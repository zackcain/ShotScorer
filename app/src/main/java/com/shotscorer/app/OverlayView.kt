package com.shotscorer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    data class Bull(val cx: Float, val cy: Float, val r: Float)
    data class CardRect(val x: Float, val y: Float, val w: Float, val h: Float)

    /** One sample of the aim trace: (dx,dy) is the vector from the active
     *  bull to where the rifle was pointing (i.e. frame centre) when this
     *  sample was captured, expressed in FULL-resolution image pixels. */
    data class AimSample(val dx: Float, val dy: Float)

    private data class State(
        val bulls: List<Bull>,
        val activeIndex: Int,
        val card: CardRect?,
        val trace: List<AimSample>,
        val frameW: Int,
        val frameH: Int,
        val timestampMs: Long,
    )

    @Volatile
    private var state: State? = null

    private val activeCirclePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val activeCrossPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val inactivePaint = Paint().apply {
        color = Color.argb(180, 255, 200, 0) // amber, translucent
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val cardPaint = Paint().apply {
        color = Color.argb(140, 80, 180, 255) // translucent blue for card outline
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val traceLinePaint = Paint().apply {
        color = Color.argb(200, 80, 220, 120)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val traceHeadPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateFrame(
        bulls: List<Bull>,
        activeIndex: Int,
        card: CardRect?,
        trace: List<AimSample>,
        frameW: Int,
        frameH: Int,
    ) {
        state = State(bulls, activeIndex, card, trace, frameW, frameH, System.currentTimeMillis())
        postInvalidate()
    }

    fun clearBull() {
        state = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        if (System.currentTimeMillis() - s.timestampMs > 1000) return
        if (s.frameW == 0 || s.frameH == 0) return

        val viewAspect = width.toFloat() / height
        val frameAspect = s.frameW.toFloat() / s.frameH
        val renderW: Float
        val renderH: Float
        val offX: Float
        val offY: Float
        if (frameAspect > viewAspect) {
            renderW = width.toFloat()
            renderH = width / frameAspect
            offX = 0f
            offY = (height - renderH) / 2f
        } else {
            renderH = height.toFloat()
            renderW = height * frameAspect
            offX = (width - renderW) / 2f
            offY = 0f
        }
        val sx = renderW / s.frameW
        val sy = renderH / s.frameH
        val scale = min(sx, sy)

        s.card?.let { c ->
            canvas.drawRect(
                offX + c.x * sx,
                offY + c.y * sy,
                offX + (c.x + c.w) * sx,
                offY + (c.y + c.h) * sy,
                cardPaint,
            )
        }

        var activeCx = 0f
        var activeCy = 0f
        var activeCr = 0f
        for ((i, b) in s.bulls.withIndex()) {
            val cx = offX + b.cx * sx
            val cy = offY + b.cy * sy
            val cr = b.r * scale
            if (i == s.activeIndex) {
                canvas.drawCircle(cx, cy, cr, activeCirclePaint)
                val armLen = cr.coerceAtLeast(30f) * 1.5f
                canvas.drawLine(cx - armLen, cy, cx + armLen, cy, activeCrossPaint)
                canvas.drawLine(cx, cy - armLen, cx, cy + armLen, activeCrossPaint)
                activeCx = cx; activeCy = cy; activeCr = cr
            } else {
                canvas.drawCircle(cx, cy, cr, inactivePaint)
            }
        }

        // Draw the aim trace as a polyline anchored to the current active bull.
        // Each sample's (dx,dy) is in FULL-res frame pixels — scale to view.
        if (s.activeIndex in s.bulls.indices && s.trace.isNotEmpty()) {
            val trace = s.trace
            var prevX = activeCx + trace[0].dx * sx
            var prevY = activeCy + trace[0].dy * sy
            val n = trace.size
            for (i in 1 until n) {
                val fx = activeCx + trace[i].dx * sx
                val fy = activeCy + trace[i].dy * sy
                // Older = fainter. Fade from 40 alpha at oldest to 220 at newest.
                val age = (n - i).toFloat() / n
                val alpha = (220 - age * 180).toInt().coerceIn(20, 220)
                traceLinePaint.alpha = alpha
                canvas.drawLine(prevX, prevY, fx, fy, traceLinePaint)
                prevX = fx; prevY = fy
            }
            // "Where the rifle is pointing right now" — a small white dot at the head.
            canvas.drawCircle(prevX, prevY, 5f, traceHeadPaint)
        }
    }
}
