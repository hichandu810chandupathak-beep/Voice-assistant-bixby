package com.bixby.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

class BixbyOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0.5f
    private var rotation = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val phase = it.animatedValue as Float
            pulse = 0.5f + 0.5f * kotlin.math.sin(phase * Math.PI * 2).toFloat()
            rotation = phase * 360f
            invalidate()
        }
    }

    init { contentDescription = "Animated Bixby voice orb" }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * (0.31f + pulse * 0.035f)
        val glowRadius = radius * (1.65f + pulse * 0.2f)

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(0x8867D7FF.toInt(), 0x443D78FF, 0x003D78FF),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)

        paint.shader = RadialGradient(
            cx - radius * 0.22f, cy - radius * 0.28f, radius * 1.55f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF79E5FF.toInt(), 0xFF587BFF.toInt(), 0xFF171D55.toInt()),
            floatArrayOf(0f, 0.28f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.color = 0xAA9CEBFF.toInt()
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.drawArc(
            cx - radius * 1.18f, cy - radius * 1.18f,
            cx + radius * 1.18f, cy + radius * 1.18f,
            205f, 105f, false, paint
        )
        canvas.restore()
        paint.style = Paint.Style.FILL
    }
}
