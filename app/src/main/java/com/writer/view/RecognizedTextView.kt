package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.writer.model.InkStroke
import com.writer.ui.writing.WritingCoordinator.DiagramDisplay
import com.writer.ui.writing.WritingCoordinator.TextSegment

/**
 * Displays recognized text as flowing word-wrapped paragraphs.
 * Text is bottom-aligned to match the feel of writing scrolling up into text.
 * Individual line segments within a paragraph can be dimmed independently
 * using colored spans.
 *
 * Includes a floating "I" logo icon that auto-hides during writing.
 */
class RecognizedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private val HORIZONTAL_PADDING    get() = ScreenMetrics.dp(21f)
        private val PARAGRAPH_SPACING     get() = ScreenMetrics.dp(12f)
        private val LIST_ITEM_SPACING     get() = ScreenMetrics.dp(3f)
        private val FIRST_LINE_INDENT     get() = ScreenMetrics.dp(43f).toInt()
        private val LIST_BASE_INDENT      get() = ScreenMetrics.dp(32f).toInt()
        private val BULLET_HANG_INDENT    get() = ScreenMetrics.dp(54f).toInt()
        private const val BULLET_PREFIX   = "\u2022  "
        private val HEADING_SPACING_AFTER get() = ScreenMetrics.dp(6f)
    }

    private val textPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.textBody
        isAntiAlias = false // e-ink
    }

    private val dimmedColor = CanvasTheme.LINE_COLOR

    private val logoPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.textLogo
        typeface = Typeface.create("cursive", Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val statusPaint = TextPaint().apply {
        color = Color.parseColor("#555555")
        textSize = ScreenMetrics.textStatus
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val scrollHintPaint = TextPaint().apply {
        color = Color.parseColor("#999999")
        textSize = 42f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val statusSubtextPaint = TextPaint().apply {
        color = Color.parseColor("#666666")
        textSize = ScreenMetrics.textSubtext
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val closeButtonPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.textCloseBtn
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
        textSize = ScreenMetrics.textTutorial
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // Diagram rendering
    private val diagramStrokePaint = CanvasTheme.newStrokePaint()
    private val diagramPath = Path()

    // Render items: text paragraphs and inline diagrams
    private sealed class RenderItem {
        abstract val heightPx: Float
    }

    private data class TextRenderItem(
        val layout: StaticLayout,
        override val heightPx: Float,
        val segments: List<TextSegment>,
        val segmentStarts: List<Int>
    ) : RenderItem()

    private data class DiagramRenderItem(
        val strokes: List<InkStroke>,
        val scale: Float,
        val offsetY: Float,
        val fullHeightPx: Float,
        override val heightPx: Float,
        val lineIndex: Int
    ) : RenderItem()


    /** When true, show "Scroll to turn writing into text" hint. Cleared permanently once text appears. */
    var showScrollHint = true

    /** When true, show tutorial annotations and close button. */
    var tutorialMode = false

    /** Called when the "Close Tutorial" button is tapped. */
    var onCloseTutorialTap: (() -> Unit)? = null

    private val closeButtonHeight get() = ScreenMetrics.dp(60f)

    private var renderItems: List<RenderItem> = emptyList()
    var totalTextHeight = 0
        private set
    /** Height of each render item, for scroll sync. */
    var paragraphHeights: List<Float> = emptyList()
        private set
    /** Per written line: (lineIndex, renderedTextHeight) for scroll sync. */
    var writtenLineHeights: List<Pair<Int, Float>> = emptyList()
        private set

    /** Pixel offset to shift text content downward (for scroll sync with canvas). */
    var textScrollOffset: Float = 0f

    /** Pixel offset to scroll text content upward (for viewing earlier text). */
    var textContentScroll: Float = 0f

    /** Called when the user scrolls the text view. Delta is in pixels (positive = finger drag down). */
    var onScroll: ((Float) -> Unit)? = null

    /** Called when a scroll gesture ends (finger lifted). */
    var onScrollEnd: (() -> Unit)? = null

    /** Called when the user taps the "I" logo. */
    var onLogoTap: (() -> Unit)? = null

    /** Called when the user taps on a text segment. Passes the written lineIndex. */
    var onTextTap: ((Int) -> Unit)? = null

    /** Status message shown centered in the content area. */
    var statusMessage: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Subtext shown below the status message. */
    var statusSubtext: String = ""
        set(value) {
            field = value
            invalidate()
        }

    // Floating icon visibility (auto-hides during writing)
    private var iconVisible = true
    private val iconSize get() = ScreenMetrics.dp(56f)
    private val iconShowRunnable = Runnable {
        iconVisible = true
        invalidate()
    }

    // Text tap tracking
    private var textTapDownX = 0f
    private var textTapDownY = 0f
    private var textTapTracking = false

    /** Shared palm-rejection filter, set by WritingActivity. */
    var touchFilter: TouchFilter? = null

    // Finger scroll state
    private var fingerScrollActive = false
    private var fingerScrollLastY = 0f

    /** Called by WritingActivity when pen state changes on the canvas. */
    fun onPenStateChanged(active: Boolean) {
        handler.removeCallbacks(iconShowRunnable)
        if (active) {
            if (iconVisible) {
                iconVisible = false
                invalidate()
            }
        } else {
            handler.postDelayed(iconShowRunnable, 300L)
        }
    }

    fun setParagraphs(paragraphs: List<List<TextSegment>>) {
        setContent(paragraphs, emptyList())
    }

    fun setContent(paragraphs: List<List<TextSegment>>, diagrams: List<DiagramDisplay>) {
        rebuildRenderItems(paragraphs, diagrams)
        if (!tutorialMode && renderItems.isNotEmpty()) showScrollHint = false
        invalidate()
    }

    private fun rebuildRenderItems(paragraphs: List<List<TextSegment>>, diagrams: List<DiagramDisplay>) {
        val availableWidth = (width - 2 * HORIZONTAL_PADDING).toInt()
        if (availableWidth <= 0) return

        data class Indexed(val lineIndex: Int, val item: RenderItem, val lineHeights: List<Pair<Int, Float>>)

        // Build text render items
        val textItems = paragraphs.mapIndexed { pIdx, segments ->
            val isListItem = segments.firstOrNull()?.listItem == true
            val isHeading = segments.firstOrNull()?.heading == true
            val spannable = SpannableStringBuilder()
            val segmentStarts = mutableListOf<Int>()

            if (isListItem) {
                spannable.append(BULLET_PREFIX)
            }

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

            if (isHeading) {
                spannable.setSpan(
                    RelativeSizeSpan(1.3f),
                    0, spannable.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0, spannable.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (isListItem) {
                spannable.setSpan(
                    LeadingMarginSpan.Standard(LIST_BASE_INDENT, BULLET_HANG_INDENT),
                    0, spannable.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                spannable.setSpan(
                    LeadingMarginSpan.Standard(FIRST_LINE_INDENT, 0),
                    0, spannable.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val layout = StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8f, 1f)
                .build()

            val nextIsListItem = paragraphs.getOrNull(pIdx + 1)?.firstOrNull()?.listItem == true
            val spacing = when {
                isHeading -> HEADING_SPACING_AFTER
                isListItem && nextIsListItem -> LIST_ITEM_SPACING
                else -> PARAGRAPH_SPACING
            }

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
            segHeights[segments.lastIndex] += spacing

            val lineHeights = segments.mapIndexed { i, segment ->
                Pair(segment.lineIndex, segHeights[i])
            }

            val heightPx = layout.height.toFloat() + spacing

            Indexed(
                lineIndex = segments.first().lineIndex,
                item = TextRenderItem(layout, heightPx, segments, segmentStarts.toList()),
                lineHeights = lineHeights
            )
        }

        // Build diagram render items (full width, no text padding)
        val diagramItems = diagrams.map { diagram ->
            val metrics = PreviewLayoutCalculator.diagramRenderMetrics(
                visibleHeight = diagram.visibleHeightPx,
                fullHeight = diagram.heightPx,
                canvasWidth = diagram.canvasWidth,
                textViewWidth = width.toFloat(),
                paragraphSpacing = PARAGRAPH_SPACING
            )
            Indexed(
                lineIndex = diagram.startLineIndex,
                item = DiagramRenderItem(diagram.strokes, metrics.scale, diagram.offsetY, metrics.fullRenderedHeight, metrics.renderedHeight, diagram.startLineIndex),
                lineHeights = listOf(Pair(diagram.startLineIndex, metrics.renderedHeight))
            )
        }

        // Merge and sort by line index
        val allItems = (textItems + diagramItems).sortedBy { it.lineIndex }

        // Strip trailing spacing from the last item so content is flush with the divider
        val items = allItems.map { it.item }.toMutableList()
        if (items.isNotEmpty()) {
            val last = items.last()
            when (last) {
                is TextRenderItem -> {
                    val trimmedHeight = PreviewLayoutCalculator.trimLastItemHeight(
                        last.heightPx, last.heightPx, PARAGRAPH_SPACING,
                        isText = true, textLayoutHeight = last.layout.height.toFloat()
                    )
                    items[items.lastIndex] = last.copy(heightPx = trimmedHeight)
                }
                is DiagramRenderItem -> {
                    val trimmedHeight = PreviewLayoutCalculator.trimLastItemHeight(
                        last.heightPx, last.fullHeightPx, PARAGRAPH_SPACING,
                        isText = false
                    )
                    items[items.lastIndex] = last.copy(heightPx = trimmedHeight)
                }
            }
        }

        renderItems = items
        paragraphHeights = renderItems.map { it.heightPx }
        writtenLineHeights = allItems.flatMap { it.lineHeights }
        totalTextHeight = paragraphHeights.sum().toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Can't rebuild without paragraph data; next setParagraphs call will handle it
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(iconShowRunnable)
    }

    // --- Touch handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Finger touches: filter through palm rejection, allow taps and scroll
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return handleFingerTouch(event)
        }

        // Tutorial: close button tap at top of text area
        if (tutorialMode && event.y < closeButtonHeight) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                return true
            }
            if (event.action == MotionEvent.ACTION_UP) {
                onCloseTutorialTap?.invoke()
                return true
            }
        }

        // Floating icon tap detection
        if (iconVisible && event.action == MotionEvent.ACTION_DOWN && isInIconArea(event.x, event.y)) {
            return true
        }
        if (iconVisible && event.action == MotionEvent.ACTION_UP && isInIconArea(event.x, event.y)) {
            onLogoTap?.invoke()
            return true
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

    /**
     * Handle filtered finger touches on the text view.
     * Allows: logo tap, text tap, text body scroll.
     */
    private fun handleFingerTouch(event: MotionEvent): Boolean {
        val tf = touchFilter ?: return false
        val touchMinorDp = event.touchMinor / ScreenMetrics.density

        // If already in a finger scroll, keep handling
        if (fingerScrollActive) {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    if (tf.evaluateMove(
                            pointerCount = event.pointerCount,
                            touchMinorDp = touchMinorDp,
                            eventTime = event.eventTime,
                            x = event.x,
                            y = event.y,
                            checkStationary = false,
                        ) == TouchFilter.Decision.REJECT
                    ) {
                        fingerScrollActive = false
                        return false
                    }
                    val dy = event.y - fingerScrollLastY
                    fingerScrollLastY = event.y
                    onScroll?.invoke(dy)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fingerScrollActive = false
                    onScrollEnd?.invoke()
                    return true
                }
            }
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (tf.evaluateDown(
                        pointerCount = event.pointerCount,
                        touchMinorDp = touchMinorDp,
                        eventTime = event.eventTime,
                        x = event.x,
                        y = event.y,
                    ) == TouchFilter.Decision.REJECT
                ) {
                    return false
                }

                // Floating icon tap
                if (iconVisible && isInIconArea(event.x, event.y)) {
                    return true
                }

                // Tutorial close button
                if (tutorialMode && event.y < closeButtonHeight) {
                    return true
                }

                // Track for tap or scroll on text body
                if (!tutorialMode) {
                    textTapDownX = event.x
                    textTapDownY = event.y
                    textTapTracking = true
                    fingerScrollLastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tf.evaluateMove(
                        pointerCount = event.pointerCount,
                        touchMinorDp = touchMinorDp,
                        eventTime = event.eventTime,
                        x = event.x,
                        y = event.y,
                        checkStationary = false,
                    ) == TouchFilter.Decision.REJECT
                ) {
                    textTapTracking = false
                    fingerScrollActive = false
                    return false
                }
                if (textTapTracking) {
                    val dx = event.x - textTapDownX
                    val dy = event.y - textTapDownY
                    if (dx * dx + dy * dy > 400f) {
                        textTapTracking = false
                        // Transition to finger scroll
                        fingerScrollActive = true
                        fingerScrollLastY = event.y
                    }
                }
                if (fingerScrollActive) {
                    val dy = event.y - fingerScrollLastY
                    fingerScrollLastY = event.y
                    onScroll?.invoke(dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (iconVisible && isInIconArea(event.x, event.y)) {
                    onLogoTap?.invoke()
                    return true
                }
                if (tutorialMode && event.y < closeButtonHeight) {
                    onCloseTutorialTap?.invoke()
                    return true
                }
                if (textTapTracking) {
                    textTapTracking = false
                    resolveTextTap(event.x, event.y)
                    return true
                }
                fingerScrollActive = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                textTapTracking = false
                fingerScrollActive = false
                return true
            }
        }
        return false
    }

    /** Check if touch coordinates are within the floating icon tap target. */
    private fun isInIconArea(x: Float, y: Float): Boolean {
        return x >= width - iconSize && y <= iconSize
    }

    /** Resolve a tap at screen coordinates (x, y) to a written lineIndex. */
    private fun resolveTextTap(x: Float, y: Float) {
        if (renderItems.isEmpty()) return
        val callback = onTextTap ?: return

        val baseY = (height - totalTextHeight).toFloat()
        val startY = baseY + textScrollOffset + textContentScroll
        val localX = x - HORIZONTAL_PADDING

        var cumulativeY = startY
        for (item in renderItems) {
            val itemEnd = cumulativeY + item.heightPx

            if (y >= cumulativeY && y < itemEnd) {
                when (item) {
                    is TextRenderItem -> {
                        val localY = (y - cumulativeY).toInt()
                        val renderedLine = item.layout.getLineForVertical(localY)
                        val charOffset = item.layout.getOffsetForHorizontal(renderedLine, localX)

                        var ownerIdx = 0
                        for (s in item.segmentStarts.indices) {
                            if (item.segmentStarts[s] <= charOffset) ownerIdx = s
                            else break
                        }

                        val segment = item.segments.getOrNull(ownerIdx) ?: return
                        callback(segment.lineIndex)
                    }
                    is DiagramRenderItem -> {
                        callback(item.lineIndex)
                    }
                }
                return
            }

            cumulativeY = itemEnd
        }
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw text content or status/hint message
        if (statusMessage.isNotEmpty() && renderItems.isEmpty()) {
            // Draw status message centered in the content area
            val contentCenterX = width / 2f
            val contentCenterY = height / 2f
            canvas.drawText(statusMessage, contentCenterX, contentCenterY, statusPaint)
            if (statusSubtext.isNotEmpty()) {
                canvas.drawText(statusSubtext, contentCenterX, contentCenterY + 50f, statusSubtextPaint)
            }
        } else if (showScrollHint && renderItems.isEmpty() && !tutorialMode) {
            val contentCenterX = width / 2f
            val contentCenterY = height / 2f
            canvas.drawText("Scroll to turn writing into text", contentCenterX, contentCenterY, scrollHintPaint)
        } else if (renderItems.isNotEmpty()) {
            val baseY = if (tutorialMode) {
                closeButtonHeight + 5f  // top-align below close button
            } else {
                (height - totalTextHeight).toFloat()
            }
            val startY = baseY + textScrollOffset + textContentScroll

            canvas.save()
            canvas.translate(HORIZONTAL_PADDING, startY)

            for (item in renderItems) {
                when (item) {
                    is TextRenderItem -> item.layout.draw(canvas)
                    is DiagramRenderItem -> drawDiagramItem(canvas, item)
                }
                canvas.translate(0f, item.heightPx)
            }

            canvas.restore()
        }

        // Draw floating "I" icon in top-right corner
        if (iconVisible) {
            val iconCenterX = width - iconSize / 2f
            val logoY = iconSize * 0.7f
            canvas.drawText("I", iconCenterX, logoY, logoPaint)
        }

        if (tutorialMode) {
            // "Close Tutorial" button centered at top of text area
            val contentCenterX = width / 2f
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

            // Arrow pointing at floating "I" icon saying "Menu"
            val iconCenterX = width - iconSize / 2f
            val menuArrowY = iconSize * 0.5f
            val menuArrowRight = iconCenterX - iconSize / 2f - 10f
            val menuArrowLeft = menuArrowRight - 170f
            canvas.drawLine(menuArrowLeft, menuArrowY, menuArrowRight, menuArrowY, tutorialAnnotationPaint)
            canvas.drawLine(menuArrowRight - 20f, menuArrowY - 12f, menuArrowRight, menuArrowY, tutorialAnnotationPaint)
            canvas.drawLine(menuArrowRight - 20f, menuArrowY + 12f, menuArrowRight, menuArrowY, tutorialAnnotationPaint)
            canvas.drawText("Menu", menuArrowLeft - 110f, menuArrowY + 12f, tutorialTextPaint)

            // "Drag divider to resize" annotation at bottom
            val resizeY = height - 60f
            canvas.drawText("Drag divider to resize", width / 2f, resizeY, tutorialTextPaint)
        }
    }

    private fun drawDiagramItem(canvas: Canvas, item: DiagramRenderItem) {
        if (item.strokes.isEmpty()) return
        canvas.save()
        // Always clip diagram to its allocated height so strokes don't overflow into adjacent items
        canvas.clipRect(
            -HORIZONTAL_PADDING, 0f,
            width.toFloat(), item.heightPx
        )
        // Undo the HORIZONTAL_PADDING translation so diagram uses full page width
        canvas.translate(-HORIZONTAL_PADDING, 0f)
        canvas.scale(item.scale, item.scale)
        canvas.translate(0f, -item.offsetY)
        for (stroke in item.strokes) {
            CanvasTheme.drawStroke(canvas, stroke, diagramPath, diagramStrokePaint)
        }
        canvas.restore()
    }
}
