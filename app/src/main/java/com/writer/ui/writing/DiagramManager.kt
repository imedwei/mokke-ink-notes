package com.writer.ui.writing

import android.util.Log
import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokeType
import com.writer.model.minX
import com.writer.model.minY
import com.writer.model.maxX
import com.writer.model.maxY
import com.writer.model.shiftY
import com.writer.recognition.LineSegmenter
import com.writer.recognition.TextRecognizer
import com.writer.view.HandwritingCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Callback interface for DiagramManager to communicate with its host. */
interface DiagramManagerHost {
    fun onDiagramAreasChanged()
    fun getLineTextCache(): MutableMap<Int, String>
}

class DiagramManager(
    val documentModel: DocumentModel,
    val lineSegmenter: LineSegmenter,
    private val recognizer: TextRecognizer,
    private val canvas: DiagramCanvas,
    private val host: DiagramManagerHost,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DiagramManager"
        // Delay before running diagram text recognition after a stroke
        private const val DIAGRAM_RECOGNIZE_DELAY_MS = 600L
    }

    /** Recognized text groups keyed by diagram area start line. */
    data class DiagramTextGroup(
        val text: String,
        val centerX: Float,
        val centerY: Float,
        val strokeIds: Set<String>
    )

    // Debounced diagram recognition jobs, keyed by area startLineIndex
    private val diagramRecognizeJobs = mutableMapOf<Int, Job>()
    // Diagram text: recognized text groups keyed by diagram area start line
    private val diagramTextCache = mutableMapOf<Int, List<DiagramTextGroup>>()

    /** Check if a line index falls inside any diagram area. */
    fun isDiagramLine(lineIdx: Int): Boolean =
        documentModel.diagramAreas.any { it.containsLine(lineIdx) }

    /** Check if a line has pre-existing strokes (not inside any diagram area). */
    fun hasTextStrokesOnLine(lineIdx: Int, excluding: InkStroke? = null): Boolean =
        documentModel.activeStrokes.any {
            it !== excluding &&
            !isDiagramLine(lineSegmenter.getStrokeLineIndex(it)) &&
            lineSegmenter.getStrokeLineIndex(it) == lineIdx
        }

    /**
     * After strokes are erased from a diagram, shrink the area to fit remaining
     * content and shift text below up to reclaim freed space.
     */
    fun shrinkDiagramAfterErase(area: DiagramArea) {
        val ls = HandwritingCanvasView.LINE_SPACING
        val lineTextCache = host.getLineTextCache()

        // Find all remaining strokes in this diagram area
        val remainingStrokes = documentModel.activeStrokes.filter { stroke ->
            area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
        }

        if (remainingStrokes.isEmpty()) {
            // Diagram is empty — remove it and shift everything below up
            val linesFreed = area.heightInLines
            val shiftUpPx = linesFreed * ls
            val oldEnd = area.endLineIndex

            documentModel.diagramAreas.remove(area)
            diagramTextCache.remove(area.startLineIndex)

            // Shift strokes below the old diagram up
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                if (strokeLine > oldEnd) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            // Shift other diagram areas below
            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex > oldEnd)
                    other.copy(startLineIndex = other.startLineIndex - linesFreed)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            // Shift text cache
            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line > oldEnd) line - linesFreed else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)

            canvas.loadStrokes(documentModel.activeStrokes.toList())
            canvas.diagramAreas = documentModel.diagramAreas.toList()
            canvas.pauseAndRedraw()
            host.onDiagramAreasChanged()
            Log.i(TAG, "Removed empty diagram area ${area.startLineIndex}-${area.endLineIndex}, shifted up $linesFreed lines")
            return
        }

        // Compute tight bounds from remaining strokes + 1-line padding
        val minLine = remainingStrokes.minOf { lineSegmenter.getLineIndex(it.minY) }
        val maxLine = remainingStrokes.maxOf { lineSegmenter.getLineIndex(it.maxY) }
        val newStart = (minLine - 1).coerceAtLeast(area.startLineIndex)
        val newEnd = (maxLine + 1).coerceAtMost(area.endLineIndex)

        val linesFreedBelow = area.endLineIndex - newEnd
        val linesFreedAbove = newStart - area.startLineIndex
        val totalFreed = linesFreedAbove + linesFreedBelow

        if (totalFreed == 0) return  // no change

        // Step 1: Shrink the diagram area first
        documentModel.diagramAreas.remove(area)
        val shrunk = DiagramArea(
            startLineIndex = newStart,
            heightInLines = newEnd - newStart + 1
        )
        documentModel.diagramAreas.add(shrunk)

        // Step 2: Shift content below the old diagram end UP by linesFreedBelow
        if (linesFreedBelow > 0) {
            val shiftUpPx = linesFreedBelow * ls
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                if (strokeLine > area.endLineIndex) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex > area.endLineIndex)
                    other.copy(startLineIndex = other.startLineIndex - linesFreedBelow)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line > area.endLineIndex) line - linesFreedBelow else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)
        }

        // Step 3: Shrink from top — shift diagram content + everything below UP
        if (linesFreedAbove > 0) {
            val shiftUpPx = linesFreedAbove * ls
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                if (strokeLine >= newStart) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex >= newStart)
                    other.copy(startLineIndex = other.startLineIndex - linesFreedAbove)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line >= newStart) line - linesFreedAbove else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)

            canvas.scrollOffsetY = (canvas.scrollOffsetY - shiftUpPx).coerceAtLeast(0f)
        }

        canvas.loadStrokes(documentModel.activeStrokes.toList())
        canvas.diagramAreas = documentModel.diagramAreas.toList()
        canvas.pauseAndRedraw()
        host.onDiagramAreasChanged()

        for (line in 0..area.endLineIndex) { lineTextCache.remove(line) }

        val finalArea = documentModel.diagramAreas.find {
            it.heightInLines == shrunk.heightInLines
        }
        Log.i(TAG, "Shrunk diagram: ${area.startLineIndex}-${area.endLineIndex} -> " +
            "${finalArea?.startLineIndex}-${finalArea?.endLineIndex} (freed: up=$linesFreedAbove down=$linesFreedBelow)")
    }

    /**
     * Schedule diagram text recognition for [area], debounced by default.
     * Rapid calls cancel the previous pending job — only the last one runs.
     * Pass [immediate] = true for initial load (no delay).
     */
    fun recognizeDiagramArea(area: DiagramArea, immediate: Boolean = false) {
        diagramRecognizeJobs[area.startLineIndex]?.cancel()
        if (immediate) {
            recognizeDiagramAreaNow(area)
            return
        }
        diagramRecognizeJobs[area.startLineIndex] = scope.launch {
            delay(DIAGRAM_RECOGNIZE_DELAY_MS)
            recognizeDiagramAreaNow(area)
        }
    }

    private fun recognizeDiagramAreaNow(area: DiagramArea) {
        val lineTextCache = host.getLineTextCache()

        // Collect all freehand strokes in the diagram area
        val freehandStrokes = documentModel.activeStrokes.filter { stroke ->
            stroke.strokeType == StrokeType.FREEHAND &&
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
        }
        if (freehandStrokes.isEmpty()) {
            diagramTextCache.remove(area.startLineIndex)
            return
        }

        val ls = HandwritingCanvasView.LINE_SPACING

        // Collect geometric (shape) strokes for classification context
        val shapeStrokes = documentModel.activeStrokes.filter { stroke ->
            stroke.strokeType != StrokeType.FREEHAND &&
                !stroke.strokeType.isConnector &&
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
        }

        // Classify freehand strokes as text candidates or drawing strokes.
        // Uses per-stroke heuristics (height, complexity, size, closure) and
        // group-level contagion (freehand near drawings/shapes -> drawing).
        val (textStrokes, _) = DiagramStrokeClassifier.partition(
            freehandStrokes, shapeStrokes, ls
        )
        if (textStrokes.isEmpty()) {
            diagramTextCache.remove(area.startLineIndex)
            return
        }

        val groups = DiagramTextGrouping.groupByProximity(textStrokes, ls, shapeStrokes)

        scope.launch {
            val results = mutableListOf<DiagramTextGroup>()
            for (group in groups) {
                val yRows = DiagramTextGrouping.splitIntoRowsAdaptive(group, ls * 0.25f)
                val groupIds = group.map { it.strokeId }.toSet()
                for (row in yRows) {
                    val inkLine = InkLine.build(row)
                    try {
                        val preContext = buildPreContext(area.startLineIndex, lineTextCache)
                        val text = withContext(Dispatchers.IO) {
                            recognizer.recognizeLine(inkLine, preContext)
                        }.trim()
                        if (text.isNotEmpty() && text != "[?]") {
                            val cx = (inkLine.boundingBox.left + inkLine.boundingBox.right) / 2f
                            val cy = (inkLine.boundingBox.top + inkLine.boundingBox.bottom) / 2f
                            results.add(DiagramTextGroup(
                                text = text,
                                centerX = cx,
                                centerY = cy,
                                strokeIds = groupIds
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Diagram group recognition failed", e)
                    }
                }
            }
            diagramTextCache[area.startLineIndex] = results
            Log.d(TAG, "Diagram area ${area.startLineIndex}: recognized ${results.size} groups: ${results.map { it.text }}")
            host.onDiagramAreasChanged()
        }
    }

    private fun buildPreContext(lineIndex: Int, lineTextCache: Map<Int, String>): String =
        com.writer.ui.writing.buildPreContext(lineTextCache, lineIndex)

    /**
     * Called when a shape is detected outside a diagram area.
     * Creates a diagram area around the shape's bounding box with 1 line padding,
     * merging with adjacent diagram areas.
     * Returns the diagram bounds (topY, bottomY) or null.
     */
    fun onShapeDetected(stroke: InkStroke): Pair<Float, Float>? {
        val lineTextCache = host.getLineTextCache()
        val minY = stroke.minY
        val maxY = stroke.maxY
        val topLine = lineSegmenter.getLineIndex(minY)
        val bottomLine = lineSegmenter.getLineIndex(maxY)

        // Exact bounding box — no forced padding. Overflow expansion will
        // add space as needed when strokes cross the boundary.
        // Don't extend into lines with pre-existing text strokes.
        var mergeStart = topLine
        var mergeHeight = bottomLine - mergeStart + 1

        // Merge with adjacent diagram area above
        val above = documentModel.diagramAreas.find { it.endLineIndex + 1 >= mergeStart && it.endLineIndex < mergeStart + mergeHeight }
        if (above != null && above.endLineIndex + 1 >= mergeStart) {
            if (above.startLineIndex < mergeStart) {
                mergeHeight += mergeStart - above.startLineIndex
                mergeStart = above.startLineIndex
            }
            documentModel.diagramAreas.remove(above)
        }

        // Merge with adjacent diagram area below
        val below = documentModel.diagramAreas.find { it.startLineIndex <= mergeStart + mergeHeight && it.startLineIndex >= mergeStart }
        if (below != null && below != above) {
            val belowEnd = below.startLineIndex + below.heightInLines
            if (belowEnd > mergeStart + mergeHeight) {
                mergeHeight = belowEnd - mergeStart
            }
            documentModel.diagramAreas.remove(below)
        }

        val newArea = DiagramArea(
            startLineIndex = mergeStart,
            heightInLines = mergeHeight
        )
        documentModel.diagramAreas.add(newArea)
        canvas.diagramAreas = documentModel.diagramAreas.toList()

        // Invalidate text cache for affected lines
        for (line in mergeStart until mergeStart + mergeHeight) {
            lineTextCache.remove(line)
        }

        val topY = lineSegmenter.getLineY(mergeStart)
        val bottomY = lineSegmenter.getLineY(mergeStart + mergeHeight)
        Log.i(TAG, "Auto-created diagram area: lines $mergeStart-${mergeStart + mergeHeight - 1}")
        return Pair(topY, bottomY)
    }

    /**
     * Called when a stroke inside a diagram area extends beyond its bounds.
     * Expands the diagram area to cover the stroke's Y range.
     */
    fun onStrokeOverflow(overflowStrokeId: String, strokeMinY: Float, strokeMaxY: Float) {
        val lineTextCache = host.getLineTextCache()
        val strokeTopLine = lineSegmenter.getLineIndex(strokeMinY)
        val strokeBottomLine = lineSegmenter.getLineIndex(strokeMaxY)

        // Find the diagram area that the stroke started in (closest match)
        val area = documentModel.diagramAreas.find {
            // The stroke overlaps this area
            strokeTopLine <= it.endLineIndex && strokeBottomLine >= it.startLineIndex
        } ?: return

        val ls = HandwritingCanvasView.LINE_SPACING
        val linesAbove = maxOf(0, area.startLineIndex - strokeTopLine + 1)  // +1 for padding
        val linesBelow = maxOf(0, strokeBottomLine - area.endLineIndex + 1)

        if (linesAbove == 0 && linesBelow == 0) return

        // Upward expansion: shift diagram strokes and the overflow stroke DOWN.
        // Text above stays in place.
        // Use centroid (getStrokeLineIndex) to classify — avoids catching text
        // descenders that barely cross the diagram boundary.
        // The overflow stroke is identified by matching its Y bounds.
        if (linesAbove > 0) {
            val shiftPx = linesAbove * ls
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                val isInsideDiagram = strokeLine >= area.startLineIndex
                val isOverflowStroke = stroke.strokeId == overflowStrokeId
                if (isInsideDiagram || isOverflowStroke) stroke.shiftY(shiftPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            // Shift diagram areas at or below
            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex >= area.startLineIndex)
                    other.copy(startLineIndex = other.startLineIndex + linesAbove)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            // Shift text cache
            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line >= area.startLineIndex) line + linesAbove else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)

            // Scroll so diagram appears at same screen position
            canvas.scrollOffsetY += shiftPx
        }

        // Downward expansion: shift strokes below the diagram DOWN,
        // but NOT the overflow stroke (it stays — it's part of the diagram).
        // Use centroid to classify. Overflow stroke identified by Y bounds.
        if (linesBelow > 0) {
            val shiftPx = linesBelow * ls
            val postEndLine = area.endLineIndex + linesAbove  // adjusted for upward shift
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                val isOverflowStroke = stroke.strokeId == overflowStrokeId
                if (strokeLine > postEndLine && !isOverflowStroke)
                    stroke.shiftY(shiftPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex > postEndLine)
                    other.copy(startLineIndex = other.startLineIndex + linesBelow)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line > postEndLine) line + linesBelow else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)
        }

        // Expand diagram: keep original startLineIndex, grow height.
        // After upward shift, the area moved to startLineIndex + linesAbove.
        // We expand it back to the original startLineIndex.
        val shiftedArea = documentModel.diagramAreas.find {
            it.startLineIndex == area.startLineIndex + linesAbove
        } ?: return
        documentModel.diagramAreas.remove(shiftedArea)
        val expanded = DiagramArea(
            startLineIndex = area.startLineIndex,
            heightInLines = area.heightInLines + linesAbove + linesBelow
        )
        documentModel.diagramAreas.add(expanded)

        // Update canvas and force redraw so expanded bounds are immediately visible
        canvas.loadStrokes(documentModel.activeStrokes.toList())
        canvas.diagramAreas = documentModel.diagramAreas.toList()
        canvas.pauseAndRedraw()
        for (line in expanded.startLineIndex..expanded.endLineIndex) {
            lineTextCache.remove(line)
        }
        Log.i(TAG, "Expanded diagram: ${area.startLineIndex}-${area.endLineIndex} -> " +
            "${expanded.startLineIndex}-${expanded.endLineIndex} (above=$linesAbove below=$linesBelow)")
    }

    /**
     * Handle strokes being erased (from scratch-out): invalidate diagram text cache
     * for affected areas and shrink diagram areas that contained erased strokes.
     * @return true if the canvas was redrawn (caller should skip its own drawToSurface)
     */
    fun onStrokesErased(idsToRemove: Set<String>, erasedStrokes: List<InkStroke>): Boolean {
        // Invalidate diagram text cache for any affected diagram areas
        for (area in documentModel.diagramAreas) {
            val cachedGroups = diagramTextCache[area.startLineIndex] ?: continue
            if (cachedGroups.any { group -> group.strokeIds.any { it in idsToRemove } }) {
                recognizeDiagramArea(area)
            }
        }

        // Shrink diagram areas that contained erased strokes
        var redrew = false
        val affectedStartLines = erasedStrokes.map { lineSegmenter.getStrokeLineIndex(it) }.toSet()
        for (startLine in affectedStartLines) {
            val area = documentModel.diagramAreas.find { it.containsLine(startLine) } ?: continue
            shrinkDiagramAfterErase(area)
            redrew = true
        }
        return redrew
    }

    /** Get recognized text groups for a diagram area by start line index. */
    fun getTextGroups(startLineIndex: Int): List<DiagramTextGroup> =
        diagramTextCache[startLineIndex] ?: emptyList()

    /** Cancel pending jobs and clear diagram recognition state. */
    fun stop() {
        diagramRecognizeJobs.values.forEach { it.cancel() }
        diagramRecognizeJobs.clear()
    }

    /** Reset all diagram state. */
    fun reset() {
        diagramRecognizeJobs.values.forEach { it.cancel() }
        diagramRecognizeJobs.clear()
        diagramTextCache.clear()
    }

    /** Clear the diagram text cache (used during undo/redo). */
    fun clearTextCache() {
        diagramTextCache.clear()
    }
}
