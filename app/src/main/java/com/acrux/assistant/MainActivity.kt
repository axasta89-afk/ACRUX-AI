
package com.acrux.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val GROQ_API_KEY =
            "PASTE_YOUR_NEW_GROQ_API_KEY_HERE"

        private const val GROQ_MODEL =
            "openai/gpt-oss-120b"

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

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Foreground service can still start if notification
            // permission is denied, but its notification may not
            // appear in the notification drawer.
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
            text = "Welcome, Sir ASTA.\n\nACRUX Groq AI is ready.\n\nAsk me anything."
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
        val startButton = makeButton("▶ START BACKGROUND")
        val stopButton = makeButton("■ STOP BACKGROUND")

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
        root.addView(micButton, marginParams(bottom = 24))
        root.addView(startButton, marginParams(bottom = 12))
        root.addView(stopButton)

        setContentView(scrollView)
    }

    private fun processCommand(rawCommand: String) {

        val command = CommandEngine.normalize(rawCommand)

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
            showResponse("Voice output stopped, Sir ASTA.")
            return
        }

        if (CommandEngine.isFreeze(rawCommand)) {
            standby = true
            statusText.text = "● STANDBY MODE"
            showResponse("Entering standby mode, Sir ASTA.")
            return
        }

        if (standby && !CommandEngine.isArise(rawCommand)) {
            showResponse("ACRUX is in standby. Say ARISE to resume.")
            return
        }

        if (CommandEngine.isArise(rawCommand)) {
            standby = false
            statusText.text = "● SYSTEM ONLINE"

            val reply = "I am online, Sir ASTA."
            showResponse(reply)
            speak(reply)
            return
        }

        val localReply = CommandEngine.getLocalReply(rawCommand)

        if (localReply != null) {
            showResponse(localReply)
            speak(localReply)
            return
        }

        askGroq(rawCommand.trim())
    }

    private fun requestBackgroundStart() {
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
            val serviceIntent =
                AcruxBackgroundService.startIntent(this)

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            showResponse(
                "ACRUX background service start requested, Sir ASTA."
            )
        } catch (error: Exception) {
            showResponse(
                "Background service error: " +
                    (error.message ?: "Unable to start service.")
            )
        }
    }

    private fun stopBackgroundService() {
        val serviceIntent =
            Intent(this, AcruxBackgroundService::class.java)

        val stopped = stopService(serviceIntent)

        if (stopped) {
            showResponse("ACRUX background service stopped.")
        } else {
            showResponse("ACRUX background service is not running.")
        }
    }

    private fun askGroq(prompt: String) {
        if (requestInProgress) {
            showResponse(
                "Please wait. ACRUX is processing your previous request."
            )
            return
        }

        if (
            GROQ_API_KEY == "PASTE_YOUR_NEW_GROQ_API_KEY_HERE" ||
            GROQ_API_KEY.isBlank()
        ) {
            showResponse("Add your new Groq API key in the local project.")
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
                "Groq connection error: " +
                    (error.message ?: "Please try again.")
            }

            requestInProgress = false
            statusText.text = "● SYSTEM ONLINE"
            showResponse(reply)
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
                                "Be friendly, clear, truthful and concise."
                        )
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )

            val requestBody = JSONObject()
                .put("model", GROQ_MODEL)
                .put("messages", messages)
                .put("max_completion_tokens", 800)
                .toString()

            connection.outputStream.use { output ->
                output.write(
                    requestBody.toByteArray(Charsets.UTF_8)
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
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: throw IOException("Empty response from Groq.")

            val json = JSONObject(responseBody)

            if (responseCode !in 200..299) {
                val message = json.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Groq request failed. HTTP $responseCode"

                throw IOException(message)
            }

            val choices = json.optJSONArray("choices")
                ?: throw IOException("Groq returned no choices.")

            val firstChoice = choices.optJSONObject(0)
                ?: throw IOException("Groq returned an empty choice.")

            val message = firstChoice.optJSONObject("message")
                ?: throw IOException("Groq response message is missing.")

            val finalReply = message
                .optString("content")
                .trim()

            if (finalReply.isEmpty()) {
                throw IOException("Groq returned an empty text response.")
            }

            return finalReply

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
                RecognizerIntent.EXTRA_PROMPT,
                "Speak to ACRUX"
            )
        }

        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showResponse("No speech recognition service is available.")
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
            background = roundedBackground(Color.rgb(0, 115, 160))
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
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
