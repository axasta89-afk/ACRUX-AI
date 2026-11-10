
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var standby = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchVoiceInput()
            } else {
                showResponse("Microphone permission is required for voice input.")
            }
        }

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
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
            text = "Welcome, Sir ASTA.\n\nACRUX core is ready.\n\nPress the microphone or type a command."
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
            hint = "Type a command..."
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

        val reply = when {
            command in greetings ->
                "Hello, Sir ASTA. How can I assist you?"

            command.contains("your name") ->
                "I am ACRUX, your personal AI assistant."

            command.contains("time") ->
                "The current time is " +
                    LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("hh:mm a")
                    )

            command.contains("date") || command.contains("day") ->
                "Today is " +
                    LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy")
                    )

            command.contains("status") ->
                "All basic ACRUX systems are online."

            command == "help" ->
                "You can ask for the time, date, my name, or system status. Say FREEZE for standby and ARISE to resume."

            else ->
                "I do not know that command yet, Sir ASTA. More AI features will be added in upcoming phases."
        }

        showResponse(reply)
        speak(reply)
    }

    private fun requestVoiceInput() {
        if (ContextCompat.checkSelfPermission(
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ACRUX")
        }

        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showResponse("No speech recognition service is available on this device.")
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

            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
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
