package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
 * Includes a right-side gutter with a "W" logo and resize drag handling.
 */
class RecognizedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val GUTTER_WIDTH = HandwritingCanvasView.GUTTER_WIDTH
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

    private val logoPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 96f
        typeface = Typeface.create("cursive", Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val statusPaint = TextPaint().apply {
        color = Color.parseColor("#666666")
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val closeButtonPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val closeButtonBorderPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val tutorialAnnotationPaint = Paint().apply {
        color = Color.rgb(50, 50, 200)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val tutorialTextPaint = TextPaint().apply {
        color = Color.rgb(50, 50, 200)
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val debugPaint = TextPaint().apply {
        color = Color.RED
        textSize = 32f
        isAntiAlias = true
    }

    /** When true, show tutorial annotations and close button. */
    var tutorialMode = false

    /** Called when the "Close Tutorial" button is tapped. */
    var onCloseTutorialTap: (() -> Unit)? = null

    private val closeButtonHeight = 110f

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

    /** Called when the user taps the "W" logo. */
    var onLogoTap: (() -> Unit)? = null

    /** Called when the user taps on a text segment. Passes the written lineIndex. */
    var onTextTap: ((Int) -> Unit)? = null

    /** Status message shown in the gutter below the logo (e.g. "Loading model..."). */
    var statusMessage: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val horizontalPadding = 40f
    private val paragraphSpacing = 24f
    private val bottomPadding = 10f

    // Gutter drag state
    private var isGutterDragging = false
    private var gutterDragLastY = 0f
    private var gutterDragStartY = 0f
    private var gutterDragMoved = false

    // Text tap tracking
    private var textTapDownX = 0f
    private var textTapDownY = 0f
    private var textTapTracking = false

    // Hit-test data (stored on each setParagraphs call)
    private var currentParagraphs: List<List<TextSegment>> = emptyList()
    private var paragraphSegmentStarts: List<List<Int>> = emptyList()

    // Debug: last tapped line info
    private var debugTapInfo: String = ""

    fun setParagraphs(paragraphs: List<List<TextSegment>>) {
        currentParagraphs = paragraphs
        rebuildLayouts(paragraphs)
        invalidate()
    }

    private fun rebuildLayouts(paragraphs: List<List<TextSegment>>) {
        val availableWidth = (width - horizontalPadding - GUTTER_WIDTH).toInt()
        if (availableWidth <= 0) return

        var height = 0f
        val allWrittenLineHeights = mutableListOf<Pair<Int, Float>>()
        val allSegmentStarts = mutableListOf<List<Int>>()

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

            allSegmentStarts.add(segmentStarts.toList())
            height += layout.height + paragraphSpacing
            layout
        }
        paragraphSegmentStarts = allSegmentStarts
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

        // Tutorial: close button tap at top of text area
        if (tutorialMode && event.x < width - GUTTER_WIDTH && event.y < closeButtonHeight) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                return true
            }
            if (event.action == MotionEvent.ACTION_UP) {
                onCloseTutorialTap?.invoke()
                return true
            }
        }

        // Stylus/mouse in gutter area → resize drag
        if (event.x >= width - GUTTER_WIDTH) {
            return handleGutterTouch(event)
        }

        // Stylus/mouse in text area → detect taps to scroll canvas to that line
        if (!tutorialMode) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    textTapDownX = event.x
                    textTapDownY = event.y
                    textTapTracking = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (textTapTracking) {
                        val dx = event.x - textTapDownX
                        val dy = event.y - textTapDownY
                        if (dx * dx + dy * dy > 400f) {
                            textTapTracking = false
                        }
                    }
                    return textTapTracking
                }
                MotionEvent.ACTION_UP -> {
                    if (textTapTracking) {
                        textTapTracking = false
                        resolveTextTap(event.x, event.y)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    textTapTracking = false
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handleGutterTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isGutterDragging = true
                gutterDragLastY = event.y
                gutterDragStartY = event.y
                gutterDragMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isGutterDragging) return false
                val dy = event.y - gutterDragLastY  // drag down = positive
                gutterDragLastY = event.y
                if (Math.abs(event.y - gutterDragStartY) > 20f) gutterDragMoved = true
                onGutterDrag?.invoke(dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isGutterDragging) return false
                isGutterDragging = false
                // Detect tap on the logo area (top of gutter, no significant drag)
                if (!gutterDragMoved && gutterDragStartY < GUTTER_WIDTH * 1.2f) {
                    onLogoTap?.invoke()
                }
                return true
            }
        }
        return false
    }

    /** Resolve a tap at screen coordinates (x, y) to a written lineIndex. */
    private fun resolveTextTap(x: Float, y: Float) {
        if (staticLayouts.isEmpty() || currentParagraphs.isEmpty()) return
        val callback = onTextTap ?: return

        val baseY = height - totalTextHeight - bottomPadding
        val startY = baseY + textScrollOffset
        val localX = x - horizontalPadding

        var cumulativeY = startY
        for (pIdx in staticLayouts.indices) {
            val layout = staticLayouts[pIdx]
            val pEnd = cumulativeY + layout.height + paragraphSpacing

            if (y >= cumulativeY && y < pEnd) {
                val localY = (y - cumulativeY).toInt()
                val renderedLine = layout.getLineForVertical(localY)
                val charOffset = layout.getOffsetForHorizontal(renderedLine, localX)

                val segStarts = paragraphSegmentStarts.getOrNull(pIdx) ?: return
                val segments = currentParagraphs.getOrNull(pIdx) ?: return

                var ownerIdx = 0
                for (s in segStarts.indices) {
                    if (segStarts[s] <= charOffset) ownerIdx = s
                    else break
                }

                val segment = segments.getOrNull(ownerIdx) ?: return
                debugTapInfo = "Tapped line ${segment.lineIndex} (seg=$ownerIdx \"${segment.text.take(20)}\")"
                invalidate()
                callback(segment.lineIndex)
                return
            }

            cumulativeY = pEnd
        }
        debugTapInfo = "Tap miss (y=${"%.0f".format(y)}, startY=${"%.0f".format(startY)})"
        invalidate()
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val gutterLeft = width - GUTTER_WIDTH
        val gutterCenterX = gutterLeft + GUTTER_WIDTH / 2f

        // Draw text content
        if (staticLayouts.isNotEmpty()) {
            val baseY = height - totalTextHeight - bottomPadding
            val startY = baseY + textScrollOffset

            canvas.save()
            canvas.translate(horizontalPadding, startY)

            for (layout in staticLayouts) {
                layout.draw(canvas)
                canvas.translate(0f, layout.height + paragraphSpacing)
            }

            canvas.restore()
        }

        // Draw gutter background
        canvas.drawRect(gutterLeft, 0f, width.toFloat(), height.toFloat(), gutterPaint)
        canvas.drawLine(gutterLeft, 0f, gutterLeft, height.toFloat(), gutterLinePaint)

        // Draw "W" logo in the top of the gutter
        val logoY = GUTTER_WIDTH * 0.7f
        canvas.drawText("W", gutterCenterX, logoY, logoPaint)

        // Draw status message below logo if present
        if (statusMessage.isNotEmpty()) {
            canvas.drawText(statusMessage, gutterCenterX, GUTTER_WIDTH + 32f, statusPaint)
        }

        // Debug: show last tapped line info
        if (debugTapInfo.isNotEmpty()) {
            canvas.drawText(debugTapInfo, 10f, 36f, debugPaint)
        }

        if (tutorialMode) {
            // "Close Tutorial" button centered at top of text area
            val contentCenterX = (width - GUTTER_WIDTH) / 2f
            val btnTextY = 52f
            canvas.drawText("Close Tutorial", contentCenterX, btnTextY, closeButtonPaint)
            val btnTextWidth = closeButtonPaint.measureText("Close Tutorial")
            val padH = 24f
            val padTop = 20f
            val padBottom = 18f
            canvas.drawRect(
                contentCenterX - btnTextWidth / 2f - padH,
                btnTextY - 40f - padTop,
                contentCenterX + btnTextWidth / 2f + padH,
                btnTextY + 12f + padBottom,
                closeButtonBorderPaint
            )

            // Arrow pointing at "W" logo saying "Menu"
            val menuArrowY = logoY - 20f
            val menuArrowRight = gutterLeft - 10f
            val menuArrowLeft = gutterLeft - 180f
            canvas.drawLine(menuArrowLeft, menuArrowY, menuArrowRight, menuArrowY, tutorialAnnotationPaint)
            canvas.drawLine(menuArrowRight - 20f, menuArrowY - 12f, menuArrowRight, menuArrowY, tutorialAnnotationPaint)
            canvas.drawLine(menuArrowRight - 20f, menuArrowY + 12f, menuArrowRight, menuArrowY, tutorialAnnotationPaint)
            canvas.drawText("Menu", menuArrowLeft - 110f, menuArrowY + 12f, tutorialTextPaint)

            // "Drag gutter to resize" with arrow pointing right toward gutter
            val resizeY = height - 60f
            val resizeLeft = gutterLeft - 350f
            val resizeRight = gutterLeft - 20f
            canvas.drawLine(resizeLeft, resizeY, resizeRight, resizeY, tutorialAnnotationPaint)
            canvas.drawLine(resizeRight - 20f, resizeY - 12f, resizeRight, resizeY, tutorialAnnotationPaint)
            canvas.drawLine(resizeRight - 20f, resizeY + 12f, resizeRight, resizeY, tutorialAnnotationPaint)
            canvas.drawText("Drag gutter to resize", resizeLeft, resizeY - 16f, tutorialTextPaint)
        }
    }
}
