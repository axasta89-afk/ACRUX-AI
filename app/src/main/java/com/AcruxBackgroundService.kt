
package com.acrux.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

internal object AcruxConfig {
    const val GROQ_API_KEY = "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"
    const val GROQ_URL =
        "https://api.groq.com/openai/v1/chat/completions"
    const val GROQ_MODEL = "openai/gpt-oss-120b"
}

class AcruxBackgroundService : Service() {

    companion object {
        const val ACTION_START =
            "com.acrux.assistant.START_SERVICE"
        const val ACTION_STOP =
            "com.acrux.assistant.STOP_SERVICE"
        const val ACTION_WAKE_WORD =
            "com.acrux.assistant.WAKE_WORD"
        const val ACTION_COMMAND =
            "com.acrux.assistant.COMMAND"
        const val ACTION_REPLY =
            "com.acrux.assistant.REPLY"
        const val ACTION_ERROR =
            "com.acrux.assistant.ERROR"
        const val ACTION_DEBUG =
            "com.acrux.assistant.DEBUG"

        private const val CHANNEL_ID = "acrux_background_channel"
        private const val NOTIFICATION_ID = 7001
    }

    private val handler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var isRunning = false
    private var isListening = false
    private var isProcessingCommand = false
    private var isFrozen = false
    private var ttsReady = false

    private var recognitionGeneration = 0
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private var speechCallback: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeTTS()
        debug("ACRUX service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                startForegroundServiceNotification()
                startListeningService()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ACRUX Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "ACRUX voice assistant background service"

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ACRUX is listening")
                .setContentText("Say ACRUX to activate.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startListeningService() {
        if (checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendError("Microphone permission is not granted.")
            stopSelf()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendError(
                "Speech recognition is not available on this device."
            )
            stopSelf()
            return
        }

        isRunning = true
        isFrozen = false

        debug("ACRUX is listening")
        debug("Microphone active. Say ACRUX to activate.")

        startWakeWordListening()
    }

    private fun createRecognitionIntent(): Intent {
        return Intent(
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
                3
            )

            putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE,
                packageName
            )

            putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                false
            )
        }
    }

    private fun startWakeWordListening() {
        if (!isRunning || isFrozen || isProcessingCommand) {
            return
        }

        startRecognition(false)
    }

    private fun startCommandListening() {
        if (!isRunning || isProcessingCommand) {
            return
        }

        debug("ACRUX activated. Listening for command.")
        startRecognition(true)
    }

    private fun startRecognition(commandMode: Boolean) {
        if (!isRunning || isListening) {
            return
        }

        if (checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendError("Microphone permission is missing.")
            return
        }

        try {
            destroyRecognizer()

            val generation = recognitionGeneration

            val recognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer = recognizer
            isListening = true

            recognizer.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        debug(
                            if (commandMode)
                                "Listening for your command..."
                            else
                                "ACRUX is listening"
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        debug("Speech detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        debug("Processing speech...")
                    }

                    override fun onError(error: Int) {
                        if (generation != recognitionGeneration) {
                            return
                        }

                        isListening = false
                        destroyRecognizer()

                        debug("Speech recognition error: $error")

                        if (!isRunning || isProcessingCommand) {
                            return
                        }

                        if (
                            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        ) {
                            sendError(
                                "Microphone permission is required."
                            )
                            return
                        }

                        handler.postDelayed({
                            if (!isRunning || isProcessingCommand) {
                                return@postDelayed
                            }

                            startWakeWordListening()
                        }, 1200)
                    }

                    override fun onResults(results: Bundle?) {
                        if (generation != recognitionGeneration) {
                            return
                        }

                        isListening = false
                        destroyRecognizer()

                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val spokenText =
                            matches?.firstOrNull()?.trim().orEmpty()

                        if (spokenText.isBlank()) {
                            restartRecognition(commandMode)
                            return
                        }

                        debug("Heard: $spokenText")

                        if (commandMode) {
                            processVoiceCommand(spokenText)
                        } else {
                            checkWakeWord(spokenText)
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            recognizer.startListening(createRecognitionIntent())

        } catch (e: Exception) {
            isListening = false
            destroyRecognizer()
            debug("Recognition start error: ${e.message}")
            restartRecognition(commandMode)
        }
    }

    private fun destroyRecognizer() {
        recognitionGeneration++

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null
        isListening = false
    }

    private fun restartRecognition(commandMode: Boolean) {
        if (!isRunning || isProcessingCommand) {
            return
        }

        handler.postDelayed({
            if (!isRunning || isProcessingCommand) {
                return@postDelayed
            }

            if (commandMode) {
                startCommandListening()
            } else {
                startWakeWordListening()
            }
        }, 900)
    }


private fun checkWakeWord(text: String) {

    val normalized = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    val words = normalized.split(" ").filter { it.isNotBlank() }

    var wakeStart = -1
    var wakeCount = 0

    for (i in words.indices) {

        if (isAcruxMatch(words[i])) {
            wakeStart = i
            wakeCount = 1
            break
        }

        if (i + 1 < words.size) {

            val combined = words[i] + words[i + 1]

            if (isAcruxMatch(combined)) {
                wakeStart = i
                wakeCount = 2
                break
            }

            if (
                words[i] == "a" &&
                (words[i + 1] == "rise" || words[i + 1] == "rice")
            ) {
                wakeStart = i
                wakeCount = 2
                break
            }
        }

        if (
            words[i] == "arise" ||
            words[i] == "rice"
        ) {
            wakeStart = i
            wakeCount = 1
            break
        }
    }

    if (wakeStart == -1) {
        debug("Wake word not detected: $normalized")
        restartRecognition(false)
        return
    }

    debug("ACRUX activated!")

    sendBroadcast(
        Intent(ACTION_WAKE_WORD).setPackage(packageName)
    )

    val remainingText = words
        .drop(wakeStart + wakeCount)
        .joinToString(" ")
        .trim()

    if (remainingText.isNotBlank()) {
        processVoiceCommand(remainingText)
    } else {
        debug("Waiting for your command...")
        startCommandListening()
    }
}

private fun isAcruxMatch(word: String): Boolean {

    val aliases = setOf(
        "acrux",
        "acruk",
        "acrox",
        "acrus",
        "accrux",
        "acru"
    )

    if (word in aliases) return true

    if (word.length !in 4..6) return false

    return editDistance(word, "acrux") <= 1
}

private fun editDistance(a: String, b: String): Int {

    val previous = IntArray(b.length + 1) { it }

    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i

        for (j in 1..b.length) {
            val cost =
                if (a[i - 1] == b[j - 1]) 0 else 1

            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost
            )
        }

        for (j in previous.indices) {
            previous[j] = current[j]
        }
    }

    return previous[b.length]
}

    private fun processVoiceCommand(text: String) {
        if (!isRunning || isProcessingCommand) {
            return
        }

        isProcessingCommand = true

        debug("Command: $text")

        sendBroadcast(
            Intent(ACTION_COMMAND)
                .setPackage(packageName)
                .putExtra("command", text)
                .putExtra("message", text)
        )

        val command = text
            .lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")

        when {
            command == "stop" || command == "stop acrux" -> {
                speakThenStop("ACRUX shutting down.")
            }

            command == "freeze" || command == "freeze acrux" -> {
                speakText("ACRUX frozen.") {
                    isFrozen = true
                    isProcessingCommand = false
                    startWakeWordListening()
                }
            }

            command == "arise" || command == "arise acrux" -> {
                isFrozen = false

                speakText("ACRUX is ready.") {
                    isProcessingCommand = false
                    startWakeWordListening()
                }
            }

            else -> {
                if (isFrozen) {
                    isFrozen = false
                }

                askGroq(text)
            }
        }
    }
    
    private fun askGroq(userMessage: String) {
        debug("Connecting to Groq...")

        Thread {
            var connection: HttpURLConnection? = null

            try {
                if (
                    AcruxConfig.GROQ_API_KEY ==
                    "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"
                ) {
                    throw Exception(
                        "Groq API key has not been configured."
                    )
                }

                connection = URL(
                    AcruxConfig.GROQ_URL
                ).openConnection() as HttpURLConnection

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

                val messageObject = JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                }

                val messages = org.json.JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "You are ACRUX, a helpful voice assistant. " +
                                "Give clear, concise and friendly answers."
                            )
                        }
                    )

                    put(messageObject)
                }

                val requestBody = JSONObject().apply {
                    put("model", AcruxConfig.GROQ_MODEL)
                    put("messages", messages)
                    put("temperature", 0.7)
                    put("max_tokens", 500)
                }

                connection.outputStream.use { output ->
                    output.write(
                        requestBody.toString().toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode = connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val responseText = responseStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (responseCode !in 200..299) {
                    throw Exception(
                        "Groq HTTP $responseCode: $responseText"
                    )
                }

                val json = JSONObject(responseText)

                val reply = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                handler.post {
                    if (!isRunning) {
                        return@post
                    }

                    if (reply.isBlank()) {
                        finishCommand(
                            "I could not generate a response."
                        )
                    } else {
                        debug("ACRUX response received")

                        speakText(reply) {
                            broadcastReply(reply)
                            isProcessingCommand = false
                            startWakeWordListening()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("ACRUX", "Groq request failed", e)

                handler.post {
                    if (!isRunning) {
                        return@post
                    }

                    val errorMessage =
                        "Sorry, I could not connect. ${e.message ?: ""}"

                    sendError(errorMessage)

                    speakText(
                        "Sorry, I could not connect to the server."
                    ) {
                        isProcessingCommand = false
                        startWakeWordListening()
                    }
                }

            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun finishCommand(message: String) {
        speakText(message) {
            broadcastReply(message)
            isProcessingCommand = false
            startWakeWordListening()
        }
    }

    private fun initializeTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {

                val result = tts?.setLanguage(
                    Locale.getDefault()
                )

                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) {
                            handler.post {
                                runPendingSpeechCallback()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            handler.post {
                                runPendingSpeechCallback()
                            }
                        }

                        override fun onError(
                            id: String?,
                            errorCode: Int
                        ) {
                            handler.post {
                                runPendingSpeechCallback()
                            }
                        }
                    }
                )

                debug("Text to speech initialized")

                pendingSpeech?.let { pending ->
                    pendingSpeech = null
                    speakText(
                        pending.first,
                        pending.second
                    )
                }

            } else {
                ttsReady = false
                debug("Text to speech initialization failed")
            }
        }
    }

    private fun speakText(
        text: String,
        after: (() -> Unit)? = null
    ) {
        if (!isRunning) {
            return
        }

        if (isFrozen && text != "ACRUX is ready.") {
            after?.invoke()
            return
        }

        if (!ttsReady || tts == null) {
            pendingSpeech = Pair(text, after)

            handler.postDelayed({
                if (pendingSpeech?.first == text) {
                    pendingSpeech = null
                    after?.invoke()
                }
            }, 5000)

            return
        }

        speechCallback = after

        val utteranceId = UUID.randomUUID().toString()

        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        if (result == TextToSpeech.ERROR) {
            runPendingSpeechCallback()
        }
    }

    private fun runPendingSpeechCallback() {
        val callback = speechCallback
        speechCallback = null
        callback?.invoke()
    }

    private fun speakThenStop(text: String) {
        speakText(text) {
            stopEverything()
        }

        handler.postDelayed({
            if (isRunning) {
                stopEverything()
            }
        }, 5000)
    }

    private fun broadcastReply(reply: String) {
        sendBroadcast(
            Intent(ACTION_REPLY)
                .setPackage(packageName)
                .putExtra("reply", reply)
                .putExtra("message", reply)
        )
    }

    private fun sendError(message: String) {
        sendBroadcast(
            Intent(ACTION_ERROR)
                .setPackage(packageName)
                .putExtra("message", message)
        )

        debug(message)
    }

    private fun debug(message: String) {
        Log.d("ACRUX", message)

        sendBroadcast(
            Intent(ACTION_DEBUG)
                .setPackage(packageName)
                .putExtra("message", message)
                .putExtra("debug", message)
        )
    }

    private fun stopEverything() {
        if (!isRunning && speechRecognizer == null) {
            stopSelf()
            return
        }

        isRunning = false
        isProcessingCommand = false
        isFrozen = false

        handler.removeCallbacksAndMessages(null)

        destroyRecognizer()

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }

        tts = null
        ttsReady = false
        pendingSpeech = null
        speechCallback = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false

        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }

        tts = null

        debug("ACRUX service stopped")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
