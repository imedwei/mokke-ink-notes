package com.writer.ui.writing

import android.util.Log
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.recognition.HandwritingRecognizer
import com.writer.recognition.LineSegmenter
import com.writer.storage.DocumentData
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        // A line indented more than 15% from the left starts a new paragraph
        private const val INDENT_THRESHOLD = 0.15f
        private val GUTTER_WIDTH = HandwritingCanvasView.GUTTER_WIDTH
    }

    private val lineSegmenter = LineSegmenter()
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

    // Eager recognition: cache of recognized text per line index
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

    fun start() {
        Log.i(TAG, "Coordinator started")
        inkCanvas.onStrokeCompleted = { stroke -> onStrokeCompleted(stroke) }
        inkCanvas.onIdleTimeout = { onIdle() }
        inkCanvas.onManualScroll = { displayHiddenLines() }
        textView.onTextTap = { lineIndex -> scrollToLine(lineIndex) }
    }

    fun stop() {
        scrollAnimating = false
        inkCanvas.onStrokeCompleted = null
        inkCanvas.onIdleTimeout = null
        inkCanvas.onManualScroll = null
        textView.onTextTap = null
    }

    fun reset() {
        scrollAnimating = false
        lineTextCache.clear()
        recognizingLines.clear()
        pendingRerecognize.clear()
        everHiddenLines.clear()
        highestLineIndex = -1
        currentLineIndex = -1
    }

    private fun onStrokeCompleted(stroke: InkStroke) {
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

    private fun eagerRecognizeLine(lineIndex: Int) {
        if (lineTextCache.containsKey(lineIndex)) return
        if (recognizingLines.contains(lineIndex)) return
        recognizingLines.add(lineIndex)

        scope.launch {
            try {
                val allStrokes = lineSegmenter.getStrokesForLine(
                    documentModel.activeStrokes, lineIndex
                )
                if (allStrokes.isEmpty()) {
                    recognizingLines.remove(lineIndex)
                    return@launch
                }
                val strokes = filterMarkerStroke(allStrokes)
                if (strokes.isEmpty()) {
                    recognizingLines.remove(lineIndex)
                    return@launch
                }

                val line = lineSegmenter.buildInkLine(strokes, lineIndex)
                val preContext = buildPreContext(lineIndex)

                val text = withContext(Dispatchers.IO) {
                    recognizer.recognizeLine(line, preContext)
                }

                lineTextCache[lineIndex] = text.trim()
                recognizingLines.remove(lineIndex)
                Log.d(TAG, "Eager recognized line $lineIndex: \"${text.trim()}\"")

                if (lineIndex in everHiddenLines) {
                    withContext(Dispatchers.Main) { displayHiddenLines() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Eager recognition failed for line $lineIndex", e)
                lineTextCache[lineIndex] = "[?]"
                recognizingLines.remove(lineIndex)
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
            try {
                val allStrokes = lineSegmenter.getStrokesForLine(
                    documentModel.activeStrokes, lineIndex
                )
                if (allStrokes.isEmpty()) {
                    recognizingLines.remove(lineIndex)
                    return@launch
                }
                val strokes = filterMarkerStroke(allStrokes)
                if (strokes.isEmpty()) {
                    recognizingLines.remove(lineIndex)
                    return@launch
                }

                val line = lineSegmenter.buildInkLine(strokes, lineIndex)
                val preContext = buildPreContext(lineIndex)
                val text = withContext(Dispatchers.IO) {
                    recognizer.recognizeLine(line, preContext)
                }

                lineTextCache[lineIndex] = text.trim()
                recognizingLines.remove(lineIndex)
                Log.d(TAG, "Rendered line recognized $lineIndex: \"${text.trim()}\"")

                withContext(Dispatchers.Main) {
                    displayHiddenLines()
                    // Force e-ink refresh
                    inkCanvas.pauseRawDrawing()
                    inkCanvas.drawToSurface()
                    inkCanvas.resumeRawDrawing()
                }

                if (lineIndex in pendingRerecognize) {
                    pendingRerecognize.remove(lineIndex)
                    recognizeRenderedLine(lineIndex)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rendered line recognition failed for line $lineIndex", e)
                lineTextCache[lineIndex] = "[?]"
                recognizingLines.remove(lineIndex)
                pendingRerecognize.remove(lineIndex)
            }
        }
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

        scope.launch(Dispatchers.Main) {
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
                        val strokes = filterMarkerStroke(allStrokes)
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
                withContext(Dispatchers.Main) {
                    val stillNotVisible = strokesByLine.keys.filter { lineIdx ->
                        val lineMid = lineSegmenter.getLineY(lineIdx) + HandwritingCanvasView.LINE_SPACING / 2f
                        lineMid <= inkCanvas.scrollOffsetY
                    }.toSet()
                    updateTextView(stillNotVisible)
                }
            }
        }
    }

    /** A segment of text within a paragraph, with its own dimming state. */
    data class TextSegment(val text: String, val dimmed: Boolean, val lineIndex: Int, val listItem: Boolean = false, val heading: Boolean = false)

    private fun updateTextView(currentlyHidden: Set<Int>) {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width - GUTTER_WIDTH

        val linesToShow = everHiddenLines.sorted()

        val paragraphs = mutableListOf<List<TextSegment>>()
        var currentSegments = mutableListOf<TextSegment>()

        for (lineIdx in linesToShow) {
            val text = lineTextCache[lineIdx]
            if (text.isNullOrEmpty() || text == "[?]") continue

            val lineStrokes = strokesByLine[lineIdx]
            val isList = lineStrokes != null && findListMarkerStrokeId(lineStrokes, writingWidth) != null
            val isHeading = lineStrokes != null && findUnderlineStrokeId(lineStrokes, lineIdx) != null

            if (lineStrokes != null && lineStrokes.isNotEmpty() && currentSegments.isNotEmpty()) {
                val leftmostX = lineStrokes.minOf { stroke -> stroke.points.minOf { it.x } }
                val prevWasList = currentSegments.any { it.listItem }
                val prevWasHeading = currentSegments.any { it.heading }
                val isIndented = leftmostX > writingWidth * INDENT_THRESHOLD
                // Break paragraph on:
                // - indent (unless continuing a list item)
                // - new list item
                // - non-indented line after a list item (back to normal text)
                // - heading (always its own paragraph)
                // - line after a heading
                val shouldBreak = isList || isHeading || prevWasHeading ||
                    (isIndented && !prevWasList) ||
                    (prevWasList && !isIndented && !isList)
                if (shouldBreak) {
                    paragraphs.add(currentSegments)
                    currentSegments = mutableListOf()
                }
            }

            currentSegments.add(TextSegment(text, dimmed = lineIdx !in currentlyHidden, lineIndex = lineIdx, listItem = isList, heading = isHeading))
        }

        if (currentSegments.isNotEmpty()) {
            paragraphs.add(currentSegments)
        }

        textView.setParagraphs(paragraphs)
    }

    /**
     * Find the list marker stroke on a line, if present: a short, flat horizontal
     * stroke on the far left, separated from the main text by a gap.
     * Returns the marker stroke ID, or null if no marker found.
     */
    private fun findListMarkerStrokeId(strokes: List<InkStroke>, writingWidth: Float): String? {
        if (strokes.size < 2) return null

        // Sort strokes by their leftmost x position
        val sorted = strokes.sortedBy { stroke -> stroke.points.minOf { it.x } }
        val first = sorted[0]

        val firstMinX = first.points.minOf { it.x }
        val firstMaxX = first.points.maxOf { it.x }
        val firstMinY = first.points.minOf { it.y }
        val firstMaxY = first.points.maxOf { it.y }
        val firstWidth = firstMaxX - firstMinX
        val firstHeight = firstMaxY - firstMinY

        // Must be on the far left
        if (firstMinX > writingWidth * INDENT_THRESHOLD) return null

        // Must be short and very flat (a dash, not a letter)
        if (firstWidth < 15f || firstWidth > 120f) return null
        if (firstHeight > firstWidth * 0.4f) return null

        // A real dash is a simple stroke — reject if the path length is much
        // longer than the bounding box diagonal (indicates curves/loops like letters)
        val pathLength = first.points.zipWithNext { a, b ->
            val dx = b.x - a.x; val dy = b.y - a.y
            kotlin.math.sqrt(dx * dx + dy * dy)
        }.sum()
        val diagonal = kotlin.math.sqrt(firstWidth * firstWidth + firstHeight * firstHeight)
        if (pathLength > diagonal * 2f) return null

        // Must have a gap between the marker and the next stroke
        val secondMinX = sorted[1].points.minOf { it.x }
        val gap = secondMinX - firstMaxX
        if (gap < 20f) return null

        return first.strokeId
    }

    /**
     * Find an underline stroke on a line, if present: a long horizontal stroke
     * near the bottom of the line that spans at least 80% of the text width.
     * The underline is always assigned to the line where it starts, even if
     * it wiggles into the line below.
     * Returns the underline stroke ID, or null if no underline found.
     */
    private fun findUnderlineStrokeId(strokes: List<InkStroke>, lineIndex: Int): String? {
        if (strokes.size < 2) return null

        val lineTop = lineSegmenter.getLineY(lineIndex)
        val lineBottom = lineTop + HandwritingCanvasView.LINE_SPACING

        // Find the bounding box of all non-underline-candidate strokes (the text)
        // First pass: find strokes that could be text vs underline candidates
        for (stroke in strokes) {
            val minY = stroke.points.minOf { it.y }
            val maxY = stroke.points.maxOf { it.y }
            val minX = stroke.points.minOf { it.x }
            val maxX = stroke.points.maxOf { it.x }
            val strokeWidth = maxX - minX
            val strokeHeight = maxY - minY

            // Underline candidate: horizontal, near bottom of line, flat
            if (strokeHeight > strokeWidth * 0.3f) continue  // not flat enough
            if (strokeWidth < 100f) continue  // too short to be an underline
            if (minY < lineTop + HandwritingCanvasView.LINE_SPACING * 0.5f) continue  // too high up

            // Check path simplicity (same as list marker check)
            val pathLength = stroke.points.zipWithNext { a, b ->
                val dx = b.x - a.x; val dy = b.y - a.y
                kotlin.math.sqrt(dx * dx + dy * dy)
            }.sum()
            val diagonal = kotlin.math.sqrt(strokeWidth * strokeWidth + strokeHeight * strokeHeight)
            if (pathLength > diagonal * 2f) continue  // too complex

            // Now check that it spans at least 80% of the text on this line
            val textStrokes = strokes.filter { it.strokeId != stroke.strokeId }
            if (textStrokes.isEmpty()) continue
            val textMinX = textStrokes.minOf { s -> s.points.minOf { it.x } }
            val textMaxX = textStrokes.maxOf { s -> s.points.maxOf { it.x } }
            val textWidth = textMaxX - textMinX
            if (textWidth <= 0f) continue

            if (strokeWidth >= textWidth * 0.8f) {
                return stroke.strokeId
            }
        }

        return null
    }

    /**
     * Filter out the list marker and underline strokes before recognition.
     */
    private fun filterMarkerStroke(strokes: List<InkStroke>): List<InkStroke> {
        val writingWidth = inkCanvas.width - GUTTER_WIDTH
        val excludeIds = mutableSetOf<String>()

        val markerId = findListMarkerStrokeId(strokes, writingWidth)
        if (markerId != null) excludeIds.add(markerId)

        // Need to determine line index from strokes for underline detection
        val lineIndex = lineSegmenter.getStrokeLineIndex(strokes.first())
        val underlineId = findUnderlineStrokeId(strokes, lineIndex)
        if (underlineId != null) excludeIds.add(underlineId)

        return if (excludeIds.isEmpty()) strokes else strokes.filter { it.strokeId !in excludeIds }
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
        val writingWidth = inkCanvas.width - GUTTER_WIDTH
        val sortedLines = lineTextCache.keys.sorted()

        val paragraphs = mutableListOf<String>()
        var currentLines = mutableListOf<String>()
        var currentIsList = false
        var currentIsHeading = false

        for (lineIdx in sortedLines) {
            val text = lineTextCache[lineIdx]
            if (text.isNullOrEmpty() || text == "[?]") continue

            val lineStrokes = strokesByLine[lineIdx]
            val isList = lineStrokes != null && findListMarkerStrokeId(lineStrokes, writingWidth) != null
            val isHeading = lineStrokes != null && findUnderlineStrokeId(lineStrokes, lineIdx) != null

            if (lineStrokes != null && lineStrokes.isNotEmpty() && currentLines.isNotEmpty()) {
                val leftmostX = lineStrokes.minOf { stroke -> stroke.points.minOf { it.x } }
                val isIndented = leftmostX > writingWidth * INDENT_THRESHOLD
                val shouldBreak = isList || isHeading || currentIsHeading ||
                    (isIndented && !currentIsList) ||
                    (currentIsList && !isIndented && !isList)
                if (shouldBreak) {
                    val joined = currentLines.joinToString(" ")
                    val prefix = if (currentIsHeading) "## " else if (currentIsList) "- " else ""
                    paragraphs.add("$prefix$joined")
                    currentLines = mutableListOf()
                }
            }

            if (currentLines.isEmpty()) {
                currentIsList = isList
                currentIsHeading = isHeading
            }
            currentLines.add(text)
        }

        if (currentLines.isNotEmpty()) {
            val joined = currentLines.joinToString(" ")
            val prefix = if (currentIsHeading) "## " else if (currentIsList) "- " else ""
            paragraphs.add("$prefix$joined")
        }

        return paragraphs.joinToString("\n\n")
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
            currentLineIndex = currentLineIndex
        )
    }

    fun restoreState(data: DocumentData) {
        lineTextCache.putAll(data.lineTextCache)
        everHiddenLines.addAll(data.everHiddenLines)
        highestLineIndex = data.highestLineIndex
        currentLineIndex = data.currentLineIndex
        displayHiddenLines()
    }
}
