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

    private data class Bull(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val quality: Float,
        val frameW: Int,
        val frameH: Int,
        val timestampMs: Long,
    )

    @Volatile
    private var bull: Bull? = null

    private val circlePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val crossPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    fun updateBull(cx: Float, cy: Float, r: Float, quality: Float, frameW: Int, frameH: Int) {
        bull = Bull(cx, cy, r, quality, frameW, frameH, System.currentTimeMillis())
        postInvalidate()
    }

    fun clearBull() {
        bull = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bull ?: return
        // Drop stale detections after 1s so a lost lock visibly fades away
        if (System.currentTimeMillis() - b.timestampMs > 1000) return
        if (b.frameW == 0 || b.frameH == 0) return

        // Frame is letterboxed inside the view. Compute the actual rendered
        // rectangle assuming AspectRatioSurfaceView-style fit-inside behaviour.
        val viewAspect = width.toFloat() / height
        val frameAspect = b.frameW.toFloat() / b.frameH
        val renderW: Float
        val renderH: Float
        val offX: Float
        val offY: Float
        if (frameAspect > viewAspect) {
            // Frame is wider than view -> letterbox top/bottom
            renderW = width.toFloat()
            renderH = width / frameAspect
            offX = 0f
            offY = (height - renderH) / 2f
        } else {
            // Frame is taller -> pillarbox left/right
            renderH = height.toFloat()
            renderW = height * frameAspect
            offX = (width - renderW) / 2f
            offY = 0f
        }
        val sx = renderW / b.frameW
        val sy = renderH / b.frameH
        val cx = offX + b.cx * sx
        val cy = offY + b.cy * sy
        val cr = b.r * min(sx, sy)

        canvas.drawCircle(cx, cy, cr, circlePaint)
        val armLen = cr.coerceAtLeast(30f) * 1.5f
        canvas.drawLine(cx - armLen, cy, cx + armLen, cy, crossPaint)
        canvas.drawLine(cx, cy - armLen, cx, cy + armLen, crossPaint)
    }
}
