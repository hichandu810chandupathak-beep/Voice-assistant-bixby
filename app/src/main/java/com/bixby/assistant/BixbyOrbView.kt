package com.bixby.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

class BixbyOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        contentDescription = "Live animated Bixby voice orb"
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

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
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val pulse = 0.5f + 0.5f * sin(phase * Math.PI * 2.0).toFloat()
        val r = side * (0.255f + pulse * 0.018f)

        // Soft, breathing outer aura.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, side * 0.49f,
            intArrayOf(0xAA53DFFF.toInt(), 0x665D72FF, 0x225E4DFF, 0x003D4DFF),
            floatArrayOf(0f, 0.48f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, side * (0.43f + pulse * 0.035f), paint)
        paint.shader = null

        // Three independently rotating luminous orbital rings.
        for (i in 0..2) {
            val ringRadius = r * (1.23f + i * 0.19f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (i == 0) side * 0.026f else side * 0.014f
            paint.strokeCap = Paint.Cap.ROUND
            paint.shader = SweepGradient(
                cx, cy,
                intArrayOf(0x003DEBFF, 0xCC6FEAFF.toInt(), 0xFFB7F7FF.toInt(), 0x224B65FF, 0x003DEBFF),
                null
            )
            canvas.save()
            canvas.rotate(phase * (if (i == 1) -360f else 360f) + i * 115f, cx, cy)
            canvas.drawArc(cx-ringRadius, cy-ringRadius, cx+ringRadius, cy+ringRadius,
                18f + i * 47f, 218f - i * 24f, false, paint)
            canvas.restore()
            paint.shader = null
        }

        // Glass-like core with a bright cyan-to-indigo depth gradient.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.28f, cy - r * 0.34f, r * 1.55f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFB8F5FF.toInt(), 0xFF55C8FF.toInt(),
                0xFF536CFF.toInt(), 0xFF171A55.toInt()),
            floatArrayOf(0f, 0.19f, 0.46f, 0.76f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Specular highlight and fine inner edge.
        paint.color = 0x77FFFFFF
        canvas.drawOval(cx-r*0.58f, cy-r*0.72f, cx+r*0.18f, cy-r*0.34f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = side * 0.012f
        paint.color = 0x99C8F7FF.toInt()
        canvas.drawCircle(cx, cy, r * 1.04f, paint)
        paint.style = Paint.Style.FILL
    }
}
