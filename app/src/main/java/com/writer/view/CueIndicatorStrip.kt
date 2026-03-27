package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Thin vertical strip on the right edge of the portrait canvas.
 * Shows dots at line positions where cue strokes exist.
 * Tapping anywhere on the strip triggers a fold to the cue view.
 *
 * Visual width: 16dp. Touch target: 40dp (extends inward).
 */
class CueIndicatorStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Line indices (in document space) that have cue content. */
    var cueLineIndices: Set<Int> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** Current scroll offset to map line indices to screen positions. */
    var scrollOffsetY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the strip is tapped — triggers fold to cue view. */
    var onTap: (() -> Unit)? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt() // Same as CanvasTheme.LINE_COLOR
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33AAAAAA // Very faint column line
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(1f)
    }

    private val dotRadius = ScreenMetrics.dp(1.5f)
    private val lineSpacing get() = ScreenMetrics.lineSpacing
    private val topMargin get() = ScreenMetrics.topMargin

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val visualWidth = ScreenMetrics.dp(16f)
        val stripLeft = width - visualWidth

        // Draw faint column line (affordance when no cues exist)
        canvas.drawLine(stripLeft, 0f, stripLeft, height.toFloat(), linePaint)

        // Draw dots for each cue line
        val cx = stripLeft + visualWidth / 2
        for (lineIndex in cueLineIndices) {
            val y = topMargin + lineIndex * lineSpacing + lineSpacing / 2 - scrollOffsetY
            if (y < 0 || y > height) continue
            canvas.drawCircle(cx, y, dotRadius, dotPaint)
        }
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
                // Only accept quick taps — palm rests are longer than 300ms
                val tapDuration = event.eventTime - tapDownTime
                if (tapAccepted && tapDuration < maxTapDurationMs) {
                    onTap?.invoke()
                }
                tapAccepted = false
                return true
            }
        }
        return true // consume all finger touches
    }
}
