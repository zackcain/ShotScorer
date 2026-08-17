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

    private data class State(
        val bulls: List<Bull>,
        val activeIndex: Int,
        val card: CardRect?,
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

    fun updateFrame(bulls: List<Bull>, activeIndex: Int, card: CardRect?, frameW: Int, frameH: Int) {
        state = State(bulls, activeIndex, card, frameW, frameH, System.currentTimeMillis())
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

        for ((i, b) in s.bulls.withIndex()) {
            val cx = offX + b.cx * sx
            val cy = offY + b.cy * sy
            val cr = b.r * scale
            if (i == s.activeIndex) {
                canvas.drawCircle(cx, cy, cr, activeCirclePaint)
                val armLen = cr.coerceAtLeast(30f) * 1.5f
                canvas.drawLine(cx - armLen, cy, cx + armLen, cy, activeCrossPaint)
                canvas.drawLine(cx, cy - armLen, cx, cy + armLen, activeCrossPaint)
            } else {
                canvas.drawCircle(cx, cy, cr, inactivePaint)
            }
        }
    }
}
