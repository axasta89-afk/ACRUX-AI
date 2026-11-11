package com.acrux.assistant

import android.Manifest
import android.app.Activity
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val GROQ_API_KEY =
            "PASTE_YOUR_NEW_GROQ_API_KEY_HERE"

        private const val GROQ_MODEL = "openai/gpt-oss-120b"

        private const val GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions"

        private const val ACTION_START =
            "com.acrux.assistant.ACTION_START"

        private const val ACTION_STOP =
            "com.acrux.assistant.ACTION_STOP"

        private const val ACTION_WAKE_WORD =
            "com.acrux.assistant.ACTION_WAKE_WORD"

        private const val ACTION_COMMAND =
            "com.acrux.assistant.ACTION_COMMAND"

        private const val ACTION_ERROR =
            "com.acrux.assistant.ACTION_ERROR"
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var chatLayout: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var receiverRegistered = false

    private val voiceLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText =
                    result.data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )?.firstOrNull()

                if (!spokenText.isNullOrBlank()) {
                    inputField.setText(spokenText)
                    sendMessage(spokenText)
                }
            }
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                launchVoiceInput()
            } else {
                showToast("Microphone permission required")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            when (intent?.action) {

                ACTION_WAKE_WORD -> {
                    runOnUiThread {
                        statusText.text = "ACRUX ACTIVATED"
                        speak("Yes, I am listening")
                    }
                }

                ACTION_COMMAND -> {
                    val command =
                        intent.getStringExtra("command") ?: ""

                    runOnUiThread {
                        if (command.isNotBlank()) {
                            sendMessage(command)
                        }
                    }
                }

                ACTION_ERROR -> {
                    val error =
                        intent.getStringExtra("error") ?: "Unknown error"

                    runOnUiThread {
                        statusText.text = "Background error"
                        showToast(error)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textToSpeech = TextToSpeech(this, this)

        createDashboard()
        registerVoiceReceiver()
        requestNotificationPermission()
    }

    private fun createDashboard() {

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(12))
            setBackgroundColor(Color.rgb(10, 14, 25))
        }

        val heading = TextView(this).apply {
            text = "A C R U X"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "PERSONAL AI ASSISTANT"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }

        statusText = TextView(this).apply {
            text = "SYSTEM READY"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.GREEN)
            setPadding(0, dp(14), 0, dp(14))
        }

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        scrollView = ScrollView(this).apply {
            addView(chatLayout)
            background = roundedBackground(
                Color.rgb(20, 27, 43),
                16
            )
        }

        inputField = EditText(this).apply {
            hint = "Message ACRUX..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(
                Color.rgb(30, 39, 58),
                12
            )
        }

        rootLayout.addView(
            heading,
            marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        rootLayout.addView(
            subtitle,
            marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(25)
            )
        )

        rootLayout.addView(
            statusText,
            marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )

        rootLayout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                bottomMargin = dp(12)
            }
        )

        rootLayout.addView(
            inputField,
            marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(55)
            )
        )

        addButton("SEND MESSAGE") {
            val message = inputField.text.toString().trim()

            if (message.isNotEmpty()) {
                inputField.setText("")
                sendMessage(message)
            }
        }

        addButton("VOICE INPUT") {
            checkMicrophonePermission()
        }

        addButton("CHAT HISTORY") {
            showHistory()
        }

        addButton("CLEAR HISTORY") {
            ChatHistory.clear(this)
            chatLayout.removeAllViews()
            showReply("Chat history cleared.")
        }

        addButton("START BACKGROUND LISTENING") {
            startBackgroundService()
        }

        addButton("STOP BACKGROUND LISTENING") {
            stopBackgroundService()
        }

        setContentView(rootLayout)

        showReply("ACRUX is ready. How can I help you?")
    }

    private fun addButton(
        buttonText: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = buttonText
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBackground(
                Color.rgb(34, 83, 150),
                12
            )
            setOnClickListener { action() }
        }

        rootLayout.addView(
            button,
            marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )
    }

    private fun sendMessage(message: String) {

        showReply(message)
        ChatHistory.save(this, "user", message)

        val localResponse = handleLocalCommand(message)

        if (localResponse != null) {
            showResponse(localResponse)
            speak(localResponse)
            ChatHistory.save(this, "assistant", localResponse)
            return
        }

        if (GROQ_API_KEY == "PASTE_YOUR_NEW_GROQ_API_KEY_HERE") {
            val warning =
                "Please add your Groq API key in MainActivity.kt first."

            showResponse(warning)
            speak(warning)
            return
        }

        statusText.text = "ACRUX THINKING..."

        thread {
            try {
                val response = askGroq(message)

                runOnUiThread {
                    statusText.text = "SYSTEM READY"
                    showResponse(response)
                    speak(response)
                    ChatHistory.save(
                        this,
                        "assistant",
                        response
                    )
                }

            } catch (e: Exception) {

                runOnUiThread {
                    statusText.text = "CONNECTION ERROR"

                    val errorMessage =
                        "Groq connection error: ${e.message}"

                    showResponse(errorMessage)
                }
            }
        }
    }

    private fun handleLocalCommand(message: String): String? {

        val command = message.trim().lowercase(Locale.ROOT)

        return when {

            command == "arise" ||
                command.contains("wake up") -> {
                "ACRUX is active and ready."
            }

            command == "freeze" -> {
                "ACRUX is standing by."
            }

            command == "stop" -> {
                "Stopping the current response."
            }

            command.contains("hello") ||
                command == "hi" -> {
                "Hello! I am ACRUX. How can I help you?"
            }

            command.contains("your name") -> {
                "My name is ACRUX."
            }

            command.contains("time") -> {
                "The current time is ${
                    java.text.SimpleDateFormat(
                        "hh:mm a",
                        Locale.getDefault()
                    ).format(java.util.Date())
                }"
            }

            command.contains("date") -> {
                "Today's date is ${
                    java.text.SimpleDateFormat(
                        "dd MMMM yyyy",
                        Locale.getDefault()
                    ).format(java.util.Date())
                }"
            }

            command.contains("status") -> {
                "ACRUX system is ready."
            }

            else -> null
        }
}
    private fun askGroq(message: String): String {

        val url = URL(GROQ_URL)
        val connection =
            url.openConnection() as HttpURLConnection

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
            "Bearer $GROQ_API_KEY"
        )

        val messages = JSONArray()

        messages.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "You are ACRUX, a helpful personal AI assistant. Reply clearly and naturally."
                )
        )

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", message)
        )

        val requestBody = JSONObject()
            .put("model", GROQ_MODEL)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 1024)

        connection.outputStream.use { output ->
            output.write(
                requestBody.toString().toByteArray(Charsets.UTF_8)
            )
        }

        val responseCode = connection.responseCode

        val responseStream =
            if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val responseText =
            responseStream.bufferedReader().use {
                it.readText()
            }

        connection.disconnect()

        if (responseCode !in 200..299) {
            throw Exception(
                "HTTP $responseCode: $responseText"
            )
        }

        val json = JSONObject(responseText)

        return json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun showReply(message: String) {
        addChatMessage("YOU", message, true)
    }

    private fun showResponse(message: String) {
        addChatMessage("ACRUX", message, false)
    }

    private fun addChatMessage(
        sender: String,
        message: String,
        isUser: Boolean
    ) {

        val messageView = TextView(this).apply {
            text = "$sender\n$message"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
            )

            background = roundedBackground(
                if (isUser) {
                    Color.rgb(35, 75, 125)
                } else {
                    Color.rgb(42, 48, 65)
                },
                14
            )
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.bottomMargin = dp(10)

        chatLayout.addView(messageView, params)

        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showHistory() {
        val history = ChatHistory.load(this)

        Toast.makeText(
            this,
            history.toString(),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun speak(message: String) {
        if (ttsReady) {
            textToSpeech?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ACRUX_SPEECH"
            )
        }
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            val languageResult =
                textToSpeech?.setLanguage(Locale.US)

            ttsReady =
                languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun checkMicrophonePermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchVoiceInput()
        } else {
            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun launchVoiceInput() {

        try {
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
                    RecognizerIntent.EXTRA_PROMPT,
                    "Speak to ACRUX"
                )
            }

            voiceLauncher.launch(intent)

        } catch (e: Exception) {
            showToast("Voice input is not available")
        }
    }

    private fun startBackgroundService() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkMicrophonePermission()
            showToast(
                "Allow microphone permission, then start listening again."
            )
            return
        }

        try {
            val intent = Intent(
                this,
                AcruxBackgroundService::class.java
            ).apply {
                action = ACTION_START
            }

            ContextCompat.startForegroundService(
                this,
                intent
            )

            statusText.text = "BACKGROUND LISTENING STARTED"
            showToast("ACRUX background service started")

        } catch (e: Exception) {
            showToast(
                "Could not start service: ${e.message}"
            )
        }
    }

    private fun stopBackgroundService() {

        try {
            val intent = Intent(
                this,
                AcruxBackgroundService::class.java
            ).apply {
                action = ACTION_STOP
            }

            startService(intent)

            statusText.text = "BACKGROUND LISTENING STOPPED"
            showToast("ACRUX background service stopped")

        } catch (e: Exception) {
            showToast(
                "Could not stop service: ${e.message}"
            )
        }
    }

    private fun registerVoiceReceiver() {

        val filter = IntentFilter().apply {
            addAction(ACTION_WAKE_WORD)
            addAction(ACTION_COMMAND)
            addAction(ACTION_ERROR)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                voiceReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                voiceReceiver,
                filter
            )
        }

        receiverRegistered = true
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun marginParams(
        width: Int,
        height: Int
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            width,
            height
        ).apply {
            bottomMargin = dp(8)
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    private fun showToast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {

        if (receiverRegistered) {
            try {
                unregisterReceiver(voiceReceiver)
            } catch (_: Exception) {
            }

            receiverRegistered = false
        }

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        super.onDestroy()
    }
}
