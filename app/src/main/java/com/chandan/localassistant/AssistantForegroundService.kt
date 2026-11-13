package com.chandan.localassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class AssistantForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "bixby_assistant_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_SHOW_OVERLAY =
            "com.chandan.localassistant.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY =
            "com.chandan.localassistant.action.HIDE_OVERLAY"

        /**
         * Call from a visible activity or another user-initiated foreground flow.
         * The user must first grant Display over other apps permission.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var assistantOverlayView: AssistantOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showAssistantOverlay()
            ACTION_HIDE_OVERLAY -> assistantOverlayView?.hide()
        }

        return START_STICKY
    }

    private fun showAssistantOverlay() {
        if (assistantOverlayView == null) {
            assistantOverlayView = AssistantOverlayView(applicationContext) { message ->
                Log.d("SamsungBixby", "Overlay message submitted: $message")
            }
        }

        val shown = assistantOverlayView?.show() == true
        if (!shown) {
            Log.w(
                "SamsungBixby",
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
                description = "Keeps the assistant foreground service active."
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
            .setContentTitle("Samsung Bixby Active")
            .setContentText("Assistant service is active")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        assistantOverlayView?.hide()
        assistantOverlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
