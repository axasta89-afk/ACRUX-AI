package com.acrux.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText
    private lateinit var debugText: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    private var receiverRegistered = false
    private var isBackgroundRunning = false

    private val mainHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val microphoneGranted =
                permissions[Manifest.permission.RECORD_AUDIO] == true

            if (microphoneGranted) {
                startBackgroundListening()
            } else {
                showStatus("Microphone permission is required")
                addDebug("Microphone permission denied")
            }
        }

    private val voiceLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )
                    ?.firstOrNull()
                    ?.trim()

                if (!spokenText.isNullOrBlank()) {
                    inputField.setText(spokenText)
                    sendMessage(spokenText)
                }
            }
        }

    private val acruxReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            when (intent?.action) {

                AcruxBackgroundService.ACTION_WAKE_WORD -> {
                    showStatus("ACRUX activated")
                    addDebug("Wake word detected")
                }

                AcruxBackgroundService.ACTION_COMMAND -> {
                    val command = intent.getStringExtra("command")
                        ?: intent.getStringExtra("message")
                        ?: ""

                    if (command.isNotBlank()) {
                        addDebug("Command received: $command")
                    }
                }

                AcruxBackgroundService.ACTION_REPLY -> {
                    val reply = intent.getStringExtra("reply")
                        ?: intent.getStringExtra("message")
                        ?: ""

                    if (reply.isNotBlank()) {
                        showReply(reply)
                    }
                }

                AcruxBackgroundService.ACTION_ERROR -> {
                    val error = intent.getStringExtra("message")
                        ?: "Unknown error"

                    showStatus("ACRUX error")
                    addDebug(error)
                }

                AcruxBackgroundService.ACTION_DEBUG -> {
                    val message = intent.getStringExtra("message")
                        ?: intent.getStringExtra("debug")
                        ?: ""

                    if (message.isNotBlank()) {
                        addDebug(message)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
        registerAcruxReceiver()

        showStatus("ACRUX is ready")
        addDebug("Application started")
        loadSavedHistory()
    }

    private fun createInterface() {

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(
                android.graphics.Color.rgb(12, 16, 28)
            )
        }

        val title = TextView(this).apply {
            text = "A C R U X"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 18, 0, 4)
        }

        val subtitle = TextView(this).apply {
            text = "PERSONAL AI ASSISTANT"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 0, 0, 24)
        }

        statusText = TextView(this).apply {
            text = "ACRUX is ready"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(
                android.graphics.Color.rgb(80, 220, 160)
            )
            setPadding(12, 16, 12, 16)
            setBackgroundColor(
                android.graphics.Color.rgb(25, 35, 48)
            )
        }

        responseText = TextView(this).apply {
            text = "Hello. I am ACRUX."
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(14, 18, 14, 18)
            setBackgroundColor(
                android.graphics.Color.rgb(25, 35, 48)
            )
        }

        inputField = EditText(this).apply {
            hint = "Ask ACRUX anything..."
            textSize = 16f
            setSingleLine(false)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
            setPadding(14, 12, 14, 12)
            setBackgroundColor(
                android.graphics.Color.rgb(35, 43, 58)
            )
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(
                android.graphics.Color.rgb(17, 23, 36)
            )
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        
scrollView.addView(chatContainer)


        debugText = TextView(this).apply {
            text = "VOICE DEBUG\nWaiting..."
            textSize = 12f
            setTextColor(
                android.graphics.Color.rgb(150, 190, 220)
            )
            setPadding(12, 12, 12, 12)
            setBackgroundColor(
                android.graphics.Color.rgb(20, 27, 40)
            )
        }

        rootLayout.addView(title)
        rootLayout.addView(subtitle)
        rootLayout.addView(statusText)
        rootLayout.addView(responseText)

        addSpace(rootLayout, 12)

        rootLayout.addView(
            inputField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addSpace(rootLayout, 10)

        addButton(rootLayout, "SEND MESSAGE") {
            val message = inputField.text.toString().trim()

            if (message.isNotBlank()) {
                sendMessage(message)
            }
        }

        addButton(rootLayout, "VOICE INPUT") {
            startVoiceInput()
        }

        addButton(rootLayout, "CHAT HISTORY") {
            showChatHistory()
        }

        addButton(rootLayout, "CLEAR HISTORY") {
            clearChatHistory()
        }

        addButton(rootLayout, "START BACKGROUND LISTENING") {
            checkPermissionsAndStart()
        }

        addButton(rootLayout, "STOP BACKGROUND LISTENING") {
            stopBackgroundListening()
        }

        addSpace(rootLayout, 12)

        val debugHeading = TextView(this).apply {
            text = "VOICE DEBUG"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        rootLayout.addView(debugHeading)

        rootLayout.addView(
            debugText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            )
        )

        addSpace(rootLayout, 10)

        rootLayout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(rootLayout)
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {

        val button = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false

            setOnClickListener {
                action()
            }
        }

        parent.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 5
            }
        )
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {
        val space = View(this)

        parent.addView(
            space,
            LinearLayout.LayoutParams(1, height)
        )
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun addDebug(message: String) {

        runOnUiThread {

            val time = SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            val oldText = debugText.text.toString()
            val newText = "$oldText\n$time $message"

            debugText.text = newText
                .lines()
                .takeLast(35)
                .joinToString("\n")
        }
    }

    private fun showReply(message: String) {

        runOnUiThread {
            responseText.text = message
            addChatMessage("ACRUX", message)
        }
    }

    private fun addChatMessage(
        sender: String,
        message: String
    ) {

        val textView = TextView(this).apply {
            text = "$sender: $message"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(10, 10, 10, 10)
        }

        chatContainer.addView(textView)

        saveHistoryMessage(sender, message)

        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
        private fun registerAcruxReceiver() {

        val filter = IntentFilter().apply {
            addAction(AcruxBackgroundService.ACTION_WAKE_WORD)
            addAction(AcruxBackgroundService.ACTION_COMMAND)
            addAction(AcruxBackgroundService.ACTION_REPLY)
            addAction(AcruxBackgroundService.ACTION_ERROR)
            addAction(AcruxBackgroundService.ACTION_DEBUG)
        }

        try {
            ContextCompat.registerReceiver(
                this,
                acruxReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            receiverRegistered = true

        } catch (e: Exception) {
            addDebug("Receiver registration failed: ${e.message}")
        }
    }

    private fun checkPermissionsAndStart() {

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startBackgroundListening()
        } else {
            permissionLauncher.launch(
                missingPermissions.toTypedArray()
            )
        }
    }

    private fun startBackgroundListening() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showStatus("Microphone permission required")
            return
        }

        try {
            val serviceIntent = Intent(
                this,
                AcruxBackgroundService::class.java
            ).apply {
                action = AcruxBackgroundService.ACTION_START
            }

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            isBackgroundRunning = true

            showStatus("ACRUX is starting...")
            addDebug("Starting background listening")

        } catch (e: Exception) {
            showStatus("Could not start ACRUX")
            addDebug("Service start error: ${e.message}")
        }
    }

    private fun stopBackgroundListening() {

        try {
            val serviceIntent = Intent(
                this,
                AcruxBackgroundService::class.java
            )

            stopService(serviceIntent)

            isBackgroundRunning = false

            showStatus("ACRUX stopped")
            addDebug("Background listening stopped")

        } catch (e: Exception) {
            addDebug("Stop service error: ${e.message}")
        }
    }

    private fun startVoiceInput() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Voice recognition is unavailable",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

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
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )
            }

            voiceLauncher.launch(intent)

        } catch (e: Exception) {
            addDebug("Voice input error: ${e.message}")
        }
    }

    private fun sendMessage(message: String) {

        val cleanedMessage = message.trim()

        if (cleanedMessage.isBlank()) return

        inputField.setText("")

        addChatMessage("YOU", cleanedMessage)
        showStatus("ACRUX is thinking...")

        if (
            AcruxConfig.GROQ_API_KEY.isBlank() ||
            AcruxConfig.GROQ_API_KEY ==
            "PASTE_YOUR_NEW_GROQ_API_KEY_HERE"
        ) {
            val error =
                "Groq API key is not configured. Add your key in AcruxConfig."

            showReply(error)
            showStatus("API key required")
            return
        }

        thread {

            var connection: HttpURLConnection? = null

            try {

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

                val messages = JSONArray().apply {

                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "You are ACRUX, a helpful personal AI assistant. " +
                                    "Answer clearly, naturally and accurately."
                            )
                        }
                    )

                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", cleanedMessage)
                        }
                    )
                }

                val requestBody = JSONObject().apply {
                    put("model", AcruxConfig.GROQ_MODEL)
                    put("messages", messages)
                    put("temperature", 0.7)
                    put("max_tokens", 700)
                }

                connection.outputStream.use { output ->
                    output.write(
                        requestBody.toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode

                val responseText = if (responseCode in 200..299) {

                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

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
                    throw IllegalStateException(
                        "Empty response from Groq"
                    )
                }

                runOnUiThread {
                    showReply(reply)
                    showStatus("ACRUX is ready")
                }

            } catch (e: Exception) {

                runOnUiThread {

                    showStatus("Connection error")

                    showReply(
                        "I could not connect to the assistant service. " +
                            "Please check your internet connection."
                    )

                    addDebug(
                        "Groq request failed: ${e.message}"
                    )
                }

            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun saveHistoryMessage(
        sender: String,
        message: String
    ) {

        try {

            val preferences = getSharedPreferences(
                "acrux_chat_history",
                Context.MODE_PRIVATE
            )

            val history = preferences.getString(
                "messages",
                "[]"
            ) ?: "[]"

            val array = JSONArray(history)

            val item = JSONObject().apply {
                put("sender", sender)
                put("message", message)
                put("time", System.currentTimeMillis())
            }

            array.put(item)

            preferences.edit()
                .putString("messages", array.toString())
                .apply()

        } catch (e: Exception) {
            addDebug("Could not save chat history")
        }
    }

    private fun loadSavedHistory() {

        try {

            val preferences = getSharedPreferences(
                "acrux_chat_history",
                Context.MODE_PRIVATE
            )

            val history = preferences.getString(
                "messages",
                "[]"
            ) ?: "[]"

            val array = JSONArray(history)

            for (i in 0 until array.length()) {

                val item = array.optJSONObject(i) ?: continue

                val sender = item.optString("sender")
                val message = item.optString("message")

                if (sender.isNotBlank() && message.isNotBlank()) {

                    val textView = TextView(this).apply {
                        text = "$sender: $message"
                        textSize = 14f
                        setTextColor(
                            android.graphics.Color.WHITE
                        )
                        setPadding(10, 10, 10, 10)
                    }

                    chatContainer.addView(textView)
                }
            }

        } catch (e: Exception) {
            addDebug("Could not load chat history")
        }
    }

    private fun showChatHistory() {

        val preferences = getSharedPreferences(
            "acrux_chat_history",
            Context.MODE_PRIVATE
        )

        val history = preferences.getString(
            "messages",
            "[]"
        ) ?: "[]"

        val array = JSONArray(history)

        val historyText = StringBuilder()

        for (i in 0 until array.length()) {

            val item = array.optJSONObject(i) ?: continue

            val sender = item.optString("sender")
            val message = item.optString("message")

            historyText.append(sender)
                .append(": ")
                .append(message)
                .append("\n\n")
        }

        val displayText = if (historyText.isEmpty()) {
            "No chat history available."
        } else {
            historyText.toString()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("ACRUX CHAT HISTORY")
            .setMessage(displayText)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun clearChatHistory() {

        getSharedPreferences(
            "acrux_chat_history",
            Context.MODE_PRIVATE
        ).edit()
            .clear()
            .apply()

        chatContainer.removeAllViews()

        responseText.text = "Chat history cleared."

        Toast.makeText(
            this,
            "Chat history cleared",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {

        if (receiverRegistered) {
            try {
                unregisterReceiver(acruxReceiver)
            } catch (_: Exception) {
            }

            receiverRegistered = false
        }

        mainHandler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }
}
