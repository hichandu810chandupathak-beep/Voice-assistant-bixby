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
    private var awaitingCommand = false
    private var windowManager: WindowManager? = null
    private var floatingOrb: View? = null
    private lateinit var commandRouter: CommandRouter

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        commandRouter = CommandRouter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening(); hideFloatingOrb(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> { startAsForeground(); showFloatingOrb(); startListening() }
            ACTION_SHOW_ORB -> showFloatingOrb()
            ACTION_HIDE_ORB -> hideFloatingOrb()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle("Bixby voice listening")
            .setContentText("Listening for Hey Bixby or Bixby. Tap to open.")
            .setContentIntent(pending).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun showFloatingOrb() {
        if (floatingOrb != null || !Settings.canDrawOverlays(this)) return
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.layout_floating_orb, null)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(96) }
            view.findViewById<View>(R.id.floatingOrbCore).setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            view.findViewById<TextView>(R.id.orbDismiss).setOnClickListener { hideFloatingOrb() }
            makeOrbDraggable(view, params)
            windowManager?.addView(view, params)
            floatingOrb = view
        } catch (_: Exception) { floatingOrb = null }
    }

    private fun makeOrbDraggable(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { touched, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    try { windowManager?.updateViewLayout(touched, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) touched.performClick(); true }
                else -> true
            }
        }
    }

    private fun hideFloatingOrb() {
        floatingOrb?.let { try { windowManager?.removeView(it) } catch (_: Exception) { } }
        floatingOrb = null
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendStatus("Speech recognition is not available."); hideFloatingOrb()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        listening = true
        sendStatus("Listening for Hey Bixby or Bixby.")
        speechRecognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = scheduleRestart()
            override fun onResults(results: Bundle?) { handleFinalResult(results); scheduleRestart() }
            override fun onPartialResults(partialResults: Bundle?) { /* Never execute partial hypotheses. */ }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        startRecognitionRequest()
    }

    private fun startRecognitionRequest() {
        if (!listening) return
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
        }
        try { speechRecognizer?.startListening(request) } catch (_: Exception) { scheduleRestart() }
    }

    private fun handleFinalResult(bundle: Bundle?) {
        val phrase = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (phrase.isBlank()) return
        val wake = Regex("(?i)\\\\b(?:hey\\\\s+)?bixby\\\\b").find(phrase)
        if (wake != null) {
            sendBroadcast(Intent(ACTION_WAKE_WORD).setPackage(packageName).putExtra(EXTRA_RECOGNIZED_TEXT, phrase))
            showFloatingOrb()
            val remainder = phrase.substring(wake.range.last + 1).trim(' ', ',', '.', '!', '?')
            if (remainder.isNotBlank()) executeCompleteCommand(remainder)
            else {
                awaitingCommand = true
                handler.removeCallbacks(commandTimeout)
                handler.postDelayed(commandTimeout, COMMAND_WINDOW_MS)
                sendStatus("I'm listening. Say your complete command.")
            }
        } else if (awaitingCommand) {
            awaitingCommand = false
            handler.removeCallbacks(commandTimeout)
            executeCompleteCommand(phrase)
        }
    }

    private fun executeCompleteCommand(command: String) {
        if (command.trim().length < 2) { sendStatus("Please repeat the complete command."); return }
        commandRouter.execute(command) { result ->
            sendBroadcast(Intent(ACTION_COMMAND_RESULT).setPackage(packageName).putExtra(EXTRA_STATUS, result))
        }
    }

    private val commandTimeout = Runnable {
        if (awaitingCommand) { awaitingCommand = false; sendStatus("Command listening timed out. Say Bixby to try again.") }
    }
    private fun scheduleRestart() {
        if (!listening) return
        handler.removeCallbacks(recognitionRestart)
        handler.postDelayed(recognitionRestart, RESTART_DELAY_MS)
    }
    private val recognitionRestart = Runnable {
        if (listening) { try { speechRecognizer?.cancel() } catch (_: Exception) { }; startRecognitionRequest() }
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }
    private fun stopListening() {
        listening = false; awaitingCommand = false; handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.stopListening(); speechRecognizer?.destroy() } catch (_: Exception) { }
        speechRecognizer = null
        sendStatus("Voice listening stopped.")
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Bixby voice listening", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Visible notification while voice listening is active."; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onDestroy() { stopListening(); hideFloatingOrb(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.bixby.assistant.action.START_LISTENING"
        const val ACTION_STOP = "com.bixby.assistant.action.STOP_LISTENING"
        const val ACTION_SHOW_ORB = "com.bixby.assistant.action.SHOW_ORB"
        const val ACTION_HIDE_ORB = "com.bixby.assistant.action.HIDE_ORB"
        const val ACTION_WAKE_WORD = "com.bixby.assistant.action.WAKE_WORD"
        const val ACTION_STATUS = "com.bixby.assistant.action.LISTENING_STATUS"
        const val ACTION_COMMAND_RESULT = "com.bixby.assistant.action.COMMAND_RESULT"
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "bixby_voice_listener"
        private const val NOTIFICATION_ID = 4102
        private const val RESTART_DELAY_MS = 350L
        private const val COMMAND_WINDOW_MS = 8000L
    }
}
