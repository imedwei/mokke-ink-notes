package com.writer.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Thin vertical rail on the left edge of the portrait cue view.
 * Shows a scaled-down minimap of the main content — visual texture
 * for orientation, not readable text.
 *
 * Tapping anywhere on the rail unfolds back to the notes view.
 */
class ContextRail @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Minimap bitmap rendered from main content (set by the activity). */
    var minimapBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the rail is tapped — triggers unfold to notes view. */
    var onTap: (() -> Unit)? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 102 // ~40% opacity for background texture
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw minimap if available
        val bmp = minimapBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        }

        // Draw right border (visual separator)
        canvas.drawLine(width.toFloat(), 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    /** Shared palm rejection filter — set by WritingActivity. */
    var touchFilter: TouchFilter? = null

    private var tapAccepted = false
    private var tapDownTime = 0L
    private val maxTapDurationMs = 300L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isFinger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        if (!isFinger) return false

        val tf = touchFilter
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchMinorDp = event.touchMinor / ScreenMetrics.density
                tapAccepted = tf?.evaluateDown(
                    event.pointerCount, touchMinorDp, event.eventTime, event.x, event.y
                ) != TouchFilter.Decision.REJECT
                tapDownTime = event.eventTime
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = event.eventTime - tapDownTime
                if (tapAccepted && tapDuration < maxTapDurationMs) {
                    onTap?.invoke()
                }
                tapAccepted = false
                return true
            }
        }
        return true
    }
}
