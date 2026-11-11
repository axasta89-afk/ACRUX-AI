package com.acrux.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService as VoskSpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class AcruxBackgroundService : Service() {

    companion object {
        const val ACTION_START =
            "com.acrux.assistant.ACTION_START"
        const val ACTION_STOP =
            "com.acrux.assistant.ACTION_STOP"
        const val ACTION_WAKE_WORD =
            "com.acrux.assistant.ACTION_WAKE_WORD"
        const val ACTION_COMMAND =
            "com.acrux.assistant.ACTION_COMMAND"
        const val ACTION_ERROR =
            "com.acrux.assistant.ACTION_ERROR"
        const val ACTION_DEBUG =
            "com.acrux.assistant.ACTION_DEBUG"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DEBUG = "debug"

        private const val CHANNEL_ID =
            "acrux_background_channel"

        private const val NOTIFICATION_ID = 1001

        private const val MODEL_NAME =
            "vosk-model-small-en-us-0.15"

        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private val executor =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var awaitingCommand = false

    @Volatile
    private var isStarting = false

    @Volatile
    private var isStopping = false

    @Volatile
    private var lastPartialDebugTime = 0L

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: VoskSpeechService? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sendDebug("SERVICE CREATED")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {
                sendDebug("START ACTION RECEIVED")

                try {
                    startForegroundNotification()
                    sendDebug("FOREGROUND NOTIFICATION STARTED")
                } catch (e: Exception) {
                    sendDebug("FOREGROUND ERROR: ${e.message}")
                    sendError(
                        "Could not show foreground notification: ${e.message}"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                val permissionGranted =
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                sendDebug("MICROPHONE PERMISSION: $permissionGranted")

                if (!permissionGranted) {
                    sendError(
                        "Microphone permission is not granted."
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!isStarting && speechService == null) {
                    isStarting = true
                    isStopping = false

                    executor.execute {
                        try {
                            sendDebug("BACKGROUND ENGINE STARTING")
                            sendStatusNotification(
                                "Preparing offline voice recognition..."
                            )

                            startVosk()

                            if (speechService != null) {
                                sendDebug(
                                    "VOSK STARTUP FINISHED. Waiting for speech..."
                                )
                                sendStatusNotification(
                                    "Recognition engine started. Say ACRUX."
                                )
                            } else if (!isStopping) {
                                sendDebug(
                                    "WARNING: Speech service is NULL after startup"
                                )
                            }

                        } catch (e: Exception) {
                            Log.e(
                                "ACRUX",
                                "Vosk startup error",
                                e
                            )

                            sendDebug(
                                "STARTUP ERROR: ${e.message}"
                            )

                            sendError(
                                e.message
                                    ?: "Could not start voice recognition."
                            )

                            stopSelf()

                        } finally {
                            isStarting = false
                        }
                    }
                } else {
                    sendDebug(
                        "START IGNORED: Service already starting or running"
                    )
                }
            }

            ACTION_STOP -> {
                sendDebug("STOP ACTION RECEIVED")
                isStopping = true
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                sendDebug(
                    "UNKNOWN ACTION: ${intent?.action}"
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun sendDebug(message: String) {
        Log.d("ACRUX_DEBUG", message)

        val intent = Intent(ACTION_DEBUG)
            .setPackage(packageName)
            .putExtra(EXTRA_DEBUG, message)

        sendBroadcast(intent)
    }

    private fun startForegroundNotification() {

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("ACRUX is starting")
                .setContentText(
                    "Preparing microphone and offline voice recognition."
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun sendStatusNotification(message: String) {

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("ACRUX voice service")
                .setContentText(message)
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .build()

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ACRUX Background Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "ACRUX microphone listening status"
                setShowBadge(false)
            }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    private fun startVosk() {

        sendDebug("CHECKING OFFLINE MODEL")

        val modelDirectory =
            File(filesDir, MODEL_NAME)

        if (
            !File(
                modelDirectory,
                "am/final.mdl"
            ).exists()
        ) {
            sendDebug("OFFLINE MODEL NOT FOUND")
            sendStatusNotification(
                "Downloading offline voice model..."
            )

            downloadAndExtractModel(modelDirectory)
            sendDebug("MODEL DOWNLOAD AND EXTRACTION FINISHED")
        } else {
            sendDebug("OFFLINE MODEL ALREADY EXISTS")
        }

        if (
            !File(
                modelDirectory,
                "am/final.mdl"
            ).exists()
        ) {
            throw IllegalStateException(
                "Vosk model files are missing."
            )
        }

        if (isStopping) {
            sendDebug("START CANCELLED: SERVICE STOPPING")
            return
        }

        sendDebug("LOADING VOSK MODEL")
        sendStatusNotification(
            "Loading offline voice recognition..."
        )

        val loadedModel =
            Model(modelDirectory.absolutePath)

        model = loadedModel
        sendDebug("VOSK MODEL LOADED")

        if (isStopping) {
            loadedModel.close()
            model = null
            sendDebug("MODEL CLOSED: SERVICE STOPPING")
            return
        }

        sendDebug("CREATING RECOGNIZER")

        val loadedRecognizer =
            Recognizer(
                loadedModel,
                16000.0f
            )

        recognizer = loadedRecognizer
        sendDebug("RECOGNIZER CREATED AT 16000 HZ")

        val service =
            VoskSpeechService(
                loadedRecognizer,
                16000.0f
            )

        speechService = service

        sendDebug("CALLING VOSK START LISTENING")

        service.startListening(
            object : RecognitionListener {

                override fun onPartialResult(
                    hypothesis: String?
                ) {
                    if (hypothesis.isNullOrBlank()) return

                    val now = SystemClock.elapsedRealtime()

                    if (now - lastPartialDebugTime >= 800) {
                        lastPartialDebugTime = now

                        val partialText =
                            extractRecognizedText(
                                hypothesis,
                                "partial"
                            )

                        if (partialText.isNotBlank()) {
                            sendDebug(
                                "HEARING: $partialText"
                            )
                        }
                    }
                }

                override fun onResult(
                    hypothesis: String?
                ) {
                    sendDebug("FINAL RESULT CALLBACK RECEIVED")
                    sendDebug(
                        "RESULT JSON: ${hypothesis ?: "null"}"
                    )
                    processRecognizedText(hypothesis)
                }

                override fun onFinalResult(
                    hypothesis: String?
                ) {
                    sendDebug("FINAL SPEECH CALLBACK RECEIVED")
                    sendDebug(
                        "FINAL JSON: ${hypothesis ?: "null"}"
                    )
                    processRecognizedText(hypothesis)
                }

                override fun onError(
                    exception: Exception?
                ) {
                    Log.e(
                        "ACRUX",
                        "Recognition error",
                        exception
                    )

                    val message =
                        exception?.message
                            ?: "Voice recognition error."

                    sendDebug("RECOGNITION ERROR: $message")
                    sendError(message)
                }

                override fun onTimeout() {
                    sendDebug(
                        "RECOGNITION TIMEOUT: restarting listening"
                    )
                }
            }
        )

        sendDebug("VOSK START LISTENING CALLED")
    }

    private fun extractRecognizedText(
        json: String,
        field: String
    ): String {
        return try {
            JSONObject(json).optString(field, "").trim()
        } catch (_: Exception) {
            json.trim()
        }
    }
        private fun downloadAndExtractModel(
        modelDirectory: File
    ) {

        val zipFile =
            File(filesDir, "acrux-vosk-model.zip")

        var connection: HttpURLConnection? = null

        try {
            sendDebug("CONNECTING TO VOSK MODEL SERVER")

            connection =
                URL(MODEL_URL).openConnection()
                    as HttpURLConnection

            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.requestMethod = "GET"
            connection.connect()

            if (
                connection.responseCode !=
                HttpURLConnection.HTTP_OK
            ) {
                throw IllegalStateException(
                    "Model download failed: HTTP ${connection.responseCode}"
                )
            }

            sendDebug("DOWNLOADING MODEL FILE")

            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }

            sendDebug(
                "MODEL ZIP DOWNLOADED: ${zipFile.length()} BYTES"
            )

            val root =
                filesDir.canonicalFile

            ZipInputStream(
                zipFile.inputStream().buffered()
            ).use { zip ->

                var entry = zip.nextEntry

                while (entry != null) {

                    val outputFile =
                        File(
                            filesDir,
                            entry.name
                        ).canonicalFile

                    if (
                        outputFile.path != root.path &&
                        !outputFile.path.startsWith(
                            root.path + File.separator
                        )
                    ) {
                        throw SecurityException(
                            "Invalid model archive."
                        )
                    }

                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()

                        FileOutputStream(
                            outputFile
                        ).use { output ->
                            zip.copyTo(output)
                        }
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (
                !File(
                    modelDirectory,
                    "am/final.mdl"
                ).exists()
            ) {
                throw IllegalStateException(
                    "Downloaded model is incomplete."
                )
            }

            sendDebug("MODEL EXTRACTION SUCCESSFUL")

        } finally {
            connection?.disconnect()

            if (zipFile.exists()) {
                zipFile.delete()
            }
        }
    }

    private fun processRecognizedText(
        json: String?
    ) {

        if (json.isNullOrBlank()) {
            sendDebug("EMPTY RECOGNITION RESULT")
            return
        }

        try {
            val text =
                JSONObject(json)
                    .optString("text")
                    .lowercase(Locale.ROOT)
                    .trim()

            if (text.isBlank()) {
                sendDebug("NO WORDS RECOGNIZED")
                return
            }

            sendDebug("RECOGNIZED SPEECH: $text")

            val normalized =
                text
                    .replace(
                        Regex("[^a-z0-9 ]"),
                        " "
                    )
                    .replace(
                        Regex("\\s+"),
                        " "
                    )
                    .trim()

            val wakeWordFound =
                normalized.contains("acrux") ||
                normalized.contains("a crux")

            if (wakeWordFound) {

                val command =
                    normalized
                        .replace("acrux", "")
                        .replace("a crux", "")
                        .trim()

                awaitingCommand = true

                sendDebug("WAKE WORD DETECTED: ACRUX")

                sendBroadcast(
                    Intent(ACTION_WAKE_WORD)
                        .setPackage(packageName)
                )

                if (command.isNotBlank()) {
                    sendDebug(
                        "WAKE WORD AND COMMAND IN SAME SENTENCE"
                    )
                    sendCommand(command)
                }

                return
            }

            if (awaitingCommand) {
                sendDebug("COMMAND PHRASE DETECTED")
                sendCommand(normalized)
            } else {
                sendDebug(
                    "SPEECH DETECTED BUT NO WAKE WORD"
                )
            }

        } catch (e: Exception) {
            Log.e(
                "ACRUX",
                "Recognition parsing error",
                e
            )

            sendDebug(
                "PARSING ERROR: ${e.message}"
            )
        }
    }

    private fun sendCommand(
        command: String
    ) {

        awaitingCommand = false

        sendDebug("SENDING COMMAND TO MAIN ACTIVITY: $command")

        sendBroadcast(
            Intent(ACTION_COMMAND)
                .setPackage(packageName)
                .putExtra(
                    EXTRA_COMMAND,
                    command
                )
        )
    }

    private fun sendError(
        message: String
    ) {

        Log.e("ACRUX", message)
        sendDebug("SERVICE ERROR: $message")

        sendBroadcast(
            Intent(ACTION_ERROR)
                .setPackage(packageName)
                .putExtra(
                    EXTRA_ERROR,
                    message
                )
        )
    }

    private fun stopListening() {

        awaitingCommand = false
        sendDebug("STOPPING VOICE RECOGNITION")

        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e(
                "ACRUX",
                "Speech shutdown error",
                e
            )
            sendDebug(
                "SPEECH SHUTDOWN ERROR: ${e.message}"
            )
        }

        speechService = null

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(
                "ACRUX",
                "Recognizer close error",
                e
            )
            sendDebug(
                "RECOGNIZER CLOSE ERROR: ${e.message}"
            )
        }

        recognizer = null

        try {
            model?.close()
        } catch (e: Exception) {
            Log.e(
                "ACRUX",
                "Model close error",
                e
            )
            sendDebug(
                "MODEL CLOSE ERROR: ${e.message}"
            )
        }

        model = null
        sendDebug("VOICE RECOGNITION STOPPED")
    }

    override fun onDestroy() {

        isStopping = true
        sendDebug("SERVICE DESTROYED")

        stopListening()
        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
