package com.chandan.localassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context.INPUT_METHOD_SERVICE
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

/**
 * Bottom-aligned Samsung Bixby-style floating chat panel.
 *
 * Call show() only after the user has granted "Display over other apps".
 * The service/activity that owns this view should call hide() when finished.
 */
class AssistantOverlayView(
    private val context: Context,
    private val onMessageSent: (String) -> Unit = {}
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayRoot: LinearLayout? = null
    private var chatContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var messageInput: EditText? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (overlayRoot != null) return true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(
                fill = Color.argb(242, 20, 24, 39),
                stroke = Color.argb(110, 112, 137, 255),
                radius = 28f
            )
            elevation = dp(18).toFloat()
        }

        root.addView(createHeader())
        root.addView(createOrbSection())
        root.addView(createChatSection(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(132)
        ))
        root.addView(createInputBar())

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(12)
            softInputMode = SOFT_INPUT_ADJUST_RESIZE
            windowAnimations = android.R.style.Animation_Dialog
        }

        return try {
            windowManager.addView(root, params)
            overlayRoot = root
            true
        } catch (_: SecurityException) {
            false
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun hide() {
        val root = overlayRoot ?: return
        try {
            windowManager.removeView(root)
        } catch (_: IllegalArgumentException) {
            // The window was already detached by the system.
        } finally {
            overlayRoot = null
            chatContainer = null
            chatScrollView = null
            messageInput = null
        }
    }

    fun appendAssistantMessage(message: String) {
        if (message.isBlank()) return
        addChatBubble(message, fromUser = false)
    }

    private fun createHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "Bixby"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        val subtitle = TextView(context).apply {
            text = "  YOUR AI ASSISTANT"
            setTextColor(Color.rgb(151, 164, 195))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.12f
        }

        val titleGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(subtitle)
        }

        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.rgb(190, 199, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(4), 0)
            contentDescription = "Close Bixby overlay"
            setOnClickListener { hide() }
        }

        header.addView(titleGroup, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        return header
    }

    private fun createOrbSection(): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(8))
        }

        section.addView(AnimatedOrbView(context), LinearLayout.LayoutParams(dp(104), dp(104)))

        val status = TextView(context).apply {
            text = "READY WHEN YOU ARE"
            setTextColor(Color.rgb(164, 177, 215))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
        }
        section.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(22)
        ))
        return section
    }

    private fun createChatSection(): View {
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
        }

        val messages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        chatContainer = messages
        chatScrollView = scroll
        scroll.addView(messages)

        addChatBubble("Hi, I'm Bixby. How can I help?", fromUser = false)
        return scroll
    }

    private fun createInputBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(6), dp(5))
            background = roundedBackground(
                fill = Color.rgb(35, 41, 60),
                stroke = Color.argb(90, 135, 151, 205),
                radius = 24f
            )
        }

        val keyboard = TextView(context).apply {
            text = "⌨"
            setTextColor(Color.rgb(180, 194, 235))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            gravity = Gravity.CENTER
            contentDescription = "Open keyboard"
            setOnClickListener { showKeyboard() }
        }

        val input = EditText(context).apply {
            hint = "Tap to start typing"
            setHintTextColor(Color.rgb(139, 151, 179))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            singleLine = true
            background = null
            setPadding(dp(8), dp(8), dp(4), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCurrentMessage()
                    true
                } else {
                    false
                }
            }
        }
        messageInput = input

        val send = TextView(context).apply {
            text = "↑"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = roundedBackground(
                fill = Color.rgb(74, 99, 220),
                stroke = Color.TRANSPARENT,
                radius = 22f
            )
            contentDescription = "Send message"
            setOnClickListener { sendCurrentMessage() }
        }

        bar.addView(keyboard, LinearLayout.LayoutParams(dp(38), dp(40)))
        bar.addView(input, LinearLayout.LayoutParams(0, dp(42), 1f))
        bar.addView(send, LinearLayout.LayoutParams(dp(38), dp(38)))
        return bar
    }

    private fun sendCurrentMessage() {
        val input = messageInput ?: return
        val message = input.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) return

        addChatBubble(message, fromUser = true)
        input.text?.clear()
        onMessageSent(message)

        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun showKeyboard() {
        val input = messageInput ?: return
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun addChatBubble(message: String, fromUser: Boolean) {
        val container = chatContainer ?: return
        val bubble = TextView(context).apply {
            text = message
            setTextColor(Color.rgb(239, 242, 252))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(
                fill = if (fromUser) Color.rgb(65, 82, 160) else Color.rgb(43, 50, 72),
                stroke = Color.TRANSPARENT,
                radius = 16f
            )
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
            })
        }
        container.addView(row)
        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != Color.TRANSPARENT) {
                setStroke(dp(1), stroke)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class AnimatedOrbView(context: Context) : View(context) {
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var animator: ValueAnimator? = null

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.31f
            val pulse = 0.5f + 0.5f * kotlin.math.sin(phase * Math.PI * 2.0).toFloat()

            for (ring in 0..1) {
                val progress = (phase + ring * 0.5f) % 1f
                val ringRadius = radius * (1.18f + progress * 0.62f)
                wavePaint.color = Color.argb(
                    ((1f - progress) * 105f).toInt().coerceIn(0, 105),
                    106, 132, 255
                )
                canvas.drawCircle(cx, cy, ringRadius, wavePaint)
            }

            orbPaint.shader = RadialGradient(
                cx - radius * 0.28f,
                cy - radius * 0.34f,
                radius * 1.75f,
                intArrayOf(
                    Color.rgb(174, 195, 255),
                    Color.rgb(92, 112, 255),
                    Color.rgb(117, 64, 220),
                    Color.rgb(40, 43, 112)
                ),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            orbPaint.setShadowLayer(dp(12f) * (0.65f + pulse * 0.55f), 0f, 0f,
                Color.argb(175, 91, 107, 255))
            canvas.drawCircle(cx, cy, radius * (0.94f + pulse * 0.035f), orbPaint)
            orbPaint.clearShadowLayer()
            orbPaint.shader = null

            highlightPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(cx - radius * 0.25f, cy - radius * 0.30f, radius * 0.18f, highlightPaint)
        }

        private fun dp(value: Float): Float =
            value * resources.displayMetrics.density
    }
}
