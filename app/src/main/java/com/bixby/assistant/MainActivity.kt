package com.bixby.assistant

import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity() {

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messageInput: EditText

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
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

        sendButton.setOnClickListener { sendMessage() }
        micButton.setOnClickListener {
            Toast.makeText(this, "Voice input will be connected next.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty()) return

        chatAdapter.addMessage(message)
        messageInput.text.clear()
        findViewById<RecyclerView>(R.id.chatRecyclerView)
            .scrollToPosition(chatAdapter.itemCount - 1)
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
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 6, 0, 6)
                }
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
