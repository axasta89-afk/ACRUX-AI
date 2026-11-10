
package com.acrux.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        // APNI GEMINI API KEY YAHAN PASTE KARO
        private const val GEMINI_API_KEY = "PASTE_YOUR_NEW_API_KEY_HERE"

        private const val GEMINI_MODEL = "gemini-3.8-flash"
    }

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var standby = false

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

        tts = TextToSpeech(this, this)

        createDashboard()
    }

    private fun createDashboard() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(20))
            setBackgroundColor(Color.rgb(5, 12, 25))
        }

        val scrollView = ScrollView(this).apply {
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
            text = "Welcome, Sir ASTA.\n\nACRUX Gemini AI is ready.\n\nAsk me anything."
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
        val micButton = makeButton("🎙 VOICE INPUT")

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
        root.addView(micButton)

        setContentView(scrollView)
    }

    private fun processCommand(rawCommand: String) {

        val command = rawCommand
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")

        if (command == "stop") {
            tts?.stop()
            showResponse("Voice output stopped, Sir ASTA.")
            return
        }

        if (command == "freeze") {
            standby = true
            statusText.text = "● STANDBY MODE"
            showResponse("Entering standby mode, Sir ASTA.")
            return
        }

        if (standby && command != "arise") {
            showResponse("ACRUX is in standby. Say ARISE to resume.")
            return
        }

        if (command == "arise") {
            standby = false
            statusText.text = "● SYSTEM ONLINE"
            val reply = "I am online, Sir ASTA."
            showResponse(reply)
            speak(reply)
            return
        }

        val greetings = setOf(
            "hi", "hii", "hiii", "hiiii",
            "hello", "helloo", "hellooo",
            "hlo", "hloo", "hlooo",
            "hey", "heyy", "heyyy",
            "good morning", "good afternoon",
            "good evening"
        )

        when {
            command in greetings -> {
                val reply = "Hello, Sir ASTA. How can I assist you?"
                showResponse(reply)
                speak(reply)
            }

            command.contains("your name") -> {
                val reply = "I am ACRUX, your personal AI assistant."
                showResponse(reply)
                speak(reply)
            }

            command.contains("time") -> {
                val reply = "The current time is " +
                    java.text.SimpleDateFormat(
                        "hh:mm a",
                        Locale.getDefault()
                    ).format(java.util.Date())

                showResponse(reply)
                speak(reply)
            }

            command.contains("date") || command.contains("day") -> {
                val reply = "Today is " +
                    java.text.SimpleDateFormat(
                        "EEEE, dd MMMM yyyy",
                        Locale.getDefault()
                    ).format(java.util.Date())

                showResponse(reply)
                speak(reply)
            }

            command.contains("status") -> {
                val reply = "ACRUX basic systems are online."
                showResponse(reply)
                speak(reply)
            }

            command == "help" -> {
                val reply = "Ask me anything. Gemini AI is connected for general questions. Say FREEZE for standby and ARISE to resume."
                showResponse(reply)
                speak(reply)
            }

            else -> {
                askGemini(rawCommand)
            }
        }
    }

    private fun askGemini(prompt: String) {

        if (GEMINI_API_KEY == "PASTE_YOUR_NEW_API_KEY_HERE" ||
            GEMINI_API_KEY.isBlank()
        ) {
            showResponse("Please add your Gemini API key in MainActivity.kt.")
            return
        }

        statusText.text = "● GEMINI AI THINKING..."
        showResponse("ACRUX is thinking, Sir ASTA...")

        Thread {

            var connection: HttpURLConnection? = null

            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        GEMINI_MODEL + ":generateContent"
                )

                connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "x-goog-api-key",
                    GEMINI_API_KEY
                )

                val requestBody = JSONObject().apply {

                    put(
                        "system_instruction",
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "You are ACRUX, a helpful personal AI assistant. " +
                                        "Address the user as Sir ASTA. " +
                                        "Be friendly, clear, concise and truthful. " +
                                        "You can answer questions but cannot directly " +
                                        "control phone features unless the app provides " +
                                        "that functionality."
                                )
                            )
                        )
                    )

                    put(
                        "contents",
                        org.json.JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "parts",
                                    org.json.JSONArray().put(
                                        JSONObject().put("text", prompt)
                                    )
                                )
                            }
                        )
                    )

                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("temperature", 0.7)
                            put("maxOutputTokens", 800)
                        }
                    )
                }

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

                val responseBody = responseStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

                if (responseCode !in 200..299) {
                    val errorMessage = try {
                        JSONObject(responseBody)
                            .optJSONObject("error")
                            ?.optString("message")
                            ?: "API request failed. Code: $responseCode"
                    } catch (_: Exception) {
                        "API request failed. Code: $responseCode"
                    }

                    throw Exception(errorMessage)
                }

                val json = JSONObject(responseBody)

                val candidates = json.optJSONArray("candidates")
                    ?: throw Exception("Gemini returned no answer.")

                if (candidates.length() == 0) {
                    throw Exception("Gemini returned an empty answer.")
                }

                val content = candidates
                    .getJSONObject(0)
                    .optJSONObject("content")
                    ?: throw Exception("No response content received.")

                val parts = content.optJSONArray("parts")
                    ?: throw Exception("No response text received.")

                val answerBuilder = StringBuilder()

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)
                    answerBuilder.append(part?.optString("text", "") ?: "")
                }

                val answer = answerBuilder.toString().trim()

                if (answer.isBlank()) {
                    throw Exception("Gemini returned an empty answer.")
                }

                runOnUiThread {
                    statusText.text = "● GEMINI AI ONLINE"
                    showResponse(answer)
                    speak(answer)
                }

            } catch (e: Exception) {

                val errorMessage = e.message
                    ?: "Unknown connection error."

                runOnUiThread {
                    statusText.text = "● AI CONNECTION ERROR"
                    showResponse(
                        "Sorry, Sir ASTA.\n\n$errorMessage"
                    )
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    private fun requestVoiceInput() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchVoiceInput()
        } else {
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
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
                RecognizerIntent.EXTRA_PROMPT,
                "Speak to ACRUX"
            )
        }

        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showResponse(
                "No speech recognition service is available."
            )
        }
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
                "ACRUX_RESPONSE"
            )
        }
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            val result = tts?.setLanguage(Locale.getDefault())

            ttsReady =
                result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBackground(
                Color.rgb(0, 115, 160)
            )
        }
    }

    private fun roundedBackground(
        color: Int,
        strokeColor: Int? = null
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()

            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun marginParams(
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
