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
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class AcruxBackgroundService : Service() {

    companion object {

        // These action names MUST match MainActivity.kt

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

        const val EXTRA_COMMAND = "command"
        const val EXTRA_ERROR = "error"

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

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: VoskSpeechService? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                try {
                    startForegroundNotification()
                } catch (e: Exception) {
                    Log.e(
                        "ACRUX",
                        "Foreground notification error",
                        e
                    )
                    sendError(
                        "Could not show foreground notification: ${e.message}"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
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
                            sendStatusNotification(
                                "Preparing offline voice recognition..."
                            )

                            startVosk()

                            sendStatusNotification(
                                "Microphone active. Say ACRUX to activate."
                            )

                            Log.d(
                                "ACRUX",
                                "Vosk listening started successfully"
                            )

                        } catch (e: Exception) {

                            Log.e(
                                "ACRUX",
                                "Vosk startup error",
                                e
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
                }
            }

            ACTION_STOP -> {

                isStopping = true

                stopListening()

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

                return START_NOT_STICKY
            }

            else -> {
                Log.w(
                    "ACRUX",
                    "Unknown service action: ${intent?.action}"
                )
            }
        }

        return START_NOT_STICKY
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
                .setContentTitle("ACRUX is listening")
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

        val modelDirectory =
            File(filesDir, MODEL_NAME)

        if (
            !File(
                modelDirectory,
                "am/final.mdl"
            ).exists()
        ) {

            sendStatusNotification(
                "Downloading offline voice model..."
            )

            downloadAndExtractModel(modelDirectory)
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

        if (isStopping) return

        sendStatusNotification(
            "Loading offline voice recognition..."
        )

        val loadedModel =
            Model(modelDirectory.absolutePath)

        model = loadedModel

        if (isStopping) {
            loadedModel.close()
            model = null
            return
        }

        val loadedRecognizer =
            Recognizer(
                loadedModel,
                16000.0f
            )

        recognizer = loadedRecognizer

        val service =
            VoskSpeechService(
                loadedRecognizer,
                16000.0f
            )

        speechService = service

        service.startListening(
            object : RecognitionListener {

                override fun onPartialResult(
                    hypothesis: String?
                ) {
                    // Partial speech is intentionally ignored.
                }

                override fun onResult(
                    hypothesis: String?
                ) {
                    processRecognizedText(hypothesis)
                }

                override fun onFinalResult(
                    hypothesis: String?
                ) {
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

                    sendError(
                        exception?.message
                            ?: "Voice recognition error."
                    )
                }

                override fun onTimeout() {
                    Log.d(
                        "ACRUX",
                        "Recognition timeout"
                    )
                }
            }
        )
    }
        private fun downloadAndExtractModel(
        modelDirectory: File
    ) {

        val zipFile =
            File(filesDir, "acrux-vosk-model.zip")

        var connection: HttpURLConnection? = null

        try {

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

            connection.inputStream.use { input ->

                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }

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

        if (json.isNullOrBlank()) return

        try {

            val text =
                JSONObject(json)
                    .optString("text")
                    .lowercase()
                    .trim()

            if (text.isBlank()) return

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

                Log.d(
                    "ACRUX",
                    "Wake word detected"
                )

                sendBroadcast(
                    Intent(ACTION_WAKE_WORD)
                        .setPackage(packageName)
                )

                if (command.isNotBlank()) {
                    sendCommand(command)
                }

                return
            }

            if (awaitingCommand) {
                sendCommand(normalized)
            }

        } catch (e: Exception) {

            Log.e(
                "ACRUX",
                "Recognition parsing error",
                e
            )
        }
    }

    private fun sendCommand(
        command: String
    ) {

        awaitingCommand = false

        Log.d(
            "ACRUX",
            "Command received: $command"
        )

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

        Log.e(
            "ACRUX",
            message
        )

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

        try {

            speechService?.stop()
            speechService?.shutdown()

        } catch (e: Exception) {

            Log.e(
                "ACRUX",
                "Speech shutdown error",
                e
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
        }

        model = null
    }

    override fun onDestroy() {

        isStopping = true

        stopListening()

        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
    
