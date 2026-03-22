package com.barbarycoast.openclawvoice.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated circular waveform ring that reacts to audio amplitude.
 * Pulses gold/amber while listening, glows white while speaking.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val RING_SEGMENTS = 120
        private const val BASE_STROKE_WIDTH = 3f
        private const val MAX_DISPLACEMENT = 30f
        private const val GLOW_LAYERS = 3
    }

    enum class Mode { LISTENING, SPEAKING, IDLE }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BASE_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var amplitude = 0f
    private var smoothedAmplitude = 0f
    private var mode = Mode.IDLE
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setAmplitude(value: Float) {
        amplitude = value.coerceIn(0f, 1f)
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        if (mode == Mode.IDLE) {
            amplitude = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.75f

        // Smooth the amplitude
        smoothedAmplitude += (amplitude - smoothedAmplitude) * 0.15f

        val baseColor = when (mode) {
            Mode.LISTENING -> Color.parseColor("#FFB300") // Gold
            Mode.SPEAKING -> Color.WHITE
            Mode.IDLE -> Color.parseColor("#80FFB300") // Dim gold
        }

        // Draw glow layers
        for (layer in GLOW_LAYERS downTo 1) {
            val glowAlpha = (40 * smoothedAmplitude / layer).toInt().coerceIn(0, 80)
            glowPaint.color = baseColor
            glowPaint.alpha = glowAlpha
            glowPaint.strokeWidth = BASE_STROKE_WIDTH + layer * 6f
            drawWaveformRing(canvas, cx, cy, radius, glowPaint, smoothedAmplitude * 0.7f)
        }

        // Draw main ring
        ringPaint.color = baseColor
        val ringAlpha = when (mode) {
            Mode.IDLE -> 100
            else -> 220 + (35 * smoothedAmplitude).toInt().coerceAtMost(35)
        }
        ringPaint.alpha = ringAlpha
        ringPaint.strokeWidth = BASE_STROKE_WIDTH + smoothedAmplitude * 2f
        drawWaveformRing(canvas, cx, cy, radius, ringPaint, smoothedAmplitude)
    }

    private fun drawWaveformRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        paint: Paint,
        amp: Float
    ) {
        val step = (2 * PI / RING_SEGMENTS).toFloat()
        var prevX = 0f
        var prevY = 0f

        for (i in 0..RING_SEGMENTS) {
            val angle = i * step

            // Multiple sine waves for organic movement
            val displacement = amp * MAX_DISPLACEMENT * (
                0.5f * sin(angle * 5 + phase) +
                0.3f * sin(angle * 8 - phase * 1.5f) +
                0.2f * cos(angle * 3 + phase * 0.7f)
            ).toFloat()

            val r = radius + displacement
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)

            if (i > 0) {
                canvas.drawLine(prevX, prevY, x, y, paint)
            }
            prevX = x
            prevY = y
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
