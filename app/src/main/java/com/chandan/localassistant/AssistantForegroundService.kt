package com.chandan.localassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class AssistantForegroundService : Service() {

    companion object {
        private const val TAG = "SamsungBixby"
        private const val CHANNEL_ID = "bixby_assistant_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "bixby_wake_word_settings"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"

        const val ACTION_SHOW_OVERLAY =
            "com.chandan.localassistant.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY =
            "com.chandan.localassistant.action.HIDE_OVERLAY"
        const val ACTION_START_WAKE_WORD =
            "com.chandan.localassistant.action.START_WAKE_WORD"
        const val ACTION_STOP_WAKE_WORD =
            "com.chandan.localassistant.action.STOP_WAKE_WORD"

        /**
         * Start this from a visible Activity after RECORD_AUDIO is granted.
         * Android 14+ blocks microphone FGS startup from ordinary background
         * app state, so do not call this from a background receiver.
         */
        fun startWakeWordListening(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Grant microphone permission before starting wake-word listening.")
                return false
            }

            val intent = Intent(context, AssistantForegroundService::class.java)
                .setAction(ACTION_START_WAKE_WORD)

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: SecurityException) {
                Log.e(TAG, "Android denied microphone foreground-service startup.", error)
                false
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Foreground service cannot be started from the current app state.", error)
                false
            }
        }

        fun stopWakeWordListening(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java)
                .setAction(ACTION_STOP_WAKE_WORD)
            try {
                context.startService(intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not send wake-word stop request.", error)
            }
        }

        /**
         * Call from a user-visible flow. The overlay permission must already
         * have been granted in Android Settings.
         */
        fun showOverlay(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java)
                .setAction(ACTION_SHOW_OVERLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java)
                .setAction(ACTION_HIDE_OVERLAY)
            try {
                context.startService(intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not send overlay hide request.", error)
            }
        }
    }

    private var assistantOverlayView: AssistantOverlayView? = null
    private var wakeWordListener: WakeWordListener? = null

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Microphone foreground-service permission is missing.", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_WAKE_WORD -> {
                preferences.edit().putBoolean(KEY_WAKE_WORD_ENABLED, true).apply()
                startWakeWordListener()
            }

            ACTION_STOP_WAKE_WORD -> {
                preferences.edit().putBoolean(KEY_WAKE_WORD_ENABLED, false).apply()
                stopWakeWordListener()
            }

            ACTION_SHOW_OVERLAY -> showAssistantOverlay()
            ACTION_HIDE_OVERLAY -> assistantOverlayView?.hide()

            null -> {
                if (preferences.getBoolean(KEY_WAKE_WORD_ENABLED, false)) {
                    startWakeWordListener()
                }
            }
        }

        return START_STICKY
    }

    private fun startWakeWordListener() {
        if (wakeWordListener != null) return

        val listener = WakeWordListener(
            context = applicationContext,
            onWakeWordDetected = {
                Log.i(TAG, "Wake word accepted; opening Bixby overlay.")
                showAssistantOverlay()
            },
            onUnavailable = { reason ->
                Log.e(TAG, "Wake-word listener unavailable: $reason")
                preferences.edit().putBoolean(KEY_WAKE_WORD_ENABLED, false).apply()
                stopWakeWordListener()
            },
            onCommandRecognized = { command ->
                val result = CommandExecutionRouter.execute(applicationContext, command)
                Log.i(TAG, "Voice command result: $result")
                showAssistantOverlay()
                assistantOverlayView?.appendAssistantMessage("You said: $command")
                assistantOverlayView?.appendAssistantMessage(result)
            }
        )
        wakeWordListener = listener
        listener.start()
    }

    private fun stopWakeWordListener() {
        wakeWordListener?.stop()
        wakeWordListener = null
    }

    private fun showAssistantOverlay() {
        if (assistantOverlayView == null) {
            assistantOverlayView = AssistantOverlayView(applicationContext) { message ->
                val result = CommandExecutionRouter.execute(applicationContext, message)
                Log.d(TAG, "Command result: $result")
                assistantOverlayView?.appendAssistantMessage(result)
            }
        }

        val shown = assistantOverlayView?.show() == true
        if (!shown) {
            Log.w(
                TAG,
                "Overlay not shown. Grant Display over other apps permission first."
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Samsung Bixby",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Bixby microphone wake-word listening is active."
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Samsung Bixby")
            .setContentText("Bixby foreground service is active")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopWakeWordListener()
        assistantOverlayView?.hide()
        assistantOverlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
