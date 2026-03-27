package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.writer.model.DiagramArea

/**
 * Thin vertical strip on the right edge of the portrait canvas.
 * Shows dots at line positions where cue strokes exist.
 * Tapping: fold to cue view. Long-press on a dot: peek cue content.
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
            recomputeBlocks()
            invalidate()
        }

    /** Cue diagram areas — used to bridge gaps between stroke-occupied lines. */
    var cueDiagramAreas: List<DiagramArea> = emptyList()
        set(value) {
            field = value
            recomputeBlocks()
            invalidate()
        }

    /** Computed contiguous blocks (line ranges) for visual grouping. */
    private var visualBlocks: List<IntRange> = emptyList()

    private fun recomputeBlocks() {
        val diagramLines = mutableSetOf<Int>()
        for (area in cueDiagramAreas) {
            for (l in area.startLineIndex..area.endLineIndex) diagramLines.add(l)
        }
        val occupied = (cueLineIndices + diagramLines).sorted()
        if (occupied.isEmpty()) { visualBlocks = emptyList(); return }

        val blocks = mutableListOf<IntRange>()
        var start = occupied.first()
        var end = start
        for (i in 1 until occupied.size) {
            if (occupied[i] == end + 1) {
                end = occupied[i]
            } else {
                blocks.add(start..end)
                start = occupied[i]
                end = start
            }
        }
        blocks.add(start..end)
        visualBlocks = blocks
    }

    /** Y offset from the top of this view to the top of the canvas area. */
    var canvasTopOffset: Float = 0f

    /** Current scroll offset to map line indices to screen positions. */
    var scrollOffsetY: Float = 0f
        set(value) {
            field = value
            post { invalidate() }
        }

    /** If true, visual elements are left-aligned (for left-side rail).
     *  If false (default), right-aligned (for right-side cue strip). */
    var alignLeft: Boolean = false

    /** Called when the strip is tapped — triggers fold to cue view. */
    var onTap: (() -> Unit)? = null

    /** Called on long-press of a dot — shows cue peek preview.
     *  Parameters: lineIndex, screenY of the dot. */
    var onDotLongPress: ((lineIndex: Int, screenY: Float) -> Unit)? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(1f)
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(4f)
        strokeCap = Paint.Cap.ROUND
    }

    private val dotRadius = ScreenMetrics.dp(3f)
    private val dotHitRadius = ScreenMetrics.dp(16f)
    private val lineSpacing get() = ScreenMetrics.lineSpacing
    private val topMargin get() = ScreenMetrics.topMargin

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val visualWidth = ScreenMetrics.dp(16f)
        val stripX = if (alignLeft) 0f else width - visualWidth
        val lineX = if (alignLeft) visualWidth else stripX

        canvas.drawLine(lineX, canvasTopOffset, lineX, height.toFloat(), linePaint)

        val cx = stripX + visualWidth / 2
        for (block in visualBlocks) {
            if (block.first == block.last) {
                // Single-line block: draw a dot
                val y = dotScreenY(block.first)
                if (y >= 0 && y <= height) {
                    canvas.drawCircle(cx, y, dotRadius, dotPaint)
                }
            } else {
                // Multi-line block: draw a thick vertical segment
                val topY = dotScreenY(block.first)
                val bottomY = dotScreenY(block.last)
                if (bottomY >= 0 && topY <= height) {
                    canvas.drawLine(cx, topY.coerceAtLeast(0f), cx, bottomY.coerceAtMost(height.toFloat()), segmentPaint)
                }
            }
        }
    }

    /** Returns the screen rect bounding all visible content indicators (dots/segments). */
    fun getContentScreenRect(): android.graphics.Rect? {
        if (visualBlocks.isEmpty()) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val topY = dotScreenY(visualBlocks.first().first)
        val bottomY = dotScreenY(visualBlocks.last().last)
        return android.graphics.Rect(
            loc[0],
            loc[1] + topY.toInt(),
            loc[0] + width,
            loc[1] + bottomY.toInt()
        )
    }

    /** Compute the screen Y position of a dot for a given line index. */
    private fun dotScreenY(lineIndex: Int): Float {
        return canvasTopOffset + topMargin + lineIndex * lineSpacing + lineSpacing / 2 - scrollOffsetY
    }

    /** Find which block a touch Y position hits, returning a representative line index, or -1. */
    private fun hitTestDot(touchY: Float): Int {
        for (block in visualBlocks) {
            val topY = dotScreenY(block.first)
            val bottomY = dotScreenY(block.last)
            // Hit if touch is within the segment range (with hit radius padding)
            if (touchY >= topY - dotHitRadius && touchY <= bottomY + dotHitRadius) {
                // Return the line closest to the touch point
                val closestLine = block.minByOrNull { kotlin.math.abs(dotScreenY(it) - touchY) }
                return closestLine ?: block.first
            }
        }
        return -1
    }

    // --- Touch handling: tap + long-press ---

    var touchFilter: TouchFilter? = null

    private var tapAccepted = false
    private var tapDownTime = 0L
    private var longPressLineIndex = -1
    private var longPressFired = false
    private val maxTapDurationMs = 300L
    private val longPressDelayMs = 500L
    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        if (longPressLineIndex >= 0) {
            longPressFired = true
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val screenY = loc[1] + dotScreenY(longPressLineIndex)
            onDotLongPress?.invoke(longPressLineIndex, screenY)
        }
    }

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
                longPressFired = false

                // Check if touching near a dot — start long-press timer
                longPressLineIndex = if (tapAccepted) hitTestDot(event.y) else -1
                if (longPressLineIndex >= 0) {
                    handler.postDelayed(longPressRunnable, longPressDelayMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Cancel long-press if finger moves too far
                if (longPressLineIndex >= 0) {
                    val slop = ScreenMetrics.dp(8f)
                    val dy = kotlin.math.abs(event.y - dotScreenY(longPressLineIndex))
                    if (dy > slop + dotHitRadius) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressLineIndex = -1
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)

                if (longPressFired) {
                    // Long-press was handled — don't also fire tap
                    longPressFired = false
                    longPressLineIndex = -1
                    tapAccepted = false
                    return true
                }

                // Quick tap → fold to cues
                val tapDuration = event.eventTime - tapDownTime
                if (tapAccepted && tapDuration < maxTapDurationMs) {
                    onTap?.invoke()
                }
                tapAccepted = false
                longPressLineIndex = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                tapAccepted = false
                longPressLineIndex = -1
                longPressFired = false
                return true
            }
        }
        return true
    }
}
