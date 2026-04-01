package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.maxX
import com.writer.view.HandwritingCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.writer.recognition.LineSegmenter
import com.writer.view.ScreenMetrics

/** Callback interface for DisplayManager to communicate with its host. */
interface DisplayManagerHost {
    val columnModel: ColumnModel
    val diagramManager: DiagramManager
    val lineTextCache: Map<Int, String>
    val highestLineIndex: Int
    val currentLineIndex: Int
    /** Full recognition results with candidates, keyed by line index. */
    val lineRecognitionResults: Map<Int, com.writer.recognition.RecognitionResult>
    fun eagerRecognizeLine(lineIndex: Int)
    /** Batch-recognize a line, adding/removing from recognizingLines internally. */
    fun markRecognizing(lineIndex: Int)
    suspend fun doRecognizeLine(lineIndex: Int): String?
    fun isRecognizing(lineIndex: Int): Boolean
}

/** A segment of text within a paragraph, with its own dimming state. */
data class TextSegment(val text: String, val dimmed: Boolean, val lineIndex: Int, val listItem: Boolean = false, val heading: Boolean = false)

/** A text label to render inside a diagram at its original stroke position. */
data class DiagramTextLabel(val text: String, val x: Float, val y: Float)

/** Diagram display data for inline rendering in the text view. */
data class DiagramDisplay(
    val startLineIndex: Int,
    val strokes: List<InkStroke>,
    val canvasWidth: Float,
    val heightPx: Float,
    val offsetY: Float,
    /** How much of the diagram's height is scrolled off the canvas (for partial rendering). */
    val visibleHeightPx: Float = heightPx,
    /** Recognized text labels replacing freehand strokes in the diagram. */
    val textLabels: List<DiagramTextLabel> = emptyList()
)

class DisplayManager(
    private val inkCanvas: HandwritingCanvasView,
    private val scope: CoroutineScope,
    private val lineSegmenter: LineSegmenter,
    private val paragraphBuilder: ParagraphBuilder,
    private val host: DisplayManagerHost,
    private val hersheyFont: HersheyFont? = null
) {
    companion object {
        private const val TAG = "DisplayManager"
        // Delay before refreshing e-ink display after text view updates
        private const val TEXT_REFRESH_DELAY_MS = 500L
        // Minimum number of distinct candidates to consider low confidence
        private const val MIN_DISTINCT_CANDIDATES = 2
    }

    /** Lines that have ever scrolled above the viewport (text stays rendered once shown). */
    internal val everHiddenLines = mutableSetOf<Int>()

    fun clearEverHiddenLines() { everHiddenLines.clear() }
    fun addEverHiddenLines(lines: Set<Int>) { everHiddenLines.addAll(lines) }
    fun getEverHiddenLinesSnapshot(): Set<Int> = everHiddenLines.toSet()
    fun isEverHidden(lineIndex: Int): Boolean = lineIndex in everHiddenLines
    /** Called during scroll animation so the activity can sync linked columns. */
    var onScrollAnimated: (() -> Unit)? = null
    /** Called when a new recognized word should be shown in the popup. (word, screenX, screenY) */
    var onWordPopup: ((word: String, screenX: Float, screenY: Float) -> Unit)? = null

    /** Lines that are temporarily un-consolidated (user double-tapped to see originals). */
    private val unConsolidatedLines = mutableSetOf<Int>()

    /** Scroll animation state. */
    var scrollAnimating = false
        private set
    /** Deferred e-ink refresh for text view updates. */
    var textRefreshJob: Job? = null

    private var lastConsolidatedUpTo = -1
    /** Cached overlays — only rebuilt when lineTextCache changes or currentLineIndex advances. */
    private var cachedOverlays: Map<Int, InlineTextState> = emptyMap()
    private var cachedOverlayTextHash = 0
    private var cachedOverlayLineIndex = -1
    /** Cache of generated Hershey strokes keyed by (lineIndex, displayText).
     *  Limited to 20 entries to prevent unbounded memory growth. */
    private val hersheyStrokeCache = object : LinkedHashMap<Pair<Int, String>, List<InkStroke>>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, String>, List<InkStroke>>?) = size > 20
    }
    /** Cache of word-wrap results keyed by (fullText, maxWidthUnits). */
    private val wordWrapCache = object : LinkedHashMap<String, List<String>>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 20
    }

    // Precomputed Hershey metrics (lazy-initialized once)
    private var hScale = 0f
    private var hBaselineY = 9f
    private var hMargin = 0f
    private var hMaxWidthUnits = Float.MAX_VALUE

    private fun ensureHersheyMetrics() {
        if (hScale > 0f) return
        val ls = HandwritingCanvasView.LINE_SPACING
        hScale = ls * 0.8f / 21f
        hMargin = ScreenMetrics.dp(10f)
        val w = inkCanvas.width.toFloat()
        if (w > 0f) hMaxWidthUnits = (w - hMargin * 2) / hScale
    }

    private fun getHersheyStrokes(lineIdx: Int, text: String): List<InkStroke> {
        val font = hersheyFont ?: return emptyList()
        return hersheyStrokeCache.getOrPut(lineIdx to text) {
            val ls = HandwritingCanvasView.LINE_SPACING
            val tm = HandwritingCanvasView.TOP_MARGIN
            val ruledY = tm + (lineIdx + 1) * ls
            font.textToStrokes(text, hMargin, ruledY - hBaselineY * hScale, hScale, 0.5f)
        }
    }

    fun onIdle(currentLineIndex: Int) {
        if (currentLineIndex >= 0) {
            host.eagerRecognizeLine(currentLineIndex)
        }
    }

    fun scrollToLine(lineIndex: Int) {
        if (scrollAnimating) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        val lineY = lineSegmenter.getLineY(lineIndex)
        val targetOffset = inkCanvas.snapToLine(lineY).coerceAtLeast(0f)

        if (targetOffset != inkCanvas.scrollOffsetY) {
            Log.i(TAG, "Tap-to-scroll: line $lineIndex -> offset ${targetOffset.toInt()}")
            animateScroll(inkCanvas.scrollOffsetY, targetOffset, 500L)
        }
    }

    fun animateScroll(fromOffset: Float, toOffset: Float, duration: Long = 1000L) {
        scrollAnimating = true
        Log.i(TAG, "AutoScroll: animating from ${fromOffset.toInt()} to ${toOffset.toInt()}")
        inkCanvas.pauseRawDrawing()

        val distance = toOffset - fromOffset

        scope.launch {
            val startTime = System.currentTimeMillis()
            while (scrollAnimating) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= duration) break

                val t = elapsed.toFloat() / duration
                val interpolated = 1f - (1f - t) * (1f - t)
                inkCanvas.scrollOffsetY = fromOffset + distance * interpolated
                inkCanvas.drawToSurface()
                displayHiddenLines()
                onScrollAnimated?.invoke()

                kotlinx.coroutines.delay(33) // ~30fps
            }

            inkCanvas.scrollOffsetY = toOffset
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            scrollAnimating = false
            displayHiddenLines()
            onScrollAnimated?.invoke()
        }
    }

    /** Schedule a deferred e-ink refresh so inline overlay updates become visible.
     *  Cancelled if a new stroke arrives, so we never interrupt active writing.
     *  Skips pause/resume if pen is still active to avoid stroke disappearance. */
    fun scheduleTextRefresh() {
        textRefreshJob?.cancel()
        textRefreshJob = scope.launch {
            delay(TEXT_REFRESH_DELAY_MS)
            if (inkCanvas.isPenRecentlyActive()) return@launch
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
        }
    }

    fun displayHiddenLines() {
        // Track which lines have scrolled off for eager recognition
        val scrollOff = inkCanvas.scrollOffsetY
        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN
        for ((lineIdx, _) in host.lineTextCache) {
            val lineBottom = tm + (lineIdx + 1) * ls
            if (lineBottom <= scrollOff) everHiddenLines.add(lineIdx)
        }

        // Trigger recognition for hidden lines not yet cached
        val uncached = everHiddenLines.filter { !host.lineTextCache.containsKey(it) && !host.isRecognizing(it) }
        if (uncached.isNotEmpty()) {
            for (lineIdx in uncached) host.markRecognizing(lineIdx)
            scope.launch {
                for (lineIdx in uncached) host.doRecognizeLine(lineIdx)
            }
        }

        updateInlineOverlays(host.currentLineIndex)
    }

    // updateTextView removed — RecognizedTextView no longer exists.
    // Paragraph/diagram display data can be rebuilt for markdown export if needed.

    fun buildInlineOverlays(currentLineIndex: Int): Map<Int, InlineTextState> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val font = hersheyFont
        val overlays = mutableMapOf<Int, InlineTextState>()

        // Only process lines within the visible viewport + a small buffer
        val viewTop = inkCanvas.scrollOffsetY
        val viewBottom = viewTop + inkCanvas.height
        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN
        val bufferLines = 3  // extra lines above/below viewport
        val minVisibleLine = ((viewTop - tm) / ls - bufferLines).toInt().coerceAtLeast(0)
        // When canvas height is 0 (tests, pre-layout), process all lines
        val maxVisibleLine = if (inkCanvas.height > 0) ((viewBottom - tm) / ls + bufferLines).toInt() else Int.MAX_VALUE

        // Collect lines to consolidate — only within visible range
        val consolidateLines = ArrayList<Map.Entry<Int, String>>()
        if (font != null) ensureHersheyMetrics()
        run {
            for (entry in host.lineTextCache) {
                val lineIdx = entry.key
                val text = entry.value
                if (text.isBlank() || text == "[?]") continue
                if (host.diagramManager.isDiagramLine(lineIdx)) continue
                if (lineIdx >= currentLineIndex) continue
                consolidateLines.add(entry)
            }
            consolidateLines.sortBy { it.key }
        }  // end run block

        // Group consecutive lines into paragraphs (break on gaps or diagram lines)
        val paragraphs = ArrayList<ArrayList<Map.Entry<Int, String>>>()
        var currentGroup = ArrayList<Map.Entry<Int, String>>()
        for (entry in consolidateLines) {
            if (currentGroup.isNotEmpty()) {
                val prevIdx = currentGroup.last().key
                val gap = entry.key - prevIdx
                val hasDiagramBetween = (prevIdx + 1 until entry.key).any { host.diagramManager.isDiagramLine(it) }
                if (gap > 1 || hasDiagramBetween) {
                    paragraphs.add(currentGroup)
                    currentGroup = ArrayList()
                }
            }
            currentGroup.add(entry)
        }
        if (currentGroup.isNotEmpty()) paragraphs.add(currentGroup)

        // For each paragraph, concatenate text and word-wrap (or just mark consolidated if no font)
        for (paragraph in paragraphs) {
            if (font == null) {
                // No font — mark all lines consolidated with empty strokes
                for (entry in paragraph) {
                    overlays[entry.key] = InlineTextState(
                        lineIndex = entry.key, recognizedText = entry.value,
                        consolidated = true
                    )
                }
                continue
            }
            val fullText = paragraph.joinToString(" ") { it.value }
            val wrappedLines = wordWrapCache.getOrPut(fullText) {
                font.wordWrap(fullText, hMaxWidthUnits)
            }
            val startLineIdx = paragraph.first().key

            // Generate Hershey strokes only for visible wrapped lines
            for (i in wrappedLines.indices) {
                val lineIdx = startLineIdx + i
                val wrappedText = wrappedLines[i]
                // Only generate strokes for lines near the viewport
                val syntheticStrokes = if (lineIdx in minVisibleLine..maxVisibleLine) {
                    getHersheyStrokes(lineIdx, wrappedText)
                } else emptyList()
                overlays[lineIdx] = InlineTextState(
                    lineIndex = lineIdx,
                    recognizedText = wrappedText,
                    consolidated = true,
                    syntheticStrokes = syntheticStrokes
                )
            }

            // Mark remaining original lines as consolidated (hide strokes)
            for (entry in paragraph) {
                val lineIdx = entry.key
                if (lineIdx !in overlays) {
                    overlays[lineIdx] = InlineTextState(
                        lineIndex = lineIdx,
                        recognizedText = "",
                        consolidated = true
                    )
                }
            }
        }

        // Non-consolidated overlays for current writing line and other non-consolidated lines
        for ((lineIdx, text) in host.lineTextCache) {
            if (lineIdx in overlays) continue
            if (text.isBlank() || text == "[?]") continue
            if (host.diagramManager.isDiagramLine(lineIdx)) continue

            overlays[lineIdx] = InlineTextState(
                lineIndex = lineIdx,
                recognizedText = text,
                consolidated = false
            )
        }

        // Mark un-consolidated lines (user double-tapped to reveal originals)
        for (lineIdx in unConsolidatedLines) {
            val existing = overlays[lineIdx]
            if (existing != null && existing.consolidated) {
                overlays[lineIdx] = existing.copy(unConsolidated = true)
            }
        }

        val elapsed = android.os.SystemClock.elapsedRealtime() - t0
        if (elapsed > 10) Log.w(TAG, "buildInlineOverlays took ${elapsed}ms (${overlays.size} overlays, ${host.lineTextCache.size} cached lines)")
        return overlays
    }

    /** Track the last recognized text per line to detect any changes. */
    private var lastPopupText = ""
    private var lastPopupLine = -1

    /** Cache key for last overlay build to skip redundant rebuilds. */
    internal var lastOverlayHash = 0
    internal var lastOverlayLine = -1

    fun updateInlineOverlays(currentLineIndex: Int) {
        inkCanvas.currentWritingLineIndex = currentLineIndex

        // Skip everything if text cache hasn't changed
        val hash = host.lineTextCache.hashCode()
        if (hash == lastOverlayHash && currentLineIndex == lastOverlayLine) return

        val overlays = buildInlineOverlays(currentLineIndex)
        inkCanvas.updateInlineOverlays(overlays)
        lastOverlayHash = hash
        lastOverlayLine = currentLineIndex

        // Update the popup card — skip during scroll (pen not active = user is scrolling)
        if (inkCanvas.isPenRecentlyActive()) {
        val currentText = host.lineTextCache[currentLineIndex]
        if (currentText != null && currentText.isNotBlank() && currentText != "[?]") {
            val lastWord = currentText.trim().split(" ").lastOrNull()
            if (lastWord != null && (currentText != lastPopupText || currentLineIndex != lastPopupLine)) {
                lastPopupText = currentText
                lastPopupLine = currentLineIndex
                // Use rightmost stroke X for popup position
                var rightX = inkCanvas.width / 2f
                for (stroke in host.columnModel.activeStrokes) {
                    val y = stroke.points[stroke.points.size / 2].y
                    if (lineSegmenter.getLineIndex(y) == currentLineIndex) {
                        if (stroke.maxX > rightX) rightX = stroke.maxX
                    }
                }
                val lineTop = HandwritingCanvasView.TOP_MARGIN + currentLineIndex * HandwritingCanvasView.LINE_SPACING
                val screenY = lineTop - inkCanvas.scrollOffsetY
                onWordPopup?.invoke(lastWord, rightX, screenY)
            }
        }
        } // isPenRecentlyActive guard
    }

    /** Toggle un-consolidation for the entire paragraph containing [lineIndex].
     *  Finds all consecutive lines (consolidated or with text cache) around the
     *  tapped line and un-consolidates them all, including any reflowed lines. */
    fun toggleUnConsolidate(lineIndex: Int) {
        // Find the contiguous range of lines around the tapped line
        // that are part of the same paragraph (no gaps, no diagram lines)
        var rangeStart = lineIndex
        while (rangeStart > 0) {
            val prev = rangeStart - 1
            if (host.diagramManager.isDiagramLine(prev)) break
            if (!host.lineTextCache.containsKey(prev) && prev !in inkCanvas.inlineTextOverlays) break
            rangeStart = prev
        }
        var rangeEnd = lineIndex
        while (rangeEnd < host.currentLineIndex - 1) {
            val next = rangeEnd + 1
            if (host.diagramManager.isDiagramLine(next)) break
            if (!host.lineTextCache.containsKey(next) && next !in inkCanvas.inlineTextOverlays) break
            rangeEnd = next
        }

        val paragraphLines = (rangeStart..rangeEnd).toSet()

        if (paragraphLines.any { it in unConsolidatedLines }) {
            unConsolidatedLines.removeAll(paragraphLines)
        } else {
            unConsolidatedLines.addAll(paragraphLines)
        }
        // Force overlay rebuild
        lastOverlayHash = 0
        updateInlineOverlays(host.currentLineIndex)
        inkCanvas.drawToSurface()
    }

    /** Re-consolidate all un-consolidated lines (called when user moves to a different line). */
    fun reConsolidateAll() {
        if (unConsolidatedLines.isNotEmpty()) {
            unConsolidatedLines.clear()
            lastOverlayHash = 0
            updateInlineOverlays(host.currentLineIndex)
        }
    }

    fun stop() {
        scrollAnimating = false
        textRefreshJob?.cancel()
    }

    fun reset() {
        scrollAnimating = false
        textRefreshJob?.cancel()
        everHiddenLines.clear()
        lastPopupText = ""
        lastPopupLine = -1
        cachedOverlays = emptyMap()
        cachedOverlayTextHash = 0
        cachedOverlayLineIndex = -1
        lastOverlayHash = 0
        lastOverlayLine = -1
        lastPopupText = ""
        lastPopupLine = -1
        hersheyStrokeCache.clear()
        wordWrapCache.clear()
        hScale = 0f
        hMaxWidthUnits = Float.MAX_VALUE
    }
}
