package com.bixby.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class WakeWordForegroundService : Service() {
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var wakeWordDetected = false
    private var windowManager: WindowManager? = null
    private var floatingOrb: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                hideFloatingOrb()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                showFloatingOrb()
                startListening()
            }
            ACTION_SHOW_ORB -> showFloatingOrb()
            ACTION_HIDE_ORB -> hideFloatingOrb()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Bixby voice listening")
            .setContentText("Listening for Hey Bixby or Bixby. Tap to open the app.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showFloatingOrb() {
        if (floatingOrb != null || !Settings.canDrawOverlays(this)) return
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.layout_floating_orb, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(96)
            }

            view.findViewById<View>(R.id.floatingOrbCore).setOnClickListener {
                val open = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(open)
            }
            view.findViewById<TextView>(R.id.orbDismiss).setOnClickListener {
                hideFloatingOrb()
            }
            makeOrbDraggable(view, params)
            windowManager?.addView(view, params)
            floatingOrb = view
        } catch (_: Exception) {
            floatingOrb = null
        }
    }

    private fun makeOrbDraggable(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { touched, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try { windowManager?.updateViewLayout(touched, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) touched.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun hideFloatingOrb() {
        floatingOrb?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
        }
        floatingOrb = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendStatus("Speech recognition is not available.")
            hideFloatingOrb()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        listening = true
        sendStatus("Listening for Hey Bixby or Bixby.")
        speechRecognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = scheduleRecognitionRestart()
            override fun onResults(results: Bundle?) {
                handleRecognitionResults(results)
                scheduleRecognitionRestart()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                handleRecognitionResults(partialResults)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        startRecognitionRequest()
    }

    private fun startRecognitionRequest() {
        if (!listening || wakeWordDetected) return
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { speechRecognizer?.startListening(request) }
        catch (_: Exception) { scheduleRecognitionRestart() }
    }

    private fun handleRecognitionResults(bundle: Bundle?) {
        if (wakeWordDetected) return
        val phrases = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val match = phrases.firstOrNull { phrase ->
            val normalized = phrase.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            normalized.contains("hey bixby") || Regex("\\bbixby\\b").containsMatchIn(normalized)
        } ?: return

        wakeWordDetected = true
        try { speechRecognizer?.cancel() } catch (_: Exception) { }
        showFloatingOrb()
        sendBroadcast(
            Intent(ACTION_WAKE_WORD).setPackage(packageName)
                .putExtra(EXTRA_RECOGNIZED_TEXT, match)
        )
        handler.postDelayed({
            wakeWordDetected = false
            startRecognitionRequest()
        }, RESTART_DELAY_MS)
    }

    private fun scheduleRecognitionRestart() {
        if (!listening) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (listening && !wakeWordDetected) {
                try { speechRecognizer?.cancel() } catch (_: Exception) { }
                startRecognitionRequest()
            }
        }, RESTART_DELAY_MS)
    }

    private fun sendStatus(message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, message)
        )
    }

    private fun stopListening() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) { }
        speechRecognizer = null
        sendStatus("Voice listening stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Bixby voice listening", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible notification while voice listening is active."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopListening()
        hideFloatingOrb()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.bixby.assistant.action.START_LISTENING"
        const val ACTION_STOP = "com.bixby.assistant.action.STOP_LISTENING"
        const val ACTION_SHOW_ORB = "com.bixby.assistant.action.SHOW_ORB"
        const val ACTION_HIDE_ORB = "com.bixby.assistant.action.HIDE_ORB"
        const val ACTION_WAKE_WORD = "com.bixby.assistant.action.WAKE_WORD"
        const val ACTION_STATUS = "com.bixby.assistant.action.LISTENING_STATUS"
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "bixby_voice_listener"
        private const val NOTIFICATION_ID = 4102
        private const val RESTART_DELAY_MS = 900L
    }
}
