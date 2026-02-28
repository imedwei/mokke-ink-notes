package com.writer.ui.writing

import android.util.Log
import android.widget.ScrollView
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
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
    private val textScrollView: ScrollView,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "WritingCoordinator"
        // Scroll when writing passes this fraction of canvas height from top
        // 25% of canvas ≈ 50% of full screen (since canvas is 75% of screen)
        private const val SCROLL_THRESHOLD = 0.25f
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
            Log.i(TAG, "Manual scroll ended, scrollOffsetY=${inkCanvas.scrollOffsetY.toInt()}")
            displayHiddenLines()
        }
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
        documentModel.activeStrokes.add(stroke)

        // Determine which line this stroke belongs to
        val centroidY = stroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

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

        val count = documentModel.activeStrokes.size
        onStatusUpdate("Strokes: $count")
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

        Log.d(TAG, "AutoScroll check: bottomLine=$bottomLine screenY=${bottomLineScreenY.toInt()}, " +
                "threshold=${targetY.toInt()}")

        // Only auto-scroll if we're at the bottom of the document
        // (the bottommost writing is visible in the canvas viewport)
        if (bottomLineScreenY < 0 || bottomLineScreenY > canvasHeight) {
            Log.d(TAG, "AutoScroll: not at bottom of document, skipping")
            return
        }

        if (bottomLineScreenY < targetY) {
            Log.d(TAG, "AutoScroll: writing not past threshold yet")
            return
        }

        // Scroll so the active writing line is at the 25% mark, snapped to line boundary
        val rawOffset = bottomLineDocY - targetY
        val newOffset = inkCanvas.snapToLine(rawOffset)
        if (newOffset > inkCanvas.scrollOffsetY) {
            Log.i(TAG, "AutoScroll: shifting from ${inkCanvas.scrollOffsetY.toInt()} to ${newOffset.toInt()}")
            inkCanvas.scrollOffsetY = newOffset
            inkCanvas.invalidate()

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
        val textLines = hiddenLines.mapNotNull { lineTextCache[it] }
            .filter { it.isNotEmpty() && it != "[?]" }

        if (textLines.isEmpty()) {
            textView.setParagraphs(emptyList())
        } else {
            textView.setParagraphs(listOf(textLines.joinToString(" ")))
        }

        textScrollView.post {
            textScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
