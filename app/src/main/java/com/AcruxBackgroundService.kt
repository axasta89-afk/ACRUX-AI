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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
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
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

internal object AcruxConfig {
    const val GROQ_API_KEY = "PASTE_YOUR_NEW_GROQ_API_KEY_HERE"
    const val GROQ_MODEL = "openai/gpt-oss-120b"
    const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
}

class AcruxBackgroundService : Service(),
    VoskRecognitionListener,
    TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.acrux.assistant.START_SERVICE"
        const val ACTION_STOP = "com.acrux.assistant.STOP_SERVICE"
        const val ACTION_WAKE_WORD = "com.acrux.assistant.WAKE_WORD"
        const val ACTION_COMMAND = "com.acrux.assistant.COMMAND"
        const val ACTION_REPLY = "com.acrux.assistant.REPLY"
        const val ACTION_ERROR = "com.acrux.assistant.ERROR"
        const val ACTION_DEBUG = "com.acrux.assistant.DEBUG"

        private const val CHANNEL_ID = "acrux_background_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ACRUX_SERVICE"

        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false

    @Volatile
    private var modelLoading = false

    @Volatile
    private var isCapturingCommand = false

    @Volatile
    private var isProcessingCommand = false

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskService: VoskSpeechService? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private var lastWakeText = ""
    private var lastWakeTime = 0L
    private var lastCommand = ""
    private var lastCommandTime = 0L

    private var pendingSpeech: String? = null
    private var afterSpeech: (() -> Unit)? = null

    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        textToSpeech = TextToSpeech(this, this)

        sendDebug("ACRUX background service created")
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

            ACTION_START, null -> {
                startForegroundServiceNotification()

                if (!isRunning) {
                    isRunning = true
                    sendDebug("ACRUX is starting...")
                    loadVoskModel()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            ttsReady = true

            textToSpeech?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {
                        sendDebug("ACRUX speaking...")
                    }

                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            val callback = afterSpeech
                            afterSpeech = null
                            pendingSpeech = null

                            callback?.invoke()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post {
                            sendDebug("Voice output error")
                            val callback = afterSpeech
                            afterSpeech = null
                            pendingSpeech = null
                            callback?.invoke()
                        }
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int
                    ) {
                        handler.post {
                            sendDebug("Voice output error: $errorCode")
                            val callback = afterSpeech
                            afterSpeech = null
                            pendingSpeech = null
                            callback?.invoke()
                        }
                    }
                }
            )

            pendingSpeech?.let {
                speakAndThen(it, afterSpeech)
            }

            sendDebug("ACRUX voice engine ready")
        } else {
            ttsReady = false
            sendDebug("ACRUX voice engine unavailable")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ACRUX Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description = "Keeps ACRUX ready for its wake word"

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ACRUX is listening")
                .setContentText("Microphone active. Say ACRUX to activate.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(message: String) {
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ACRUX is listening")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun sendDebug(message: String) {
        Log.d(TAG, message)

        val intent = Intent(ACTION_DEBUG).apply {
            setPackage(packageName)
            putExtra("message", message)
            putExtra("debug", message)
        }

        sendBroadcast(intent)
    }

    private fun sendError(message: String) {
        Log.e(TAG, message)

        val intent = Intent(ACTION_ERROR).apply {
            setPackage(packageName)
            putExtra("message", message)
        }

        sendBroadcast(intent)
    }

    private fun loadVoskModel() {
        if (modelLoading || voskModel != null) return

        modelLoading = true
        updateNotification("Preparing voice recognition...")
        sendDebug("Checking offline voice model...")

        Thread {
            try {
                val modelDirectory = File(filesDir, MODEL_NAME)

                if (!modelDirectory.exists() ||
                    modelDirectory.listFiles().isNullOrEmpty()
                ) {
                    downloadAndExtractModel(modelDirectory)
                }

                handler.post {
                    try {
                        voskModel = Model(modelDirectory.absolutePath)
                        modelLoading = false

                        sendDebug("Offline voice model ready")
                        updateNotification(
                            "Microphone active. Say ACRUX to activate."
                        )

                        startVoskListening()
                    } catch (e: Exception) {
                        modelLoading = false
                        sendError(
                            "Could not load voice model: ${e.message}"
                        )
                    }
                }

            } catch (e: Exception) {
                modelLoading = false

                handler.post {
                    sendError(
                        "Voice model download failed: ${e.message}"
                    )
                }
            }
        }.start()
    }

    private fun downloadAndExtractModel(
        modelDirectory: File
    ) {
        val zipFile = File(cacheDir, "$MODEL_NAME.zip")

        sendDebug("Downloading offline voice model...")
        updateNotification("Downloading offline voice model...")

        val connection =
            URL(MODEL_URL).openConnection() as HttpURLConnection

        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.requestMethod = "GET"
        connection.connect()

        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(
                "Download failed: HTTP ${connection.responseCode}"
            )
        }

        connection.inputStream.use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }

        connection.disconnect()

        sendDebug("Extracting offline voice model...")

        val extractionDirectory = File(cacheDir, "vosk_extract")
        if (extractionDirectory.exists()) {
            extractionDirectory.deleteRecursively()
        }
        extractionDirectory.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val outputFile = File(extractionDirectory, entry.name)

                if (!outputFile.canonicalPath.startsWith(
                        extractionDirectory.canonicalPath + File.separator
                    )
                ) {
                    throw SecurityException("Invalid model archive path")
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

        val extractedModel = File(extractionDirectory, MODEL_NAME)

        if (!extractedModel.exists()) {
            throw IllegalStateException(
                "Downloaded model folder not found"
            )
        }

        if (modelDirectory.exists()) {
            modelDirectory.deleteRecursively()
        }

        if (!extractedModel.renameTo(modelDirectory)) {
            extractedModel.copyRecursively(modelDirectory, overwrite = true)
        }

        zipFile.delete()
        extractionDirectory.deleteRecursively()
    }

    private fun startVoskListening() {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        if (voskModel == null) {
            sendDebug("Voice model is not ready yet")
            return
        }

        try {
            stopVoskListening()

            voskRecognizer = Recognizer(voskModel, 16000.0f)

            voskService = VoskSpeechService(
                voskRecognizer,
                16000.0f
            )

            voskService?.startListening(this)

            sendDebug("Microphone active. Say ACRUX to activate.")
            updateNotification(
                "Microphone active. Say ACRUX to activate."
            )

        } catch (e: Exception) {
            sendError(
                "Could not start microphone listening: ${e.message}"
            )

            scheduleWakeWordRestart(3000)
        }
    }

    private fun stopVoskListening() {
        try {
            voskService?.stop()
        } catch (_: Exception) {
        }

        try {
            voskService?.shutdown()
        } catch (_: Exception) {
        }

        voskService = null

        try {
            voskRecognizer?.close()
        } catch (_: Exception) {
        }

        voskRecognizer = null
    }

    override fun onPartialResult(hypothesis: String?) {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        if (hypothesis.isNullOrBlank()) return

        val text = extractVoskText(hypothesis)
        if (text.isBlank()) return

        if (containsWakeWord(text)) {
            activateAcrux("")
        }
    }

    override fun onResult(hypothesis: String?) {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        if (hypothesis.isNullOrBlank()) return

        processVoskText(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        if (hypothesis.isNullOrBlank()) return

        processVoskText(hypothesis)
    }

    override fun onError(exception: Exception?) {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        sendDebug(
            "Voice recognition restarting: ${exception?.message ?: "unknown error"}"
        )

        scheduleWakeWordRestart(2000)
    }

    override fun onTimeout() {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        scheduleWakeWordRestart(500)
    }

    private fun extractVoskText(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
                .trim()
                .lowercase(Locale.US)
        } catch (_: Exception) {
            ""
        }
    }

    private fun processVoskText(hypothesis: String) {
        val recognizedText = extractVoskText(hypothesis)

        if (recognizedText.isBlank()) return

        val now = System.currentTimeMillis()

        if (recognizedText == lastWakeText &&
            now - lastWakeTime < 2500
        ) {
            return
        }

        if (containsWakeWord(recognizedText)) {
            lastWakeText = recognizedText
            lastWakeTime = now

            val command = recognizedText
                .replace("a crux", "")
                .replace("acrux", "")
                .trim()

            activateAcrux(command)
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val normalized = text
            .lowercase(Locale.US)
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized == "acrux" ||
            normalized == "a crux" ||
            normalized.startsWith("acrux ") ||
            normalized.startsWith("a crux ")
    }

    private fun activateAcrux(command: String) {
        if (!isRunning || isCapturingCommand || isProcessingCommand) {
            return
        }

        isCapturingCommand = true

        stopVoskListening()

        sendDebug("ACRUX activated")
        updateNotification("ACRUX activated")

        val wakeIntent = Intent(ACTION_WAKE_WORD).apply {
            setPackage(packageName)
        }
        sendBroadcast(wakeIntent)

        if (command.isNotBlank()) {
            processCommand(command)
        } else {
            handler.postDelayed({
                startCommandRecognition()
            }, 700)
        }
    }
       private fun startCommandRecognition() {
        if (!isRunning || !isCapturingCommand) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendError("Speech recognition is not available on this device.")
            resumeWakeWordListening()
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        sendDebug("ACRUX is listening for your command...")
                        updateNotification("Listening for your command...")
                    }

                    override fun onBeginningOfSpeech() {
                        sendDebug("Voice detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        sendDebug("Processing your command...")
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                            else -> "Speech recognition error: $error"
                        }

                        sendDebug(message)
                        resumeWakeWordListening()
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val command = matches?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        if (command.isBlank()) {
                            sendDebug("I did not hear a command.")
                            resumeWakeWordListening()
                            return
                        }

                        sendDebug("Heard: $command")
                        processCommand(command)
                    }

                    override fun onPartialResults(
                        partialResults: android.os.Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: android.os.Bundle?
                    ) {}
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
                    Locale.getDefault()
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    Locale.getDefault().toLanguageTag()
                )
                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }

            speechRecognizer?.startListening(speechIntent)

        } catch (e: Exception) {
            sendError("Could not start command recognition: ${e.message}")
            resumeWakeWordListening()
        }
    }

    private fun processCommand(command: String) {
        val cleanedCommand = command.trim()

        if (cleanedCommand.isBlank() || isProcessingCommand) {
            resumeWakeWordListening()
            return
        }

        isProcessingCommand = true
        isCapturingCommand = false

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        sendDebug("Command received: $cleanedCommand")

        val normalized = cleanedCommand.lowercase(Locale.US)

        when {
            normalized == "freeze" ||
                normalized == "stop listening" -> {
                sendReply("ACRUX listening paused.")
                speakAndThen("Listening paused.") {
                    isProcessingCommand = false
                    isCapturingCommand = false
                    sendDebug("ACRUX listening paused")
                }
                return
            }

            normalized == "arise" ||
                normalized == "start listening" -> {
                sendReply("ACRUX is ready.")
                speakAndThen("ACRUX is ready.") {
                    isProcessingCommand = false
                    resumeWakeWordListening()
                }
                return
            }

            normalized == "stop" ||
                normalized == "goodbye" -> {
                sendReply("ACRUX stopping.")
                speakAndThen("ACRUX stopping.") {
                    stopEverything()
                    stopSelf()
                }
                return
            }
        }

        sendDebug("Thinking...")

        if (AcruxConfig.GROQ_API_KEY ==
            "PASTE_YOUR_NEW_GROQ_API_KEY_HERE" ||
            AcruxConfig.GROQ_API_KEY.isBlank()
        ) {
            val message =
                "Groq API key is not configured. Please add your API key in AcruxConfig."

            sendReply(message)
            speakAndThen(message) {
                isProcessingCommand = false
                resumeWakeWordListening()
            }
            return
        }

        networkExecutor.execute {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(AcruxConfig.GROQ_URL)
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer ${AcruxConfig.GROQ_API_KEY}"
                )

                val messages = JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "You are ACRUX, a helpful Android voice assistant. " +
                                    "Reply clearly and naturally. Keep voice replies concise."
                            )
                        }
                    )

                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", cleanedCommand)
                        }
                    )
                }

                val requestBody = JSONObject().apply {
                    put("model", AcruxConfig.GROQ_MODEL)
                    put("messages", messages)
                    put("temperature", 0.7)
                    put("max_tokens", 500)
                }

                connection.outputStream.use { output ->
                    output.write(
                        requestBody.toString().toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode

                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use {
                        it.readText()
                    }
                } else {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: "Unknown server error"
                }

                if (responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Groq error $responseCode: $responseText"
                    )
                }

                val reply = JSONObject(responseText)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    .orEmpty()

                if (reply.isBlank()) {
                    throw IllegalStateException("Empty response from Groq")
                }

                handler.post {
                    sendReply(reply)
                    speakAndThen(reply) {
                        isProcessingCommand = false
                        resumeWakeWordListening()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Groq request failed", e)

                handler.post {
                    val errorMessage =
                        "I could not connect to the assistant service. Please check your internet connection."

                    sendError("Groq request failed: ${e.message}")
                    sendReply(errorMessage)

                    speakAndThen(errorMessage) {
                        isProcessingCommand = false
                        resumeWakeWordListening()
                    }
                }

            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun sendReply(message: String) {
        val intent = Intent(ACTION_REPLY).apply {
            setPackage(packageName)
            putExtra("reply", message)
            putExtra("message", message)
        }

        sendBroadcast(intent)
    }

    private fun speakAndThen(
        message: String,
        callback: (() -> Unit)? = null
    ) {
        if (message.isBlank()) {
            callback?.invoke()
            return
        }

        if (!ttsReady || textToSpeech == null) {
            pendingSpeech = message
            afterSpeech = callback

            handler.postDelayed({
                if (!ttsReady) {
                    pendingSpeech = null
                    val pendingCallback = afterSpeech
                    afterSpeech = null
                    pendingCallback?.invoke()
                }
            }, 5000)

            return
        }

        pendingSpeech = null
        afterSpeech = callback

        val result = textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "ACRUX_${System.currentTimeMillis()}"
        )

        if (result == TextToSpeech.ERROR) {
            afterSpeech = null
            callback?.invoke()
        }
    }

    private fun resumeWakeWordListening() {
        handler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }

            speechRecognizer = null

            isCapturingCommand = false
            isProcessingCommand = false

            if (!isRunning) return@post

            sendDebug("Returning to wake-word listening...")

            handler.postDelayed({
                if (isRunning &&
                    !isCapturingCommand &&
                    !isProcessingCommand
                ) {
                    startVoskListening()
                }
            }, 1200)
        }
    }

    private fun scheduleWakeWordRestart(delay: Long) {
        handler.postDelayed({
            if (isRunning &&
                !isCapturingCommand &&
                !isProcessingCommand
            ) {
                startVoskListening()
            }
        }, delay)
    }

    private fun stopEverything() {
        isRunning = false
        isCapturingCommand = false
        isProcessingCommand = false

        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        stopVoskListening()

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }

        textToSpeech = null
        ttsReady = false

        try {
            networkExecutor.shutdownNow()
        } catch (_: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)

        sendDebug("ACRUX background service stopped")
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
     
