package com.writer.ui.writing

import android.util.Log
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.HandwritingRecognizer
import com.writer.recognition.LineSegmenter
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
        // Approximate gutter width for indent calculation
        private const val GUTTER_WIDTH = 144f
    }

    private val lineSegmenter = LineSegmenter()

    // Eager recognition: cache of recognized text per line index
    private val lineTextCache = mutableMapOf<Int, String>()
    // Track which lines are currently being recognized (avoid duplicates)
    private val recognizingLines = mutableSetOf<Int>()
    // Track the highest (bottommost) line the user has written on
    private var highestLineIndex = -1

    fun start() {
        Log.i(TAG, "Coordinator started")
        inkCanvas.onStrokeCompleted = { stroke -> onStrokeCompleted(stroke) }
        inkCanvas.onIdleTimeout = { checkAutoScroll() }
        inkCanvas.onManualScroll = {
            displayHiddenLines()
        }
        // Tap-on-text navigation disabled for now
    }

    fun stop() {
        inkCanvas.onStrokeCompleted = null
        inkCanvas.onIdleTimeout = null
        inkCanvas.onManualScroll = null
    }

    fun reset() {
        lineTextCache.clear()
        recognizingLines.clear()
        highestLineIndex = -1
        currentLineIndex = -1
    }

    // Track which line the user is currently writing on
    private var currentLineIndex = -1

    private fun onStrokeCompleted(stroke: InkStroke) {
        // Check for gestures before adding to model
        if (isStrikethroughGesture(stroke)) {
            val gestureMaxX = stroke.points.maxOf { it.x }
            val canvasWritingWidth = inkCanvas.width - GUTTER_WIDTH
            if (gestureMaxX >= canvasWritingWidth) {
                handleDeleteLine(stroke)
            } else {
                handleStrikethrough(stroke)
            }
            return
        }
        if (isVerticalLineGesture(stroke)) {
            handleInsertLine(stroke)
            return
        }

        documentModel.activeStrokes.add(stroke)

        // Determine which line this stroke belongs to (use start point, not centroid)
        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

        // If the user modified a previously recognized line, invalidate cache
        if (lineTextCache.containsKey(lineIdx)) {
            lineTextCache.remove(lineIdx)
        }

        // Only trigger recognition when the user moves to a DIFFERENT line
        if (lineIdx != currentLineIndex) {
            // Recognize the line we just left (if any)
            if (currentLineIndex >= 0) {
                eagerRecognizeLine(currentLineIndex)
            }
            currentLineIndex = lineIdx
        }

        if (lineIdx > highestLineIndex) {
            highestLineIndex = lineIdx
        }

    }

    private fun isStrikethroughGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 2) return false

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        // Must be wide enough to be intentional
        if (xRange < 100f) return false

        // Must be mostly horizontal
        if (yRange > xRange * 0.3f) return false

        // Must stay within a single line
        val startLineIdx = lineSegmenter.getLineIndex(stroke.points.first().y)
        val endLineIdx = lineSegmenter.getLineIndex(stroke.points.last().y)
        if (startLineIdx != endLineIdx) return false

        return true
    }

    private fun handleStrikethrough(gestureStroke: InkStroke) {
        val centroidY = gestureStroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

        val gestureMinX = gestureStroke.points.minOf { it.x }
        val gestureMaxX = gestureStroke.points.maxOf { it.x }

        // Find all strokes on this line that overlap with the gesture's X span
        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val overlapping = lineStrokes.filter { stroke ->
            val strokeMinX = stroke.points.minOf { it.x }
            val strokeMaxX = stroke.points.maxOf { it.x }
            strokeMaxX >= gestureMinX && strokeMinX <= gestureMaxX
        }

        if (overlapping.isEmpty()) {
            // No strokes to delete — just remove the gesture stroke from canvas
            refreshCanvas { inkCanvas.removeStrokes(setOf(gestureStroke.strokeId)) }
            return
        }

        // Collect IDs to remove: the overlapping strokes + the gesture stroke itself
        val idsToRemove = overlapping.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)

        // Remove from document model
        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }

        // Remove from canvas with e-ink refresh
        refreshCanvas { inkCanvas.removeStrokes(idsToRemove) }

        // Invalidate recognition cache for this line
        lineTextCache.remove(lineIdx)

        // Update text view
        displayHiddenLines()

        Log.i(TAG, "Strikethrough on line $lineIdx: removed ${overlapping.size} strokes")
    }

    /** Delete entire line and shift strokes below up. */
    private fun handleDeleteLine(gestureStroke: InkStroke) {
        val centroidY = gestureStroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

        // Remove all strokes on this line
        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val idsToRemove = lineStrokes.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)
        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }

        // Shift all strokes below this line UP by LINE_SPACING
        val shiftAmount = -HandwritingCanvasView.LINE_SPACING
        val replacements = mutableMapOf<String, InkStroke>()
        val newActiveStrokes = mutableListOf<InkStroke>()

        for (stroke in documentModel.activeStrokes) {
            val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
            if (strokeLine > lineIdx) {
                val shifted = shiftStroke(stroke, shiftAmount)
                replacements[stroke.strokeId] = shifted
                newActiveStrokes.add(shifted)
            } else {
                newActiveStrokes.add(stroke)
            }
        }

        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newActiveStrokes)

        // Update canvas with e-ink refresh
        refreshCanvas {
            inkCanvas.replaceStrokes(replacements)
            inkCanvas.removeStrokes(idsToRemove)
        }

        // Invalidate recognition cache for this line and all shifted lines
        val keysToRemove = lineTextCache.keys.filter { it >= lineIdx }.toList()
        for (key in keysToRemove) {
            lineTextCache.remove(key)
        }

        // Update text view
        displayHiddenLines()

        Log.i(TAG, "Delete line $lineIdx: removed ${lineStrokes.size} strokes, shifted ${replacements.size} up")
    }

    private fun isVerticalLineGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 2) return false

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        // Must be tall enough to be intentional (at least 1.5 line heights)
        if (yRange < HandwritingCanvasView.LINE_SPACING * 1.5f) return false

        // Must be mostly vertical
        if (xRange > yRange * 0.3f) return false

        return true
    }

    private fun handleInsertLine(gestureStroke: InkStroke) {
        // Direction: compare first point to last point
        val startY = gestureStroke.points.first().y
        val endY = gestureStroke.points.last().y
        val startLineIdx = lineSegmenter.getLineIndex(startY)
        val drawingDownward = endY > startY

        // Insert below the start line (downward gesture) or above it (upward gesture)
        // All strokes on lines at or below the insertion point shift down by LINE_SPACING
        val shiftFromLine = if (drawingDownward) startLineIdx + 1 else startLineIdx
        val shiftAmount = HandwritingCanvasView.LINE_SPACING

        // Find strokes that need shifting
        val replacements = mutableMapOf<String, InkStroke>()
        val newActiveStrokes = mutableListOf<InkStroke>()

        for (stroke in documentModel.activeStrokes) {
            val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

            if (lineIdx >= shiftFromLine) {
                // Shift this stroke down
                val shifted = shiftStroke(stroke, shiftAmount)
                replacements[stroke.strokeId] = shifted
                newActiveStrokes.add(shifted)
            } else {
                newActiveStrokes.add(stroke)
            }
        }

        // Update document model
        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newActiveStrokes)

        // Update canvas with e-ink refresh
        refreshCanvas {
            inkCanvas.replaceStrokes(replacements)
            inkCanvas.removeStrokes(setOf(gestureStroke.strokeId))
        }

        // Invalidate recognition cache for all shifted lines
        val keysToRemove = lineTextCache.keys.filter { it >= shiftFromLine }.toList()
        for (key in keysToRemove) {
            lineTextCache.remove(key)
        }

        val direction = if (drawingDownward) "below" else "above"
        Log.i(TAG, "Insert line $direction line $startLineIdx (shifted ${replacements.size} strokes)")
    }

    private fun shiftStroke(stroke: InkStroke, dy: Float): InkStroke {
        val shiftedPoints = stroke.points.map { pt ->
            StrokePoint(pt.x, pt.y + dy, pt.pressure, pt.timestamp)
        }
        return InkStroke(
            strokeId = stroke.strokeId,
            points = shiftedPoints,
            strokeWidth = stroke.strokeWidth
        )
    }

    /** Pause SDK, perform canvas operations, redraw, and resume SDK. */
    private fun refreshCanvas(operations: () -> Unit) {
        inkCanvas.pauseRawDrawing()
        operations()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }

    private fun eagerRecognizeLine(lineIndex: Int) {
        if (lineTextCache.containsKey(lineIndex)) return
        if (recognizingLines.contains(lineIndex)) return
        recognizingLines.add(lineIndex)

        scope.launch {
            try {
                val strokes = lineSegmenter.getStrokesForLine(
                    documentModel.activeStrokes, lineIndex
                )
                if (strokes.isEmpty()) {
                    recognizingLines.remove(lineIndex)
                    return@launch
                }

                val line = lineSegmenter.buildInkLine(strokes, lineIndex)

                // Get pre-context from previously recognized lines
                val preContext = buildPreContext(lineIndex)

                val text = withContext(Dispatchers.IO) {
                    recognizer.recognizeLine(line, preContext)
                }

                lineTextCache[lineIndex] = text.trim()
                recognizingLines.remove(lineIndex)
                Log.d(TAG, "Eager recognized line $lineIndex: \"${text.trim()}\"")
            } catch (e: Exception) {
                Log.e(TAG, "Eager recognition failed for line $lineIndex", e)
                lineTextCache[lineIndex] = "[?]"
                recognizingLines.remove(lineIndex)
            }
        }
    }

    private fun buildPreContext(lineIndex: Int): String {
        // Find the closest recognized line below this one
        val previousLines = lineTextCache.keys.filter { it < lineIndex }.sorted()
        if (previousLines.isEmpty()) return ""
        val lastText = previousLines.map { lineTextCache[it] ?: "" }
            .filter { it.isNotEmpty() && it != "[?]" }
            .joinToString(" ")
        return lastText.takeLast(20)
    }

    private fun checkAutoScroll() {
        val strokes = documentModel.activeStrokes
        if (strokes.isEmpty()) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        // Find the bottommost occupied line
        val bottomLine = lineSegmenter.getBottomOccupiedLine(strokes)
        if (bottomLine < 0) return

        // Convert to screen space (use bottom edge of line, not top)
        val bottomLineDocY = lineSegmenter.getLineY(bottomLine) + HandwritingCanvasView.LINE_SPACING
        val bottomLineScreenY = bottomLineDocY - inkCanvas.scrollOffsetY
        val targetY = canvasHeight * SCROLL_THRESHOLD

        // Only auto-scroll if we're at the bottom of the document
        // (the bottommost writing is visible in the canvas viewport)
        if (bottomLineScreenY < 0 || bottomLineScreenY > canvasHeight) {
            return
        }

        if (bottomLineScreenY < targetY) {
            return
        }

        // Scroll so the active writing line is at the 25% mark, snapped to line boundary
        val rawOffset = bottomLineDocY - targetY
        val newOffset = inkCanvas.snapToLine(rawOffset)
        if (newOffset > inkCanvas.scrollOffsetY) {
            Log.i(TAG, "AutoScroll: shifting from ${inkCanvas.scrollOffsetY.toInt()} to ${newOffset.toInt()}")
            inkCanvas.pauseRawDrawing()
            inkCanvas.scrollOffsetY = newOffset
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()

            // Display text for lines that are now above the viewport
            displayHiddenLines()
        }
    }

    private fun displayHiddenLines() {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)

        // Find all line indices that are fully above the viewport
        val hiddenLines = strokesByLine.keys.filter { lineIdx ->
            val lineBottom = lineSegmenter.getLineY(lineIdx) + HandwritingCanvasView.LINE_SPACING
            lineBottom <= inkCanvas.scrollOffsetY
        }.sorted()

        // Immediately update text view with whatever we have cached
        updateTextView(hiddenLines)

        // Kick off recognition for any uncached lines in the background
        val uncached = hiddenLines.filter { !lineTextCache.containsKey(it) }
        if (uncached.isNotEmpty()) {
            scope.launch {
                for (lineIdx in uncached) {
                    if (lineTextCache.containsKey(lineIdx)) continue
                    try {
                        val strokes = strokesByLine[lineIdx] ?: continue
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
                // Re-update text view now that recognition is done
                withContext(Dispatchers.Main) {
                    updateTextView(hiddenLines)
                }
            }
        }
    }

    private fun updateTextView(hiddenLines: List<Int>) {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width - GUTTER_WIDTH

        // Build paragraphs by detecting indented lines
        val paragraphs = mutableListOf<String>()
        val paragraphLineIndices = mutableListOf<List<Int>>()
        val currentParagraph = mutableListOf<String>()
        val currentLineIndices = mutableListOf<Int>()

        for (lineIdx in hiddenLines) {
            val text = lineTextCache[lineIdx]
            if (text.isNullOrEmpty() || text == "[?]") continue

            // Check if this line is indented (starts a new paragraph)
            val lineStrokes = strokesByLine[lineIdx]
            if (lineStrokes != null && lineStrokes.isNotEmpty() && currentParagraph.isNotEmpty()) {
                val leftmostX = lineStrokes.minOf { stroke -> stroke.points.minOf { it.x } }
                if (leftmostX > writingWidth * INDENT_THRESHOLD) {
                    // Indented line — flush current paragraph, start new one
                    paragraphs.add(currentParagraph.joinToString(" "))
                    paragraphLineIndices.add(currentLineIndices.toList())
                    currentParagraph.clear()
                    currentLineIndices.clear()
                }
            }

            currentParagraph.add(text)
            currentLineIndices.add(lineIdx)
        }

        // Flush remaining
        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(currentParagraph.joinToString(" "))
            paragraphLineIndices.add(currentLineIndices.toList())
        }

        textView.setParagraphs(paragraphs, paragraphLineIndices)
    }

}
