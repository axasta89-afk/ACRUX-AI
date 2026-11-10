package com.acrux.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.view.Gravity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(5, 12, 25)
        window.navigationBarColor = Color.rgb(5, 12, 25)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(5, 12, 25))
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "A C R U X"
            textSize = 38f
            setTextColor(Color.rgb(0, 210, 255))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "PERSONAL AI ASSISTANT"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "\nSYSTEM INITIALIZED\n\nACRUX CORE: READY"
            textSize = 16f
            setTextColor(Color.rgb(0, 220, 180))
            gravity = Gravity.CENTER
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)

        setContentView(layout)
    }
}
