package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokeType

/**
 * Pure logic for filtering out text lines that are entirely inside diagram shape bounds.
 *
 * Strokes written inside a shape are recognized as the shape's label (shown in the diagram
 * preview) and must not also appear as independent text paragraphs.
 */
internal object DiagramTextFilter {

    /**
     * Returns the set of line indices whose freehand strokes all lie inside a diagram node.
     *
     * A line is excluded when every [StrokeType.FREEHAND] stroke on it has its center
     * contained within at least one node's bounding box. Lines that have no freehand
     * strokes at all are also excluded (they carry no text content).
     *
     * @param strokesByLine  map from line index to all strokes on that line
     * @param nodeBounds     node bounding boxes as [left, top, right, bottom] float arrays
     * @return set of line indices to suppress from the text preview
     */
    fun diagramOnlyLines(
        strokesByLine: Map<Int, List<InkStroke>>,
        nodeBounds: List<FloatArray>
    ): Set<Int> {
        val validBounds = nodeBounds.filter { it.size == 4 }
        if (validBounds.isEmpty()) return emptySet()
        return strokesByLine.entries
            .filter { (_, strokes) ->
                val freehand = strokes.filter { it.strokeType == StrokeType.FREEHAND }
                // No freehand strokes → no text on this line
                if (freehand.isEmpty()) return@filter true
                // All freehand strokes must be inside some node
                freehand.all { stroke ->
                    val cx = (stroke.points.minOf { it.x } + stroke.points.maxOf { it.x }) / 2f
                    val cy = (stroke.points.minOf { it.y } + stroke.points.maxOf { it.y }) / 2f
                    validBounds.any { (l, t, r, b) -> cx >= l && cx <= r && cy >= t && cy <= b }
                }
            }
            .map { it.key }
            .toSet()
    }

    /**
     * Returns line indices whose freehand strokes all lie in the diagram's Y-band
     * but outside every node's bounds — i.e. "beside" the diagram, not inside a shape.
     *
     * @param strokesByLine  map from line index to strokes
     * @param nodeBounds     node bounds as [left, top, right, bottom]
     * @param diagramBBox    overall diagram bounding box as [left, top, right, bottom]
     * @param yTolerance     Y padding around the diagram bbox (pass LINE_SPACING)
     */
    /**
     * @param rightBandLimit  upper X bound for "right-side" band notes.  Strokes whose
     *   center X exceeds this value are treated as ordinary text, not diagram notes.
     *   Pass `canvasWidth − 2 × gutterWidth` to exclude the rightmost gutter-zone column
     *   (fixes Bug #6: text near the right margin wrongly suppressed after a shape is drawn).
     *   Defaults to [Float.MAX_VALUE] (no upper bound — original behaviour).
     */
    fun diagramBandLines(
        strokesByLine: Map<Int, List<InkStroke>>,
        nodeBounds: List<FloatArray>,
        diagramBBox: FloatArray,
        yTolerance: Float,
        leftBandLimit: Float = diagramBBox[0] - yTolerance * 4,
        rightBandLimit: Float = diagramBBox[2] + yTolerance * 4
    ): Set<Int> {
        if (diagramBBox.size != 4) return emptySet()
        val validNodeBounds = nodeBounds.filter { it.size == 4 }
        val bLeft = diagramBBox[0]; val bTop = diagramBBox[1]
        val bRight = diagramBBox[2]; val bBottom = diagramBBox[3]
        return strokesByLine.entries
            .filter { (_, strokes) ->
                val freehand = strokes.filter { it.strokeType == StrokeType.FREEHAND }
                if (freehand.isEmpty()) return@filter false  // no text on this line
                // Exclude shape-label strokes (inside a node) — they are already handled by
                // diagramOnlyLines. Only the remaining strokes need to qualify as band notes.
                // This allows "side" strokes to be detected even when a shape label (e.g. "A")
                // happens to share the same written line index.
                val nonLabel = freehand.filter { stroke ->
                    val cx = (stroke.points.minOf { it.x } + stroke.points.maxOf { it.x }) / 2f
                    val cy = (stroke.points.minOf { it.y } + stroke.points.maxOf { it.y }) / 2f
                    validNodeBounds.none { (l, t, r, b) -> cx >= l && cx <= r && cy >= t && cy <= b }
                }
                if (nonLabel.isEmpty()) return@filter false  // pure shape-label line
                nonLabel.all { stroke ->
                    val cx = (stroke.points.minOf { it.x } + stroke.points.maxOf { it.x }) / 2f
                    val cy = (stroke.points.minOf { it.y } + stroke.points.maxOf { it.y }) / 2f
                    val inYBand = cy >= bTop - yTolerance && cy <= bBottom + yTolerance
                    // "Right-side" band notes must not extend past the rightmost gutter zone.
                    val outsideX = (cx < bLeft && cx > leftBandLimit) || (cx > bRight && cx < rightBandLimit)
                    inYBand && outsideX
                }
            }
            .map { it.key }
            .toSet()
    }
}
