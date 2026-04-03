package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.maxX
import com.writer.model.minX
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
    /** Pending word edit from scratch-out on consolidated text (null if none). */
    val pendingWordEdit: PendingWordEdit?
    fun eagerRecognizeLine(lineIndex: Int)
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
    internal val host: DisplayManagerHost,
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
    internal val hersheyStrokeCache = object : LinkedHashMap<Pair<Int, String>, List<InkStroke>>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, String>, List<InkStroke>>?) = size > 20
    }
    /** Cache of word-wrap results keyed by (fullText, maxWidthUnits). */
    internal val wordWrapCache = object : LinkedHashMap<String, List<String>>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 20
    }

    // Precomputed Hershey metrics (lazy-initialized once)
    internal var hScale = 0f
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

    private fun getHersheyStrokes(lineIdx: Int, text: String, skipWordIndex: Int = -1): List<InkStroke> {
        val font = hersheyFont ?: return emptyList()
        val cacheKey = lineIdx to (text + ":skip$skipWordIndex")
        return hersheyStrokeCache.getOrPut(cacheKey) {
            val ls = HandwritingCanvasView.LINE_SPACING
            val tm = HandwritingCanvasView.TOP_MARGIN
            val ruledY = tm + (lineIdx + 1) * ls
            val startY = ruledY - hBaselineY * hScale
            if (skipWordIndex < 0) {
                font.textToStrokes(text, hMargin, startY, hScale, 0.5f)
            } else {
                // Generate strokes word-by-word, skipping the word at skipWordIndex
                val words = text.split(" ")
                val result = mutableListOf<InkStroke>()
                var cursorX = 0f
                for ((i, word) in words.withIndex()) {
                    val wordWidthUnits = font.measureWidth(word)
                    val spaceWidthUnits = font.measureWidth(" ")
                    if (i == skipWordIndex) {
                        // Skip rendering but advance cursor to preserve gap
                        cursorX += wordWidthUnits + spaceWidthUnits
                        continue
                    }
                    val wordStrokes = font.textToStrokes(
                        word, hMargin + cursorX * hScale, startY, hScale, 0.5f
                    )
                    result.addAll(wordStrokes)
                    cursorX += wordWidthUnits + spaceWidthUnits
                }
                result
            }
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
        // Track which lines have scrolled off (persisted for overlay state on reload)
        val scrollOff = inkCanvas.scrollOffsetY
        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN
        for ((lineIdx, _) in host.lineTextCache) {
            val lineBottom = tm + (lineIdx + 1) * ls
            if (lineBottom <= scrollOff) everHiddenLines.add(lineIdx)
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

        // Collect lines to consolidate — only lines strictly before currentLineIndex.
        // The current writing line stays unconsolidated so the user can see their strokes.
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

            // Build a map from global word index → (source line index, word count)
            val wordToSourceLine = mutableListOf<Int>()
            val paragraphSourceLines = mutableListOf<Int>()
            val paragraphSourceWordCounts = mutableListOf<Int>()
            val allWordConfidences = mutableListOf<com.writer.recognition.WordConfidence>()
            var wordOffset = 0
            for (entry in paragraph) {
                val entryWordCount = entry.value.split(" ").size
                paragraphSourceLines.add(entry.key)
                paragraphSourceWordCounts.add(entryWordCount)
                repeat(entryWordCount) { wordToSourceLine.add(entry.key) }
                val result = host.lineRecognitionResults[entry.key]
                if (result != null) {
                    for (wc in result.wordConfidences) {
                        allWordConfidences.add(wc.copy(wordIndex = wc.wordIndex + wordOffset))
                    }
                }
                wordOffset += entryWordCount
            }

            val pendingEdit = host.pendingWordEdit
            // Build a list of target line indices, skipping diagram lines.
            // When word-wrap produces more lines than the source paragraph,
            // overflow lines must not collide with diagram areas.
            val targetLineIndices = mutableListOf<Int>()
            var nextLine = startLineIdx
            for (i in wrappedLines.indices) {
                while (host.diagramManager.isDiagramLine(nextLine)) nextLine++
                targetLineIndices.add(nextLine)
                nextLine++
            }
            for (i in wrappedLines.indices) {
                val lineIdx = targetLineIndices[i]
                val wrappedText = wrappedLines[i]
                // Check if this wrapped line contains the pending edit word.
                // Use the scratch-out X position to disambiguate duplicate words.
                val lineStartWord = wrappedLines.take(i).sumOf { it.split(" ").size }
                val skipIdx = if (pendingEdit != null && hersheyFont != null) {
                    val words = wrappedText.split(" ")
                    var bestIdx = -1
                    var bestDist = Float.MAX_VALUE
                    var wx = hMargin
                    for ((wi, w) in words.withIndex()) {
                        val ww = hersheyFont!!.measureWidth(w) * hScale
                        val sw = hersheyFont!!.measureWidth(" ") * hScale
                        val wordCenterX = wx + ww / 2f
                        if (w == pendingEdit.oldWord) {
                            val dist = kotlin.math.abs(wordCenterX - (pendingEdit.wordStartX + pendingEdit.wordEndX) / 2f)
                            if (dist < bestDist) {
                                bestDist = dist
                                bestIdx = wi
                            }
                        }
                        wx += ww + sw
                    }
                    bestIdx
                } else -1
                // Only generate strokes for lines near the viewport
                val syntheticStrokes = if (lineIdx in minVisibleLine..maxVisibleLine) {
                    getHersheyStrokes(lineIdx, wrappedText, skipIdx)
                } else emptyList()
                // Scope word confidences to this wrapped line (reuse lineStartWord from above)
                val lineWordCount = wrappedText.split(" ").size
                val lineConfidences = allWordConfidences.filter { wc ->
                    wc.wordIndex >= lineStartWord && wc.wordIndex < lineStartWord + lineWordCount
                }.map { it.copy(wordIndex = it.wordIndex - lineStartWord) }

                Log.d(TAG, "buildOverlays: consolidated line $lineIdx text='${wrappedText.take(30)}' (wrapped from paragraph starting at ${paragraph.first().key})")
                overlays[lineIdx] = InlineTextState(
                    lineIndex = lineIdx,
                    recognizedText = wrappedText,
                    consolidated = true,
                    syntheticStrokes = syntheticStrokes,
                    wordConfidences = lineConfidences,
                    sourceLineIndices = paragraphSourceLines,
                    sourceWordCounts = paragraphSourceWordCounts,
                    paragraphWordOffset = lineStartWord
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
            if (lineIdx in overlays) {
                Log.d(TAG, "buildOverlays: skip non-consolidated at line $lineIdx (already has ${overlays[lineIdx]?.let { "consolidated=${it.consolidated} text='${it.recognizedText.take(20)}'" }})")
                continue
            }
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

        // Compute overflow shift: if consolidated text extends into the current
        // writing line, shift non-consolidated strokes down to avoid overlap.
        val maxConsolidatedLine = overlays.entries
            .filter { it.value.consolidated && !it.value.unConsolidated }
            .maxOfOrNull { it.key } ?: -1
        val prevShift = inkCanvas.consolidationOverflowShiftPx
        val newShift = if (currentLineIndex >= 0 && maxConsolidatedLine >= currentLineIndex) {
            (maxConsolidatedLine - currentLineIndex + 2) * HandwritingCanvasView.LINE_SPACING
        } else 0f
        inkCanvas.consolidationOverflowShiftPx = newShift
        // Compensate scroll so the current writing line stays at the same
        // screen position.  Screen Y = docY + overflowShift - scrollOffset,
        // so when overflowShift changes by delta, scrollOffset must change
        // by the same delta to keep screen Y constant.
        // The scroll change takes effect on the next drawToSurface() pass
        // (triggered by scheduleTextRefresh), avoiding a jarring mid-stroke jump.
        val shiftDelta = newShift - prevShift
        if (shiftDelta != 0f) {
            inkCanvas.scrollOffsetY = (inkCanvas.scrollOffsetY + shiftDelta).coerceAtLeast(0f)
        }

        lastOverlayHash = hash
        lastOverlayLine = currentLineIndex

        // Update the popup card — skip during scroll (pen not active = user is scrolling).
        // Window covers idle timeout (100ms) + recognition latency (~500ms).
        if (inkCanvas.isPenRecentlyActive(windowMs = 1000L)) {
        val currentText = host.lineTextCache[currentLineIndex]
        if (currentText != null && currentText.isNotBlank() && currentText != "[?]") {
            val lastWord = currentText.trim().split(" ").lastOrNull()
            if (lastWord != null && (currentText != lastPopupText || currentLineIndex != lastPopupLine)) {
                lastPopupText = currentText
                lastPopupLine = currentLineIndex
                // Find the left edge of the last word's strokes using N-1 largest
                // gaps (same algorithm as findStrokesForWord). The word count from
                // recognized text tells us how many gaps to find.
                val wordCount = currentText.trim().split(" ").size
                val lineStrokes = host.columnModel.activeStrokes.filter { stroke ->
                    val y = stroke.points[stroke.points.size / 2].y
                    lineSegmenter.getLineIndex(y) == currentLineIndex
                }.sortedBy { it.minX }
                var popupX = inkCanvas.width / 2f
                if (lineStrokes.size >= 2 && wordCount >= 2) {
                    // Collect all inter-stroke gaps, find N-1 largest
                    val gaps = (1 until lineStrokes.size).map { i ->
                        i to (lineStrokes[i].minX - lineStrokes[i - 1].maxX)
                    }.sortedByDescending { it.second }
                    val boundaryIndices = gaps.take(wordCount - 1).map { it.first }.sorted()
                    // Last word starts after the last boundary
                    val lastWordStart = boundaryIndices.lastOrNull() ?: 0
                    popupX = lineStrokes[lastWordStart].minX
                } else if (lineStrokes.isNotEmpty()) {
                    popupX = lineStrokes[0].minX
                }
                val lineTop = HandwritingCanvasView.TOP_MARGIN + currentLineIndex * HandwritingCanvasView.LINE_SPACING
                val screenY = lineTop + inkCanvas.consolidationOverflowShiftPx - inkCanvas.scrollOffsetY
                onWordPopup?.invoke(lastWord, popupX, screenY)
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
        // Extend past currentLineIndex to include word-wrap overflow lines
        // that are part of this paragraph's consolidated overlays.
        val maxOverlayLine = inkCanvas.inlineTextOverlays.keys.maxOrNull() ?: (host.currentLineIndex - 1)
        while (rangeEnd < maxOverlayLine) {
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
        // Pause/draw/resume to force e-ink refresh (Onyx SDK suppresses View rendering)
        inkCanvas.post {
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
        }
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
