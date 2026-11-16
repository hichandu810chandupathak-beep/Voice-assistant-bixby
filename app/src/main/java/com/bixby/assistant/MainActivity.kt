package com.bixby.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity() {
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messageInput: EditText
    private var listeningRequested = false

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) requestVoicePermissions()
        else Toast.makeText(this, "Allow display over other apps to show the floating orb.", Toast.LENGTH_LONG).show()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (audioGranted) startVoiceService()
        else Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_LONG).show()
    }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WakeWordForegroundService.ACTION_WAKE_WORD -> {
                    val phrase = intent.getStringExtra(WakeWordForegroundService.EXTRA_RECOGNIZED_TEXT).orEmpty()
                    chatAdapter.addMessage("Wake word detected: $phrase. Voice assistant is ready.")
                    scrollChatToBottom()
                }
                WakeWordForegroundService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(WakeWordForegroundService.EXTRA_STATUS).orEmpty()
                    if (status.isNotBlank()) Toast.makeText(this@MainActivity, status, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 22)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 22)

        val chatRecyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        val micButton = findViewById<ImageButton>(R.id.micButton)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)
        chatAdapter = ChatAdapter()
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatRecyclerView.adapter = chatAdapter
        sendButton.setOnClickListener { sendMessage() }
        micButton.setOnClickListener { toggleVoiceListening() }

        val filter = IntentFilter().apply {
            addAction(WakeWordForegroundService.ACTION_WAKE_WORD)
            addAction(WakeWordForegroundService.ACTION_STATUS)
        }
        ContextCompat.registerReceiver(this, voiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun toggleVoiceListening() {
        if (listeningRequested) {
            val stop = Intent(this, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_STOP
            }
            startService(stop)
            listeningRequested = false
            chatAdapter.addMessage("Voice listening stopped.")
            scrollChatToBottom()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(settingsIntent)
        } else requestVoicePermissions()
    }

    private fun requestVoicePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startVoiceService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startVoiceService() {
        val serviceIntent = Intent(this, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            listeningRequested = true
            chatAdapter.addMessage("Voice listening started. Floating orb is active.")
            scrollChatToBottom()
        } catch (error: Exception) {
            listeningRequested = false
            Toast.makeText(this, "Could not start voice listening: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty()) return
        chatAdapter.addMessage(message)
        messageInput.text.clear()
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        findViewById<RecyclerView>(R.id.chatRecyclerView).scrollToPosition(chatAdapter.itemCount - 1)
    }

    override fun onDestroy() {
        try { unregisterReceiver(voiceReceiver) } catch (_: IllegalArgumentException) { }
        super.onDestroy()
    }

    private class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {
        private val messages = mutableListOf<String>()
        fun addMessage(message: String) {
            messages.add(message)
            notifyItemInserted(messages.lastIndex)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val textView = TextView(parent.context).apply {
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(16, 11, 16, 11)
                background = parent.context.getDrawable(R.drawable.bg_chat_bubble)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
            }
            return MessageViewHolder(textView)
        }
        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            holder.message.text = messages[position]
        }
        override fun getItemCount(): Int = messages.size
        class MessageViewHolder(val message: TextView) : RecyclerView.ViewHolder(message)
    }
}
