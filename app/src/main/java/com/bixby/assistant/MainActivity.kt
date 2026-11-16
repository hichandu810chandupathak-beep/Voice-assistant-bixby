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
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var commandRouter: CommandRouter
    private var listeningRequested = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) requestVoicePermissions()
        else Toast.makeText(this, "Allow display over other apps to show the floating orb.", Toast.LENGTH_LONG).show()
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startVoiceService()
        else Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_LONG).show()
    }
    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = when (intent?.action) {
                WakeWordForegroundService.ACTION_WAKE_WORD -> "Wake word detected. Say your command."
                WakeWordForegroundService.ACTION_STATUS,
                WakeWordForegroundService.ACTION_COMMAND_RESULT -> intent.getStringExtra(WakeWordForegroundService.EXTRA_STATUS)
                else -> null
            }
            if (!message.isNullOrBlank()) {
                chatAdapter.addMessage(message)
                scrollChatToBottom()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 22)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 22)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        commandRouter = CommandRouter(this)
        tts = TextToSpeech(this, this)
        val list = findViewById<RecyclerView>(R.id.chatRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        chatAdapter = ChatAdapter()
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = chatAdapter
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener { sendMessage() }
        findViewById<ImageButton>(R.id.micButton).setOnClickListener { toggleVoiceListening() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
        val filter = IntentFilter().apply {
            addAction(WakeWordForegroundService.ACTION_WAKE_WORD)
            addAction(WakeWordForegroundService.ACTION_STATUS)
            addAction(WakeWordForegroundService.ACTION_COMMAND_RESULT)
        }
        ContextCompat.registerReceiver(this, voiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.getDefault()
            val prefs = getSharedPreferences("bixby_setup", MODE_PRIVATE)
            if (prefs.getBoolean("greeting_pending", false)) {
                prefs.edit().putBoolean("greeting_pending", false).apply()
                speak("Hello, I am Bixby, how can I help you.")
            }
        }
    }

    private fun speak(message: String) {
        if (!ttsReady) return
        val locale = if (message.any { it in '\u0900'..'\u097F' }) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "bixby_reply")
    }

    private fun toggleVoiceListening() {
        if (listeningRequested) {
            startService(Intent(this, WakeWordForegroundService::class.java).setAction(WakeWordForegroundService.ACTION_STOP))
            listeningRequested = false
            chatAdapter.addMessage("Voice listening stopped.")
            scrollChatToBottom()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else requestVoicePermissions()
    }

    private fun requestVoicePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startVoiceService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startVoiceService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordForegroundService::class.java)
                .setAction(WakeWordForegroundService.ACTION_START))
            listeningRequested = true
            chatAdapter.addMessage("Voice listening started. Say “Bixby” followed by a command.")
            scrollChatToBottom()
        } catch (error: Exception) {
            listeningRequested = false
            Toast.makeText(this, "Could not start voice listening: " + error.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty()) return
        chatAdapter.addMessage(message)
        messageInput.text.clear()
        scrollChatToBottom()
        commandRouter.execute(message) { response ->
            runOnUiThread {
                chatAdapter.addMessage(response)
                scrollChatToBottom()
                speak(response)
            }
        }
    }

    private fun scrollChatToBottom() {
        findViewById<RecyclerView>(R.id.chatRecyclerView).scrollToPosition(chatAdapter.itemCount - 1)
    }

    override fun onDestroy() {
        try { unregisterReceiver(voiceReceiver) } catch (_: IllegalArgumentException) { }
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {
        private val messages = mutableListOf<String>()
        fun addMessage(message: String) { messages.add(message); notifyItemInserted(messages.lastIndex) }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val textView = TextView(parent.context).apply {
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(16, 11, 16, 11)
                background = parent.context.getDrawable(R.drawable.bg_chat_bubble)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 6, 0, 6) }
            }
            return MessageViewHolder(textView)
        }
        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) { holder.message.text = messages[position] }
        override fun getItemCount() = messages.size
        class MessageViewHolder(val message: TextView) : RecyclerView.ViewHolder(message)
    }
}
