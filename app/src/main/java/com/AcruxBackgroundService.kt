package com.acrux.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService as VoskSpeechService
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class AcruxBackgroundService : Service(), VoskRecognitionListener {

    companion object {
        const val ACTION_START = "com.acrux.assistant.ACTION_START"
        const val ACTION_STOP = "com.acrux.assistant.ACTION_STOP"
        const val ACTION_WAKE_WORD = "com.acrux.assistant.ACTION_WAKE_WORD"
        const val ACTION_COMMAND = "com.acrux.assistant.ACTION_COMMAND"
        const val ACTION_ERROR = "com.acrux.assistant.ACTION_ERROR"
        const val ACTION_DEBUG = "com.acrux.assistant.ACTION_DEBUG"

        private const val CHANNEL_ID = "acrux_background_channel"
        private const val NOTIFICATION_ID = 1001

        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var voskService: VoskSpeechService? = null
    private var androidRecognizer: SpeechRecognizer? = null

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var capturingCommand = false

    private var modelLoading = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        debug("ACRUX background service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!serviceRunning) {
                    serviceRunning = true
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification("Starting ACRUX listening...")
                    )
                    debug("ACRUX is starting")
                    loadModel()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ACRUX Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "ACRUX wake word listening service"

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ACRUX is listening")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID, createNotification(message))
    }

    private fun debug(message: String) {
        Log.d("ACRUX_SERVICE", message)

        val intent = Intent(ACTION_DEBUG)
        intent.setPackage(packageName)
        intent.putExtra("message", message)
        sendBroadcast(intent)
    }

    private fun sendError(message: String) {
        debug("ERROR: $message")

        val intent = Intent(ACTION_ERROR)
        intent.setPackage(packageName)
        intent.putExtra("error", message)
        sendBroadcast(intent)
    }

    private fun loadModel() {
        if (modelLoading || !serviceRunning) return

        modelLoading = true

        Thread {
            try {
                val modelDirectory = File(filesDir, MODEL_NAME)

                if (!modelDirectory.exists() ||
                    modelDirectory.listFiles().isNullOrEmpty()
                ) {
                    debug("Downloading offline voice model...")
                    downloadAndExtractModel(modelDirectory)
                }

                if (!serviceRunning) return@Thread

                debug("Loading Vosk voice model...")

                model = Model(modelDirectory.absolutePath)

                recognizer = Recognizer(model, 16000.0f)

                modelLoading = false

                handler.post {
                    if (serviceRunning) {
                        debug("ACRUX is listening")
                        updateNotification(
                            "Microphone active. Say ACRUX to activate."
                        )
                        startVoskListening()
                    }
                }

            } catch (e: Exception) {
                modelLoading = false
                sendError(e.message ?: "Unable to load voice model")
            }
        }.start()
    }

    private fun downloadAndExtractModel(destination: File) {
        val zipFile = File(filesDir, "$MODEL_NAME.zip")

        val connection =
            URL(MODEL_URL).openConnection() as HttpURLConnection

        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Model download failed: HTTP ${connection.responseCode}")
        }

        connection.inputStream.use { input ->
            FileOutputStream(zipFile).use { output ->
                val buffer = ByteArray(8192)
                var count: Int

                while (input.read(buffer).also { count = it } != -1) {
                    if (!serviceRunning) {
                        throw Exception("Service stopped during download")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }

        debug("Voice model downloaded. Extracting...")

        destination.parentFile?.mkdirs()

        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val outputFile = File(filesDir, entry.name)

                if (!outputFile.canonicalPath.startsWith(
                        filesDir.canonicalPath + File.separator
                    )
                ) {
                    throw Exception("Invalid model archive")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()

                    FileOutputStream(outputFile).use { output ->
                        zip.copyTo(output)
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        zipFile.delete()
        debug("Voice model extraction completed")
    }

    private fun startVoskListening() {
        if (!serviceRunning || capturingCommand) return

        try {
            val currentRecognizer = recognizer ?: return

            stopVoskAudioOnly()

            voskService = VoskSpeechService(
                currentRecognizer,
                16000.0f
            )

            voskService?.startListening(this)

            debug("Microphone active. Say ACRUX to activate.")

        } catch (e: Exception) {
            sendError(e.message ?: "Could not start microphone listening")
        }
    }

    private fun stopVoskAudioOnly() {
        try {
            voskService?.stop()
            voskService?.shutdown()
        } catch (_: Exception) {
        }

        voskService = null
    }

    override fun onPartialResult(hypothesis: String?) {
        // Partial Vosk results are intentionally ignored.
    }

    override fun onResult(hypothesis: String?) {
        processVoskText(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        processVoskText(hypothesis)
    }

    private fun processVoskText(json: String?) {
        if (json.isNullOrBlank() || !serviceRunning || capturingCommand) {
            return
        }

        try {
            val text = JSONObject(json)
                .optString("text", "")
                .lowercase(Locale.ROOT)
                .trim()

            if (text.isBlank()) return

            debug("Heard: $text")

            val wakeIndex = text.indexOf("acrux")
                .takeIf { it >= 0 }
                ?: text.indexOf("a crux")

            if (wakeIndex >= 0) {
                val wakeLength =
                    if (text.startsWith("a crux", wakeIndex)) 6 else 5

                val command = text
                    .substring(wakeIndex + wakeLength)
                    .trim()

                activateAcrux(command)
            }

        } catch (e: Exception) {
            Log.e("ACRUX_SERVICE", "Vosk result error", e)
        }
    }

    private fun activateAcrux(command: String) {
        if (!serviceRunning || capturingCommand) return

        capturingCommand = true

        debug("ACRUX wake word detected")

        stopVoskAudioOnly()

        val wakeIntent = Intent(ACTION_WAKE_WORD)
        wakeIntent.setPackage(packageName)
        sendBroadcast(wakeIntent)

        if (command.isNotBlank()) {
            sendCommand(command)
        } else {
            handler.postDelayed({
                startAndroidCommandRecognition()
            }, 1500)
        }
    }
        private fun startAndroidCommandRecognition() {
        if (!serviceRunning) return

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                sendError("Android speech recognition is unavailable")
                resumeWakeWordListening()
                return
            }

            destroyAndroidRecognizer()

            androidRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            androidRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        debug("Listening for your command...")
                        updateNotification("Listening for your command...")
                    }

                    override fun onBeginningOfSpeech() {
                        debug("Command detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                    }

                    override fun onEndOfSpeech() {
                        debug("Processing command...")
                    }

                    override fun onError(error: Int) {
                        debug("Speech recognition error: $error")
                        resumeWakeWordListening()
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val command = matches?.firstOrNull()?.trim()

                        if (!command.isNullOrBlank()) {
                            sendCommand(command)
                        } else {
                            debug("No command was detected")
                            resumeWakeWordListening()
                        }
                    }

                    override fun onPartialResults(
                        partialResults: android.os.Bundle?
                    ) {
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: android.os.Bundle?
                    ) {
                    }
                }
            )

            val speechIntent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault().toLanguageTag()
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            androidRecognizer?.startListening(speechIntent)

        } catch (e: Exception) {
            sendError(
                e.message ?: "Could not start Android speech recognition"
            )
            resumeWakeWordListening()
        }
    }

    private fun sendCommand(command: String) {
        if (command.isBlank()) {
            resumeWakeWordListening()
            return
        }

        debug("Command: $command")

        val intent = Intent(ACTION_COMMAND)
        intent.setPackage(packageName)
        intent.putExtra("command", command)
        sendBroadcast(intent)

        handler.postDelayed({
            resumeWakeWordListening()
        }, 3000)
    }

    private fun destroyAndroidRecognizer() {
        try {
            androidRecognizer?.cancel()
            androidRecognizer?.destroy()
        } catch (_: Exception) {
        }

        androidRecognizer = null
    }

    private fun resumeWakeWordListening() {
        handler.removeCallbacksAndMessages(null)

        destroyAndroidRecognizer()

        capturingCommand = false

        if (serviceRunning) {
            handler.postDelayed({
                if (serviceRunning && !capturingCommand) {
                    updateNotification(
                        "Microphone active. Say ACRUX to activate."
                    )
                    startVoskListening()
                }
            }, 2000)
        }
    }

    override fun onError(exception: Exception?) {
        debug(
            "Vosk error: ${exception?.message ?: "Unknown error"}"
        )

        if (serviceRunning && !capturingCommand) {
            handler.postDelayed({
                startVoskListening()
            }, 1500)
        }
    }

    override fun onTimeout() {
        if (serviceRunning && !capturingCommand) {
            handler.post {
                startVoskListening()
            }
        }
    }

    private fun stopEverything() {
        serviceRunning = false
        capturingCommand = false

        handler.removeCallbacksAndMessages(null)

        stopVoskAudioOnly()
        destroyAndroidRecognizer()

        try {
            recognizer?.close()
        } catch (_: Exception) {
        }

        recognizer = null

        try {
            model?.close()
        } catch (_: Exception) {
        }

        model = null
        modelLoading = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        debug("ACRUX background listening stopped")
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
    
