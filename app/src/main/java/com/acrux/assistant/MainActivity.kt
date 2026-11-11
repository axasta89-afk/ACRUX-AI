
package com.acrux.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val GROQ_API_KEY =
            "PASTE_YOUR_NEW_GROQ_API_KEY_HERE"

        private const val GROQ_MODEL = "openai/gpt-oss-120b"

        private const val GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions"
    }

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var standby = false
    private var requestInProgress = false
    private var receiverRegistered = false

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AcruxBackgroundService.ACTION_WAKE_WORD -> {
                    val reply = "Yes, Sir ASTA. I'm listening."
                    statusText.text = "● ACRUX ACTIVATED"
                    showReply(reply)
                    speak(reply)
                }

                AcruxBackgroundService.ACTION_COMMAND -> {
                    val command = intent.getStringExtra(
                        AcruxBackgroundService.EXTRA_COMMAND
                    )

                    if (!command.isNullOrBlank()) {
                        inputField.setText(command)
                        processCommand(command)
                    }
                }

                AcruxBackgroundService.ACTION_ERROR -> {
                    val error = intent.getStringExtra(
                        AcruxBackgroundService.EXTRA_ERROR
                    )
                    statusText.text = "● VOICE SERVICE ERROR"
                    showResponse(
                        "ACRUX voice error: ${error ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                launchVoiceInput()
            } else {
                showResponse("Microphone permission is required.")
            }
        }

    private val backgroundAudioPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                requestBackgroundStart()
            } else {
                showResponse(
                    "Microphone permission is required for background listening."
                )
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            startBackgroundService()
        }

    private val voiceLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )
                    ?.firstOrNull()

                if (!spokenText.isNullOrBlank()) {
                    inputField.setText(spokenText)
                    processCommand(spokenText)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(5, 12, 25)
        window.navigationBarColor = Color.rgb(5, 12, 25)

        createDashboard()
        registerVoiceReceiver()

        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            ttsReady =
                result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        } else {
            ttsReady = false
        }
    }

    private fun registerVoiceReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(AcruxBackgroundService.ACTION_WAKE_WORD)
            addAction(AcruxBackgroundService.ACTION_COMMAND)
            addAction(AcruxBackgroundService.ACTION_ERROR)
        }

        ContextCompat.registerReceiver(
            this,
            voiceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered = true
    }

    private fun createDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(20))
            setBackgroundColor(Color.rgb(5, 12, 25))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        val title = TextView(this).apply {
            text = "A C R U X"
            textSize = 36f
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 210, 255))
        }

        val subtitle = TextView(this).apply {
            text = "PERSONAL AI ASSISTANT"
            textSize = 12f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        statusText = TextView(this).apply {
            text = "● SYSTEM ONLINE"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 220, 180))
        }

        responseText = TextView(this).apply {
            text = "Welcome, Sir ASTA.\n\nACRUX AI is ready."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(24), dp(16), dp(24))
            background = roundedBackground(
                Color.rgb(12, 27, 46),
                Color.rgb(0, 150, 190)
            )
        }

        inputField = EditText(this).apply {
            hint = "Ask ACRUX anything..."
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(Color.rgb(18, 31, 49))
        }

        val sendButton = makeButton("SEND")
        val micButton = makeButton("VOICE INPUT")
        val historyButton = makeButton("CHAT HISTORY")
        val clearButton = makeButton("CLEAR HISTORY")
        val startButton = makeButton("START BACKGROUND")
        val stopButton = makeButton("STOP BACKGROUND")

        sendButton.setOnClickListener {
            val command = inputField.text.toString().trim()
            if (command.isNotEmpty()) {
                processCommand(command)
                inputField.text.clear()
            }
        }

        micButton.setOnClickListener {
            requestVoiceInput()
        }

        historyButton.setOnClickListener {
            showChatHistory()
        }

        clearButton.setOnClickListener {
            ChatHistory.clear(this)
            showResponse("Chat history cleared.")
        }

        startButton.setOnClickListener {
            requestBackgroundStart()
        }

        stopButton.setOnClickListener {
            stopBackgroundService()
        }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else {
                false
            }
        }

        root.addView(title)
        root.addView(subtitle, marginParams(top = 4, bottom = 30))
        root.addView(statusText, marginParams(bottom = 24))
        root.addView(responseText, marginParams(bottom = 24))
        root.addView(inputField, marginParams(bottom = 12))
        root.addView(sendButton, marginParams(bottom = 12))
        root.addView(micButton, marginParams(bottom = 12))
        root.addView(historyButton, marginParams(bottom = 12))
        root.addView(clearButton, marginParams(bottom = 24))
        root.addView(startButton, marginParams(bottom = 12))
        root.addView(stopButton)

        setContentView(scroll)
    }

    private fun processCommand(rawCommand: String) {
        val command = CommandEngine.normalize(rawCommand)

        ChatHistory.save(this, "user", rawCommand)

        when (command) {
            "start background",
            "start background service" -> {
                requestBackgroundStart()
                return
            }

            "stop background",
            "stop background service" -> {
                stopBackgroundService()
                return
            }
        }

        if (CommandEngine.isStop(rawCommand)) {
            tts?.stop()
            showReply("Voice output stopped, Sir ASTA.")
            return
        }

        if (CommandEngine.isFreeze(rawCommand)) {
            standby = true
            statusText.text = "● STANDBY MODE"
            showReply("Entering standby mode, Sir ASTA.")
            return
        }

        if (standby && !CommandEngine.isArise(rawCommand)) {
            showReply("ACRUX is in standby. Say ARISE to resume.")
            return
        }

        if (CommandEngine.isArise(rawCommand)) {
            standby = false
            statusText.text = "● SYSTEM ONLINE"
            val reply = "I am online, Sir ASTA."
            showReply(reply)
            speak(reply)
            return
        }

        val localReply = CommandEngine.getLocalReply(rawCommand)

        if (localReply != null) {
            showReply(localReply)
            speak(localReply)
            return
        }

        askGroq(rawCommand.trim())
    }

    private fun showChatHistory() {
        val messages = ChatHistory.load(this)

        if (messages.isEmpty()) {
            showResponse("No chat history available yet.")
            return
        }

        val formatter = SimpleDateFormat(
            "dd MMM, hh:mm a",
            Locale.getDefault()
        )

        val history = buildString {
            append("════ CHAT HISTORY ════\n\n")

            messages.takeLast(50).forEach { message ->
                val speaker =
                    if (message.role == "user") "YOU" else "ACRUX"

                append("[$speaker]\n")
                append(message.content)
                append("\n")
                append(formatter.format(Date(message.timestamp)))
                append("\n\n────────────────\n\n")
            }
        }

        responseText.text = history
    }

    private fun requestBackgroundStart() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundAudioPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            startBackgroundService()
        }
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(
                this,
                AcruxBackgroundService::class.java
            ).apply {
                action = AcruxBackgroundService.ACTION_START
            }

            ContextCompat.startForegroundService(this, serviceIntent)

            statusText.text = "● STARTING VOICE SERVICE"
            showResponse(
                "ACRUX voice service is starting. First startup may download the speech model."
            )
        } catch (error: Exception) {
            showResponse(
                "Background service error: ${error.message ?: "Unable to start"}"
            )
        }
    }

    private fun stopBackgroundService() {
        val serviceIntent = Intent(
            this,
            AcruxBackgroundService::class.java
        ).apply {
            action = AcruxBackgroundService.ACTION_STOP
        }

        try {
            startService(serviceIntent)
            statusText.text = "● SYSTEM ONLINE"
            showResponse("Background service stop requested.")
        } catch (error: Exception) {
            stopService(serviceIntent)
            statusText.text = "● SYSTEM ONLINE"
            showResponse("Background service stopped.")
        }
    }

    private fun askGroq(prompt: String) {
        if (requestInProgress) {
            showResponse("Please wait. ACRUX is processing your previous request.")
            return
        }

        if (
            GROQ_API_KEY == "PASTE_YOUR_NEW_GROQ_API_KEY_HERE" ||
            GROQ_API_KEY.isBlank()
        ) {
            showReply("Groq API key is not configured. Add your new key locally.")
            return
        }

        requestInProgress = true
        statusText.text = "● CONNECTING TO GROQ..."
        showResponse("ACRUX is thinking...")

        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    requestGroq(prompt)
                }
            } catch (error: Exception) {
                "Groq connection error: ${error.message ?: "Please try again."}"
            }

            requestInProgress = false
            statusText.text = "● SYSTEM ONLINE"
            showReply(reply)
            speak(reply)
        }
    }

    private fun requestGroq(prompt: String): String {
        val connection =
            URL(GROQ_URL).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.setRequestProperty(
                "Authorization",
                "Bearer $GROQ_API_KEY"
            )

            val messages = JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You are ACRUX, a helpful personal AI assistant. " +
                                "Address the user as Sir ASTA. " +
                                "Understand English, Hindi and Hinglish. " +
                                "Be friendly, truthful and concise."
                        )
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )

            val body = JSONObject()
                .put("model", GROQ_MODEL)
                .put("messages", messages)
                .put("max_completion_tokens", 800)
                .toString()

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream =
                if (code in 200..299) connection.inputStream
                else connection.errorStream

            val response = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: throw IOException("Empty response from Groq.")

            val json = JSONObject(response)

            if (code !in 200..299) {
                val message = json.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Groq request failed. HTTP $code"

                throw IOException(message)
            }

            val choices = json.optJSONArray("choices")
                ?: throw IOException("Groq returned no choices.")

            val message = choices.optJSONObject(0)
                ?.optJSONObject("message")
                ?: throw IOException("Groq response message is missing.")

            val reply = message.optString("content").trim()

            if (reply.isEmpty()) {
                throw IOException("Groq returned an empty response.")
            }

            return reply
        } finally {
            connection.disconnect()
        }
    }

    private fun requestVoiceInput() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchVoiceInput()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchVoiceInput() {
        val intent = Intent(
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
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak to ACRUX"
            )
        }

        try {
            voiceLauncher.launch(intent)
        } catch (error: ActivityNotFoundException) {
            showResponse("No speech recognition app is available.")
        }
    }

    private fun showReply(message: String) {
        showResponse(message)
        ChatHistory.save(this, "assistant", message)
    }

    private fun showResponse(message: String) {
        responseText.text = message
    }

    private fun speak(message: String) {
        if (ttsReady) {
            tts?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ACRUX_REPLY"
                
