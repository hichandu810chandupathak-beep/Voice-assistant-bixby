package com.chandan.localassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Launcher entry point. Starts the microphone foreground service only after
 * the user has granted RECORD_AUDIO while this Activity is visible.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            hasMicrophonePermission()

        if (microphoneGranted) {
            startWakeWordService()
        } else {
            statusView.text =
                "Microphone permission is required to enable Bixby wake-word listening."
            startButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createScreen()

        if (hasMicrophonePermission()) {
            startWakeWordService()
        } else {
            requestAssistantPermissions()
        }
    }

    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(12, 16, 31))
        }

        val title = TextView(this).apply {
            text = "Samsung Bixby"
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "On-device wake-word assistant"
            textSize = 15f
            setTextColor(android.graphics.Color.rgb(174, 187, 220))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(22))
        }

        statusView = TextView(this).apply {
            text = "Preparing Bixby…"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }

        startButton = Button(this).apply {
            text = "Enable Bixby listening"
            isAllCaps = false
            setOnClickListener {
                if (hasMicrophonePermission()) {
                    startWakeWordService()
                } else {
                    requestAssistantPermissions()
                }
            }
        }

        stopButton = Button(this).apply {
            text = "Stop listening"
            isAllCaps = false
            setOnClickListener {
                AssistantForegroundService.stopWakeWordListening(this@MainActivity)
                statusView.text = "Bixby listening stopped."
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }

        root.addView(title, matchWidth())
        root.addView(description, matchWidth())
        root.addView(statusView, matchWidth())
        root.addView(startButton, matchWidth())
        root.addView(stopButton, matchWidth())

        setContentView(root)
        stopButton.isEnabled = false
    }

    private fun requestAssistantPermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun startWakeWordService() {
        if (!hasMicrophonePermission()) {
            requestAssistantPermissions()
            return
        }

        val started = AssistantForegroundService.startWakeWordListening(this)
        if (started) {
            statusView.text =
                "Bixby service started. On-device wake-word listening is initializing."
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            statusView.text =
                "Android could not start microphone listening. Check microphone permission and try again."
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
