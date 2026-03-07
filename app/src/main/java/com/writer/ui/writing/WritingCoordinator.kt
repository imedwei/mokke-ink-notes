package com.writer.ui.writing

import android.util.Log
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.shiftY
import com.writer.recognition.HandwritingRecognizer
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.model.DocumentData
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WritingCoordinator(
    private val documentModel: DocumentModel,
    private val recognizer: HandwritingRecognizer,
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "WritingCoordinator"
        // Scroll when writing passes this fraction of canvas height from top
        // 25% of canvas ≈ 50% of full screen (since canvas is 75% of screen)
        private const val SCROLL_THRESHOLD = 0.25f
        private fun gutterWidth(viewWidth: Int) = HandwritingCanvasView.gutterWidth(viewWidth)
        // Delay before refreshing e-ink display after text view updates
        private const val TEXT_REFRESH_DELAY_MS = 500L
    }

    private val lineSegmenter = LineSegmenter()
    private val strokeClassifier = StrokeClassifier(lineSegmenter)
    private val paragraphBuilder = ParagraphBuilder(strokeClassifier)
    private val undoManager = UndoManager()

    private val gestureHandler = GestureHandler(
        documentModel = documentModel,
        inkCanvas = inkCanvas,
        lineSegmenter = lineSegmenter,
        onLinesChanged = { invalidatedLines ->
            for (line in invalidatedLines) {
                lineTextCache.remove(line)
            }
            displayHiddenLines()
        },
        onBeforeMutation = { saveUndoSnapshot() }
    )

    // Eager recognition: cache of recognized text per line index.
    // All shared mutable state below is accessed only from Dispatchers.Main
    // (scope is lifecycleScope; only recognizer.recognizeLine runs on IO).
    private val lineTextCache = mutableMapOf<Int, String>()
    // Track which lines are currently being recognized (avoid duplicates)
    private val recognizingLines = mutableSetOf<Int>()
    // Lines that need re-recognition after current recognition finishes
    private val pendingRerecognize = mutableSetOf<Int>()
    // Track the highest (bottommost) line the user has written on
    private var highestLineIndex = -1
    // Lines that have ever scrolled above the viewport (text stays rendered once shown)
    private val everHiddenLines = mutableSetOf<Int>()
    // Auto-scroll animation
    private var scrollAnimating = false
    // Track which line the user is currently writing on
    private var currentLineIndex = -1
    // Whether the user has manually renamed this document
    var userRenamed = false
    // Callback to notify activity when heading-based rename should happen
    var onHeadingDetected: ((String) -> Unit)? = null
    // Deferred e-ink refresh for text view updates (avoids interrupting active writing)
    private var textRefreshJob: Job? = null

    // Line-drag gesture state
    private var lineDragAnchorLine = -1
    private var lineDragOriginalStrokes: List<InkStroke>? = null
    private var lineDragCurrentShift = 0

    fun start() {
        Log.i(TAG, "Coordinator started")
        inkCanvas.onStrokeCompleted = { stroke -> onStrokeCompleted(stroke) }
        inkCanvas.onIdleTimeout = { onIdle() }
        inkCanvas.onManualScroll = {
            // Clamp text overscroll so text doesn't scroll completely out of view
            val maxOverscroll = (textView.totalTextHeight - textView.height).coerceAtLeast(0).toFloat()
            if (inkCanvas.textOverscroll > maxOverscroll) {
                inkCanvas.textOverscroll = maxOverscroll
            }
            displayHiddenLines()
        }
        textView.onTextTap = { lineIndex -> scrollToLine(lineIndex) }
        inkCanvas.onLineDragStart = { anchorLine -> onLineDragStart(anchorLine) }
        inkCanvas.onLineDragStep = { shiftLines -> onLineDragStep(shiftLines) }
        inkCanvas.onLineDragEnd = { onLineDragEnd() }
        inkCanvas.onUndoGestureStart = {
            undoManager.beginScrub(currentSnapshot())
        }
        inkCanvas.onUndoGestureStep = step@{ offset ->
            val snapshot = undoManager.scrubTo(offset) ?: return@step
            applySnapshot(snapshot)
        }
        inkCanvas.onUndoGestureEnd = {
            undoManager.endScrub()
        }
    }

    fun stop() {
        scrollAnimating = false
        textRefreshJob?.cancel()
        inkCanvas.onStrokeCompleted = null
        inkCanvas.onIdleTimeout = null
        inkCanvas.onManualScroll = null
        textView.onTextTap = null
        inkCanvas.onLineDragStart = null
        inkCanvas.onLineDragStep = null
        inkCanvas.onLineDragEnd = null
        inkCanvas.onUndoGestureStart = null
        inkCanvas.onUndoGestureStep = null
        inkCanvas.onUndoGestureEnd = null
    }

    fun reset() {
        scrollAnimating = false
        textRefreshJob?.cancel()
        lineTextCache.clear()
        recognizingLines.clear()
        pendingRerecognize.clear()
        everHiddenLines.clear()
        highestLineIndex = -1
        currentLineIndex = -1
        userRenamed = false
    }

    private fun onStrokeCompleted(stroke: InkStroke) {
        textRefreshJob?.cancel()
        if (gestureHandler.tryHandle(stroke)) return

        saveUndoSnapshot()
        documentModel.activeStrokes.add(stroke)

        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

        // If the user modified a previously recognized line, invalidate cache
        lineTextCache.remove(lineIdx)

        // Only trigger recognition when the user moves to a DIFFERENT line
        if (lineIdx != currentLineIndex) {
            if (currentLineIndex >= 0) {
                eagerRecognizeLine(currentLineIndex)
            }
            currentLineIndex = lineIdx
        }

        // If editing a line that has rendered text, re-recognize immediately
        if (lineIdx in everHiddenLines) {
            Log.i(TAG, "Stroke on rendered line $lineIdx, triggering re-recognition")
            recognizeRenderedLine(lineIdx)
        } else {
            Log.d(TAG, "Stroke on line $lineIdx (not in everHiddenLines=$everHiddenLines)")
        }

        if (lineIdx > highestLineIndex) {
            highestLineIndex = lineIdx
        }
    }

    // --- Recognition ---

    /** Recognize all lines that have strokes but no cached text or failed recognition. */
    fun recognizeAllLines() {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        if (strokesByLine.isEmpty()) return
        scope.launch {
            for (lineIndex in strokesByLine.keys.sorted()) {
                // Re-recognize lines that failed ("[?]") or were never cached
                val cached = lineTextCache[lineIndex]
                if (cached != null && cached != "[?]") continue
                if (recognizingLines.contains(lineIndex)) continue
                recognizingLines.add(lineIndex)
                lineTextCache.remove(lineIndex)
                val text = doRecognizeLine(lineIndex)
                if (text != null) {
                    Log.d(TAG, "Post-load recognized line $lineIndex: \"$text\"")
                    displayHiddenLines()
                }
            }
        }
    }

    private fun eagerRecognizeLine(lineIndex: Int) {
        if (lineTextCache.containsKey(lineIndex)) return
        if (recognizingLines.contains(lineIndex)) return
        recognizingLines.add(lineIndex)

        scope.launch {
            val text = doRecognizeLine(lineIndex) ?: return@launch
            Log.d(TAG, "Eager recognized line $lineIndex: \"$text\"")
            if (lineIndex in everHiddenLines) {
                displayHiddenLines()
            }
        }
    }

    private fun recognizeRenderedLine(lineIndex: Int) {
        if (recognizingLines.contains(lineIndex)) {
            pendingRerecognize.add(lineIndex)
            return
        }
        recognizingLines.add(lineIndex)
        lineTextCache.remove(lineIndex)

        scope.launch {
            val text = doRecognizeLine(lineIndex) ?: return@launch
            Log.d(TAG, "Rendered line recognized $lineIndex: \"$text\"")
            displayHiddenLines()
            scheduleTextRefresh()
            if (lineIndex in pendingRerecognize) {
                pendingRerecognize.remove(lineIndex)
                recognizeRenderedLine(lineIndex)
            }
        }
    }

    /**
     * Core recognition: get strokes for [lineIndex], filter markers, recognize,
     * and cache the result. Returns the recognized text, or null if there were
     * no strokes or recognition failed.
     */
    private suspend fun doRecognizeLine(lineIndex: Int): String? {
        try {
            val allStrokes = lineSegmenter.getStrokesForLine(
                documentModel.activeStrokes, lineIndex
            )
            if (allStrokes.isEmpty()) {
                recognizingLines.remove(lineIndex)
                return null
            }
            val strokes = strokeClassifier.filterMarkerStrokes(allStrokes, inkCanvas.width - gutterWidth(inkCanvas.width))
            if (strokes.isEmpty()) {
                recognizingLines.remove(lineIndex)
                return null
            }

            val line = lineSegmenter.buildInkLine(strokes, lineIndex)
            val preContext = buildPreContext(lineIndex)
            val text = withContext(Dispatchers.IO) {
                recognizer.recognizeLine(line, preContext)
            }.trim()

            lineTextCache[lineIndex] = text
            recognizingLines.remove(lineIndex)
            checkHeadingRename(lineIndex, text, allStrokes)
            return text
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed for line $lineIndex", e)
            lineTextCache[lineIndex] = "[?]"
            recognizingLines.remove(lineIndex)
            pendingRerecognize.remove(lineIndex)
            return null
        }
    }

    private fun checkHeadingRename(lineIndex: Int, text: String, strokes: List<InkStroke>) {
        if (userRenamed) return
        if (lineIndex != 0) return
        if (text.isEmpty() || text == "[?]") return
        val isHeading = strokeClassifier.findUnderlineStrokeId(strokes, lineIndex) != null
        if (!isHeading) return
        onHeadingDetected?.invoke(text)
    }

    private fun buildPreContext(lineIndex: Int): String {
        val previousLines = lineTextCache.keys.filter { it < lineIndex }.sorted()
        if (previousLines.isEmpty()) return ""
        val lastText = previousLines.map { lineTextCache[it] ?: "" }
            .filter { it.isNotEmpty() && it != "[?]" }
            .joinToString(" ")
        return lastText.takeLast(20)
    }

    // --- Auto-scroll ---

    private fun onIdle() {
        if (currentLineIndex >= 0) {
            eagerRecognizeLine(currentLineIndex)
        }
        checkAutoScroll()
    }

    private fun checkAutoScroll() {
        if (scrollAnimating) return

        val strokes = documentModel.activeStrokes
        if (strokes.isEmpty()) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        val bottomLine = lineSegmenter.getBottomOccupiedLine(strokes)
        if (bottomLine < 0) return

        if (currentLineIndex != bottomLine) return

        val bottomLineDocY = lineSegmenter.getLineY(bottomLine) + HandwritingCanvasView.LINE_SPACING
        val bottomLineScreenY = bottomLineDocY - inkCanvas.scrollOffsetY
        val targetY = canvasHeight * SCROLL_THRESHOLD

        if (bottomLineScreenY < 0 || bottomLineScreenY > canvasHeight) return
        if (bottomLineScreenY < targetY) return

        val rawOffset = bottomLineDocY - targetY
        val newOffset = inkCanvas.snapToLine(rawOffset)
        if (newOffset > inkCanvas.scrollOffsetY) {
            animateScroll(inkCanvas.scrollOffsetY, newOffset)
        }
    }

    private fun scrollToLine(lineIndex: Int) {
        if (scrollAnimating) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        val lineY = lineSegmenter.getLineY(lineIndex)
        val targetOffset = inkCanvas.snapToLine(lineY).coerceAtLeast(0f)

        if (targetOffset != inkCanvas.scrollOffsetY) {
            Log.i(TAG, "Tap-to-scroll: line $lineIndex → offset ${targetOffset.toInt()}")
            animateScroll(inkCanvas.scrollOffsetY, targetOffset, 500L)
        }
    }

    private fun animateScroll(fromOffset: Float, toOffset: Float, duration: Long = 1000L) {
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

                kotlinx.coroutines.delay(33) // ~30fps
            }

            inkCanvas.scrollOffsetY = toOffset
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            scrollAnimating = false
            displayHiddenLines()
        }
    }

    /** Schedule a deferred e-ink refresh so text view updates become visible.
     *  Cancelled if a new stroke arrives, so we never interrupt active writing. */
    private fun scheduleTextRefresh() {
        textRefreshJob?.cancel()
        textRefreshJob = scope.launch {
            delay(TEXT_REFRESH_DELAY_MS)
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
        }
    }

    // --- Text display sync ---

    private fun displayHiddenLines() {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)

        val currentlyHidden = strokesByLine.keys.filter { lineIdx ->
            val lineBottom = lineSegmenter.getLineY(lineIdx) + HandwritingCanvasView.LINE_SPACING
            lineBottom <= inkCanvas.scrollOffsetY
        }.toSet()

        val notYetVisible = strokesByLine.keys.filter { lineIdx ->
            val lineMid = lineSegmenter.getLineY(lineIdx) + HandwritingCanvasView.LINE_SPACING / 2f
            lineMid <= inkCanvas.scrollOffsetY
        }.toSet()

        everHiddenLines.addAll(currentlyHidden)
        everHiddenLines.retainAll(strokesByLine.keys)

        updateTextView(notYetVisible)
        updateTextScrollOffset()

        val uncached = everHiddenLines.filter { !lineTextCache.containsKey(it) }
        if (uncached.isNotEmpty()) {
            scope.launch {
                for (lineIdx in uncached) {
                    if (lineTextCache.containsKey(lineIdx)) continue
                    try {
                        val allStrokes = strokesByLine[lineIdx] ?: continue
                        val strokes = strokeClassifier.filterMarkerStrokes(allStrokes, inkCanvas.width - gutterWidth(inkCanvas.width))
                        if (strokes.isEmpty()) continue
                        val line = lineSegmenter.buildInkLine(strokes, lineIdx)
                        val preContext = buildPreContext(lineIdx)
                        val text = withContext(Dispatchers.IO) {
                            recognizer.recognizeLine(line, preContext)
                        }
                        lineTextCache[lineIdx] = text.trim()
                        Log.d(TAG, "On-scroll recognized line $lineIdx: \"${text.trim()}\"")
                    } catch (e: Exception) {
                        Log.e(TAG, "Recognition failed for line $lineIdx", e)
                        lineTextCache[lineIdx] = "[?]"
                    }
                }
                val stillNotVisible = strokesByLine.keys.filter { lineIdx ->
                    val lineMid = lineSegmenter.getLineY(lineIdx) + HandwritingCanvasView.LINE_SPACING / 2f
                    lineMid <= inkCanvas.scrollOffsetY
                }.toSet()
                updateTextView(stillNotVisible)
            }
        }
    }

    /** A segment of text within a paragraph, with its own dimming state. */
    data class TextSegment(val text: String, val dimmed: Boolean, val lineIndex: Int, val listItem: Boolean = false, val heading: Boolean = false)

    private fun updateTextView(currentlyHidden: Set<Int>) {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width - gutterWidth(inkCanvas.width)

        val classifiedLines = everHiddenLines.sorted().mapNotNull { lineIdx ->
            paragraphBuilder.classifyLine(lineIdx, lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth)
        }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth)

        val paragraphs = grouped.map { group ->
            group.map { line ->
                TextSegment(
                    text = line.text,
                    dimmed = line.lineIndex !in currentlyHidden,
                    lineIndex = line.lineIndex,
                    listItem = line.isList,
                    heading = line.isHeading
                )
            }
        }

        textView.setParagraphs(paragraphs)
    }

    /** Called when the text view is scrolled via its gutter overscroll. */
    fun onManualTextScroll() {
        textView.textContentScroll = inkCanvas.textOverscroll
        textView.invalidate()
    }

    private fun updateTextScrollOffset() {
        val lineHeights = textView.writtenLineHeights
        if (lineHeights.isEmpty()) {
            textView.textScrollOffset = 0f
            return
        }

        var offset = 0f

        for (i in lineHeights.indices.reversed()) {
            val (lineIdx, textHeight) = lineHeights[i]

            if (textHeight <= 0f) continue

            val lineTop = lineSegmenter.getLineY(lineIdx)
            if (lineTop < inkCanvas.scrollOffsetY) {
                break
            }

            val drivingLine = lineIdx - 1
            if (drivingLine < 0) {
                offset += textHeight
                continue
            }

            val drivingLineBottom = lineSegmenter.getLineY(drivingLine) + HandwritingCanvasView.LINE_SPACING
            val fraction = ((drivingLineBottom - inkCanvas.scrollOffsetY) / HandwritingCanvasView.LINE_SPACING)
                .coerceIn(0f, 1f)

            if (fraction >= 1f) {
                offset += textHeight
                continue
            }

            offset += fraction * textHeight
            break
        }

        textView.textScrollOffset = offset
    }

    // --- Markdown export ---

    fun getMarkdownText(): String {
        if (lineTextCache.isEmpty()) return ""

        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width - gutterWidth(inkCanvas.width)

        val classifiedLines = lineTextCache.keys.sorted().mapNotNull { lineIdx ->
            paragraphBuilder.classifyLine(lineIdx, lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth)
        }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth)

        return grouped.joinToString("\n\n") { group ->
            val joined = group.joinToString(" ") { it.text }
            val first = group.first()
            val prefix = if (first.isHeading) "## " else if (first.isList) "- " else ""
            "$prefix$joined"
        }
    }

    // --- Line-drag gesture ---

    private fun onLineDragStart(anchorLine: Int) {
        saveUndoSnapshot()
        lineDragAnchorLine = anchorLine
        lineDragOriginalStrokes = documentModel.activeStrokes.toList()
        lineDragCurrentShift = 0
        Log.i(TAG, "Line-drag started: anchorLine=$anchorLine")
    }

    private fun onLineDragStep(shiftLines: Int) {
        val originals = lineDragOriginalStrokes ?: return
        val clamped = shiftLines.coerceAtLeast(-lineDragAnchorLine)
        if (clamped == lineDragCurrentShift) return
        lineDragCurrentShift = clamped

        val shiftAmount = clamped * HandwritingCanvasView.LINE_SPACING
        val newStrokes = mutableListOf<InkStroke>()

        for (stroke in originals) {
            val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
            if (strokeLine >= lineDragAnchorLine) {
                // Anchor line and below: shift
                newStrokes.add(stroke.shiftY(shiftAmount))
            } else if (clamped < 0) {
                // Upward drag: check if this stroke is in the overwritten zone
                val overwrittenStart = lineDragAnchorLine + clamped
                if (strokeLine >= overwrittenStart) {
                    // This stroke is being overwritten — skip it
                    continue
                }
                newStrokes.add(stroke)
            } else {
                newStrokes.add(stroke)
            }
        }

        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newStrokes)
        inkCanvas.loadStrokes(newStrokes)
    }

    private fun onLineDragEnd() {
        val shift = lineDragCurrentShift
        if (shift == 0) {
            // No net shift — restore originals
            val originals = lineDragOriginalStrokes
            if (originals != null) {
                documentModel.activeStrokes.clear()
                documentModel.activeStrokes.addAll(originals)
                inkCanvas.loadStrokes(originals)
            }
        } else {
            // Invalidate text cache for all affected lines
            val minAffected = if (shift < 0) {
                lineDragAnchorLine + shift
            } else {
                lineDragAnchorLine
            }
            val maxAffected = (lineSegmenter.getBottomOccupiedLine(documentModel.activeStrokes)
                .coerceAtLeast(lineDragAnchorLine)) + kotlin.math.abs(shift)
            val invalidated = (minAffected..maxAffected).toSet()
            for (line in invalidated) {
                lineTextCache.remove(line)
            }
            displayHiddenLines()
        }

        lineDragOriginalStrokes = null
        lineDragAnchorLine = -1
        lineDragCurrentShift = 0
        Log.i(TAG, "Line-drag ended: shift=$shift lines")
    }

    // --- Undo / Redo ---

    private fun saveUndoSnapshot() {
        undoManager.saveSnapshot(UndoManager.Snapshot(
            strokes = documentModel.activeStrokes.toList(),
            scrollOffsetY = inkCanvas.scrollOffsetY,
            lineTextCache = lineTextCache.toMap()
        ))
    }

    private fun applySnapshot(snapshot: UndoManager.Snapshot) {
        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(snapshot.strokes)
        inkCanvas.loadStrokes(snapshot.strokes)
        inkCanvas.scrollOffsetY = snapshot.scrollOffsetY
        inkCanvas.drawToSurface()
        lineTextCache.clear()
        lineTextCache.putAll(snapshot.lineTextCache)
        everHiddenLines.clear()
        displayHiddenLines()
    }

    private fun currentSnapshot() = UndoManager.Snapshot(
        strokes = documentModel.activeStrokes.toList(),
        scrollOffsetY = inkCanvas.scrollOffsetY,
        lineTextCache = lineTextCache.toMap()
    )

    fun undo() {
        val snapshot = undoManager.undo(currentSnapshot()) ?: return
        inkCanvas.pauseRawDrawing()
        applySnapshot(snapshot)
        inkCanvas.resumeRawDrawing()
        Log.i(TAG, "Undo: restored ${snapshot.strokes.size} strokes")
    }

    fun redo() {
        val snapshot = undoManager.redo(currentSnapshot()) ?: return
        inkCanvas.pauseRawDrawing()
        applySnapshot(snapshot)
        inkCanvas.resumeRawDrawing()
        Log.i(TAG, "Redo: restored ${snapshot.strokes.size} strokes")
    }

    // --- State persistence ---

    fun getState(): DocumentData {
        return DocumentData(
            strokes = inkCanvas.getStrokes(),
            scrollOffsetY = inkCanvas.scrollOffsetY,
            lineTextCache = lineTextCache.toMap(),
            everHiddenLines = everHiddenLines.toSet(),
            highestLineIndex = highestLineIndex,
            currentLineIndex = currentLineIndex,
            userRenamed = userRenamed
        )
    }

    fun restoreState(data: DocumentData) {
        lineTextCache.putAll(data.lineTextCache)
        everHiddenLines.addAll(data.everHiddenLines)
        highestLineIndex = data.highestLineIndex
        currentLineIndex = data.currentLineIndex
        userRenamed = data.userRenamed
        displayHiddenLines()
    }
}
