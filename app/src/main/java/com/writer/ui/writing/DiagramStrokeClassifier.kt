package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokeType
import com.writer.model.pathLength
import com.writer.model.diagonal
import com.writer.model.xRange
import com.writer.model.yRange
import kotlin.math.hypot
import kotlin.math.max

/**
 * Classifies freehand strokes inside diagram areas as text candidates or drawing strokes.
 *
 * Uses a scored heuristic approach: each stroke gets a "drawing score" from 0.0 to 1.0
 * based on height, path complexity, size, and closure. A score ≥ 0.5 means drawing.
 *
 * After per-stroke classification, group-level heuristics catch multi-line connected
 * components and "drawing contagion" (freehand strokes adjacent to drawings/shapes).
 */
object DiagramStrokeClassifier {

    // Per-stroke thresholds (multiples of LINE_SPACING)
    private const val HEIGHT_STRONG = 1.5f      // yRange > 1.5×LS → strong drawing signal
    private const val HEIGHT_MODERATE = 1.0f     // yRange > 1.0×LS → moderate signal
    private const val COMPLEXITY_HIGH = 3.5f     // pathLength/diagonal ratio
    private const val COMPLEXITY_MODERATE = 2.5f
    private const val SIZE_THRESHOLD = 2.5f      // max(xRange,yRange) > 2.5×LS
    private const val CLOSE_FRACTION = 0.2f      // start-end distance / diagonal for closure
    private const val CLOSE_MIN_DIAGONAL = 0.5f  // minimum diagonal for closure check (×LS)
    private const val CONNECTOR_WIDTH = 2.0f     // width > 2×LS for connector filter
    private const val CONNECTOR_HEIGHT_RATIO = 0.3f // height < 30% of width

    // Score threshold
    private const val DRAWING_THRESHOLD = 0.5f

    // Group-level thresholds
    private const val GROUP_MAX_LINE_SPAN = 2.0f // group yRange > 2×LS → drawing

    /**
     * Compute a drawing score for a single stroke (0.0 = text, 1.0 = drawing).
     */
    fun classifyStroke(stroke: InkStroke, lineSpacing: Float): Float {
        if (stroke.points.size < 2) return 0f

        val w = stroke.xRange
        val h = stroke.yRange
        val pl = stroke.pathLength
        val diag = stroke.diagonal

        var score = 0f

        // A: Height relative to line spacing
        when {
            h > HEIGHT_STRONG * lineSpacing -> score += 0.6f
            h > HEIGHT_MODERATE * lineSpacing -> score += 0.3f
        }

        // B: Path complexity
        if (diag > 0f) {
            val complexity = pl / diag
            when {
                complexity > COMPLEXITY_HIGH -> score += 0.4f
                complexity > COMPLEXITY_MODERATE -> score += 0.2f
            }
        }

        // C: Large size
        if (max(w, h) > SIZE_THRESHOLD * lineSpacing) {
            score += 0.3f
        }

        // D: Closed loop
        if (diag > CLOSE_MIN_DIAGONAL * lineSpacing && stroke.points.size >= 3) {
            val first = stroke.points.first()
            val last = stroke.points.last()
            val closeDist = hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble()).toFloat()
            if (closeDist < CLOSE_FRACTION * diag) {
                score += 0.3f
            }
        }

        // E: Connector (existing heuristic, auto-drawing)
        if (w > CONNECTOR_WIDTH * lineSpacing && h < CONNECTOR_HEIGHT_RATIO * w) {
            score = 1.0f
        }

        return score.coerceAtMost(1.0f)
    }

    /**
     * Partition freehand strokes into text candidates and drawing strokes
     * using per-stroke heuristics only.
     */
    fun partitionByStroke(
        strokes: List<InkStroke>,
        lineSpacing: Float
    ): Pair<List<InkStroke>, List<InkStroke>> {
        val text = mutableListOf<InkStroke>()
        val drawing = mutableListOf<InkStroke>()
        for (stroke in strokes) {
            if (classifyStroke(stroke, lineSpacing) >= DRAWING_THRESHOLD) {
                drawing.add(stroke)
            } else {
                text.add(stroke)
            }
        }
        return Pair(text, drawing)
    }

    /**
     * Full classification pipeline: per-stroke scoring + group-level contagion.
     *
     * Groups ALL freehand strokes (text + drawing) with geometric shapes by proximity.
     * If any stroke in a group is drawing-classified or geometric, the entire group
     * becomes drawing. Also applies multi-line span filter.
     *
     * @return (textCandidates, drawingStrokes)
     */
    fun partition(
        freehandStrokes: List<InkStroke>,
        geometricStrokes: List<InkStroke>,
        lineSpacing: Float
    ): Pair<List<InkStroke>, List<InkStroke>> {
        if (freehandStrokes.isEmpty()) return Pair(emptyList(), emptyList())

        // Step 1: Per-stroke classification
        val drawingScores = freehandStrokes.associateWith { classifyStroke(it, lineSpacing) }
        val perStrokeDrawingIds = drawingScores.filter { it.value >= DRAWING_THRESHOLD }.keys.map { it.strokeId }.toSet()

        // Step 2: Proximity-group ALL strokes (freehand + geometric) for contagion
        val allStrokes = freehandStrokes + geometricStrokes
        val groups = groupByProximity(allStrokes, lineSpacing)

        // Step 3: Drawing contagion — if any stroke in a group is drawing or geometric,
        // all freehand strokes in the group become drawing
        val contagionDrawingIds = mutableSetOf<String>()
        for (group in groups) {
            val hasDrawing = group.any { stroke ->
                stroke.strokeId in perStrokeDrawingIds ||
                stroke.strokeType != StrokeType.FREEHAND  // geometric shapes
            }
            // Multi-line span check
            val groupYRange = group.maxOf { it.points.maxOf { p -> p.y } } -
                              group.minOf { it.points.minOf { p -> p.y } }
            val isMultiLine = groupYRange > GROUP_MAX_LINE_SPAN * lineSpacing

            if (hasDrawing || isMultiLine) {
                for (stroke in group) {
                    if (stroke.strokeType == StrokeType.FREEHAND) {
                        contagionDrawingIds.add(stroke.strokeId)
                    }
                }
            }
        }

        // Step 4: Partition
        val allDrawingIds = perStrokeDrawingIds + contagionDrawingIds
        val text = freehandStrokes.filter { it.strokeId !in allDrawingIds }
        val drawing = freehandStrokes.filter { it.strokeId in allDrawingIds }
        return Pair(text, drawing)
    }

    /**
     * Check if a group of strokes should be classified as text.
     * Returns false (= drawing) if the group spans more than 2 line heights.
     */
    fun isTextGroup(group: List<InkStroke>, lineSpacing: Float): Boolean {
        if (group.isEmpty()) return true
        val minY = group.minOf { it.points.minOf { p -> p.y } }
        val maxY = group.maxOf { it.points.maxOf { p -> p.y } }
        return (maxY - minY) <= GROUP_MAX_LINE_SPAN * lineSpacing
    }

    // Simple proximity grouping (mirrors DiagramTextGrouping logic)
    private fun groupByProximity(strokes: List<InkStroke>, maxGap: Float): List<List<InkStroke>> {
        if (strokes.isEmpty()) return emptyList()

        data class BBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
        val boxes = strokes.map { s ->
            BBox(s.points.minOf { it.x }, s.points.minOf { it.y },
                 s.points.maxOf { it.x }, s.points.maxOf { it.y })
        }

        val parent = IntArray(strokes.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var x = i
            while (x != r) { val n = parent[x]; parent[x] = r; x = n }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val xDist = maxOf(0f, maxOf(boxes[i].minX - boxes[j].maxX, boxes[j].minX - boxes[i].maxX))
                val yDist = maxOf(0f, maxOf(boxes[i].minY - boxes[j].maxY, boxes[j].minY - boxes[i].maxY))
                if (xDist <= maxGap && yDist <= maxGap) {
                    union(i, j)
                }
            }
        }

        return strokes.indices.groupBy { find(it) }
            .values.map { indices -> indices.map { strokes[it] } }
    }
}
