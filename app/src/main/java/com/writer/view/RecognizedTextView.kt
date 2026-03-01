package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.writer.ui.writing.WritingCoordinator.TextSegment

/**
 * Displays recognized text as flowing word-wrapped paragraphs.
 * Text is bottom-aligned to match the feel of writing scrolling up into text.
 * Individual line segments within a paragraph can be dimmed independently
 * using colored spans.
 *
 * Includes a right-side gutter for resizing the text/canvas split.
 */
class RecognizedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val GUTTER_WIDTH = 144f
    }

    private val textPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 80f
        isAntiAlias = false // e-ink
    }

    private val dimmedColor = Color.parseColor("#AAAAAA")

    private val gutterPaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        style = Paint.Style.FILL
    }

    private val gutterLinePaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private var staticLayouts: List<StaticLayout> = emptyList()
    var totalTextHeight = 0
        private set
    /** Height of each paragraph (layout height + spacing), for scroll sync. */
    var paragraphHeights: List<Float> = emptyList()
        private set
    /** Per written line: (lineIndex, renderedTextHeight) for scroll sync. */
    var writtenLineHeights: List<Pair<Int, Float>> = emptyList()
        private set

    /** Pixel offset to shift text content downward (for scroll sync with canvas). */
    var textScrollOffset: Float = 0f

    /** Called when the user drags the gutter. Delta is positive = drag down. */
    var onGutterDrag: ((Float) -> Unit)? = null

    private val horizontalPadding = 40f
    private val paragraphSpacing = 24f
    private val bottomPadding = 10f

    // Gutter drag state
    private var isGutterDragging = false
    private var gutterDragLastY = 0f

    fun setParagraphs(paragraphs: List<List<TextSegment>>) {
        rebuildLayouts(paragraphs)
        invalidate()
    }

    private fun rebuildLayouts(paragraphs: List<List<TextSegment>>) {
        val availableWidth = (width - horizontalPadding - GUTTER_WIDTH).toInt()
        if (availableWidth <= 0) return

        var height = 0f
        val allWrittenLineHeights = mutableListOf<Pair<Int, Float>>()

        staticLayouts = paragraphs.map { segments ->
            val spannable = SpannableStringBuilder()
            val segmentStarts = mutableListOf<Int>()
            for ((i, segment) in segments.withIndex()) {
                if (i > 0) spannable.append(" ")
                val start = spannable.length
                segmentStarts.add(start)
                spannable.append(segment.text)
                if (segment.dimmed) {
                    spannable.setSpan(
                        ForegroundColorSpan(dimmedColor),
                        start, spannable.length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            val layout = StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8f, 1f)
                .build()

            // Attribute each rendered line to the segment whose text starts it.
            // This ensures a rendered line won't scroll until its first letter's
            // written line is dimmed.
            val segHeights = FloatArray(segments.size)
            for (rl in 0 until layout.lineCount) {
                val rlStartOffset = layout.getLineStart(rl)
                var owner = 0
                for (s in segmentStarts.indices) {
                    if (segmentStarts[s] <= rlStartOffset) owner = s
                    else break
                }
                val rlTop = layout.getLineTop(rl).toFloat()
                val rlBottom = if (rl < layout.lineCount - 1) {
                    layout.getLineTop(rl + 1).toFloat()
                } else {
                    layout.height.toFloat()
                }
                segHeights[owner] += rlBottom - rlTop
            }
            segHeights[segments.lastIndex] += paragraphSpacing

            for ((i, segment) in segments.withIndex()) {
                allWrittenLineHeights.add(Pair(segment.lineIndex, segHeights[i]))
            }

            height += layout.height + paragraphSpacing
            layout
        }
        paragraphHeights = staticLayouts.map { it.height.toFloat() + paragraphSpacing }
        writtenLineHeights = allWrittenLineHeights
        totalTextHeight = height.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Can't rebuild without paragraph data; next setParagraphs call will handle it
    }

    // --- Touch handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Reject finger/palm touches
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return false
        }

        // If already in a gutter drag, keep handling even if pen leaves gutter area
        if (isGutterDragging) {
            return handleGutterTouch(event)
        }

        // Stylus/mouse in gutter area → resize drag
        if (event.x >= width - GUTTER_WIDTH) {
            return handleGutterTouch(event)
        }

        return super.onTouchEvent(event)
    }

    private fun handleGutterTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isGutterDragging = true
                gutterDragLastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isGutterDragging) return false
                val dy = event.y - gutterDragLastY  // drag down = positive
                gutterDragLastY = event.y
                onGutterDrag?.invoke(dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isGutterDragging) return false
                isGutterDragging = false
                return true
            }
        }
        return false
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val gutterLeft = width - GUTTER_WIDTH

        // Draw text content
        if (staticLayouts.isNotEmpty()) {
            // Bottom-align, then shift down by scroll offset
            val baseY = (height - totalTextHeight - bottomPadding).coerceAtLeast(0f)
            val startY = baseY + textScrollOffset

            canvas.save()
            canvas.translate(horizontalPadding, startY)

            for (layout in staticLayouts) {
                layout.draw(canvas)
                canvas.translate(0f, layout.height + paragraphSpacing)
            }

            canvas.restore()
        }

        // Draw gutter (in screen space, on top of everything)
        canvas.drawRect(gutterLeft, 0f, width.toFloat(), height.toFloat(), gutterPaint)
        canvas.drawLine(gutterLeft, 0f, gutterLeft, height.toFloat(), gutterLinePaint)
    }
}
