package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokeType
import com.writer.model.minX
import com.writer.model.minY
import com.writer.model.maxX
import com.writer.model.maxY
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
    private const val HEIGHT_STRONG = 2.5f      // yRange > 2.5×LS → strong drawing signal (clearly multi-line)
    private const val HEIGHT_MODERATE = 2.0f    // yRange > 2.0×LS → moderate signal
    private const val COMPLEXITY_HIGH = 3.5f     // pathLength/diagonal ratio
    private const val COMPLEXITY_MODERATE = 2.5f
    private const val SIZE_THRESHOLD = 2.5f      // max(xRange,yRange) > 2.5×LS
    private const val CLOSE_FRACTION = 0.2f      // start-end distance / diagonal for closure
    private const val CLOSE_MIN_DIAGONAL = 0.5f  // minimum diagonal for closure check (×LS)
    private const val CONNECTOR_WIDTH = 2.0f     // width > 2×LS for connector filter
    private const val CONNECTOR_HEIGHT_RATIO = 0.3f // height < 30% of width
    private const val LINE_DENSITY_THRESHOLD = 0.80f // if >80% of points within 1 LS of median → text

    // Score threshold
    private const val DRAWING_THRESHOLD = 0.5f

    // Group-level thresholds
    private const val GROUP_MAX_LINE_SPAN = 2.0f // group yRange > 2×LS → drawing

    /**
     * Compute a drawing score for a single stroke (0.0 = text, 1.0 = drawing).
     * @param includeConnector whether to include the connector heuristic (wide+flat).
     *        Set to false for dwell disambiguation — cursive text is wide+flat but not a connector.
     */
    fun classifyStroke(stroke: InkStroke, lineSpacing: Float, includeConnector: Boolean = true): Float {
        if (stroke.points.size < 2) return 0f

        val w = stroke.xRange
        val h = stroke.yRange
        val pl = stroke.pathLength
        val diag = stroke.diagonal

        // Early out: if most points are concentrated within one line height,
        // this is text (possibly with ascenders/descenders that extend the
        // bounding box). Descenders on 'g', 'y', 'p' push yRange beyond 1×LS
        // but the stroke body stays on one line.
        // Skip this check for closed loops (circles/ovals are single-line-height drawings).
        if (stroke.points.size >= 10 && lineSpacing > 0f && diag > 0f) {
            val first = stroke.points.first()
            val last = stroke.points.last()
            val closeDist = hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble()).toFloat()
            val isClosed = closeDist < CLOSE_FRACTION * diag
            if (!isClosed) {
                val density = lineDensity(stroke, lineSpacing)
                if (density >= LINE_DENSITY_THRESHOLD) return 0f
            }
        }

        var score = 0f

        // A: Height relative to line spacing — diagrams span multiple lines
        when {
            h >= HEIGHT_STRONG * lineSpacing -> score += 0.6f
            h >= HEIGHT_MODERATE * lineSpacing -> score += 0.3f
        }

        // B: Path complexity — only contributes if stroke extends beyond normal
        // text height (1.5×LS). Cursive text with ascenders/descenders stays
        // within ~1.3×LS; anything taller is suspicious enough for complexity to count.
        val isTallEnough = h > 1.5f * lineSpacing
        if (diag > 0f && isTallEnough) {
            val complexity = pl / diag
            when {
                complexity > COMPLEXITY_HIGH -> score += 0.4f
                complexity > COMPLEXITY_MODERATE -> score += 0.2f
            }
        }

        // C: Large vertical size (height matters, not width — text is naturally wide)
        if (h > SIZE_THRESHOLD * lineSpacing) {
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

        // E: Connector (wide + flat → auto-drawing). Skipped for dwell disambiguation
        // because cursive text is also wide+flat.
        if (includeConnector && w > CONNECTOR_WIDTH * lineSpacing && h < CONNECTOR_HEIGHT_RATIO * w) {
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
     * Groups freehand strokes by proximity. If any freehand stroke in a group
     * is drawing-classified (per-stroke heuristics), the entire group becomes
     * drawing. Geometric shapes do NOT trigger contagion — text labels are
     * often inside or adjacent to shapes and must remain as text.
     * Also applies multi-line span filter.
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

        // Step 2: Proximity-group FREEHAND strokes only for contagion.
        // Geometric shapes are NOT included — text labels near shapes must stay text.
        val groups = groupByProximity(freehandStrokes, lineSpacing)

        // Step 3: Drawing contagion — if any freehand stroke in a group is
        // drawing-classified, all freehand strokes in the group become drawing.
        val contagionDrawingIds = mutableSetOf<String>()
        for (group in groups) {
            val hasDrawing = group.any { stroke ->
                stroke.strokeId in perStrokeDrawingIds
            }
            // Multi-line span check
            val groupYRange = group.maxOf { it.maxY } -
                              group.minOf { it.minY }
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
        val minY = group.minOf { it.minY }
        val maxY = group.maxOf { it.maxY }
        return (maxY - minY) <= GROUP_MAX_LINE_SPAN * lineSpacing
    }

    /**
     * Compute what fraction of a stroke's points are within one line spacing
     * of the Y median. High density (>0.80) means the stroke body is on one
     * line — outlier points are ascenders/descenders, not multi-line drawing.
     */
    private fun lineDensity(stroke: InkStroke, lineSpacing: Float): Float {
        val ys = stroke.points.map { it.y }
        val sorted = ys.sorted()
        val medianY = sorted[sorted.size / 2]
        val halfLine = lineSpacing * 0.6f  // slightly generous to cover full letter body
        val within = ys.count { kotlin.math.abs(it - medianY) <= halfLine }
        return within.toFloat() / ys.size
    }

    // Grid-based proximity grouping — O(n) average instead of O(n²).
    private fun groupByProximity(strokes: List<InkStroke>, maxGap: Float): List<List<InkStroke>> =
        SpatialGrouping.groupByProximity(strokes, maxGap) { s -> BBox(s.minX, s.minY, s.maxX, s.maxY) }
}
