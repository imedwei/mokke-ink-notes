package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.minY
import com.writer.model.maxX
import com.writer.model.maxY
import com.writer.model.yRange

/**
 * Groups freehand diagram strokes by 2D spatial proximity, then splits
 * groups into horizontal rows for line-based recognition.
 *
 * Extracted from [WritingCoordinator.recognizeDiagramArea] for testability.
 */
object DiagramTextGrouping {

    /**
     * Group strokes by 2D spatial proximity. Strokes whose bounding boxes
     * are within [maxGap] of each other (in both X and Y) are merged into
     * the same group using union-find.
     *
     * If [shapeStrokes] is provided, strokes are first partitioned by which
     * shape contains them (centroid inside shape bounding box). Strokes in
     * different shapes are never grouped together, regardless of distance.
     * Strokes not inside any shape are grouped by proximity with each other.
     *
     * @return list of stroke groups (each group is a list of strokes)
     */
    fun groupByProximity(
        strokes: List<InkStroke>,
        maxGap: Float,
        shapeStrokes: List<InkStroke> = emptyList()
    ): List<List<InkStroke>> {
        if (strokes.isEmpty()) return emptyList()

        // If shapes are available, partition freehand strokes by containment
        if (shapeStrokes.isNotEmpty()) {
            val shapeBounds = shapeStrokes.map { s ->
                BBox(s.minX, s.minY, s.maxX, s.maxY)
            }

            // Assign each freehand stroke to a shape (by centroid containment), or -1
            val assignments = strokes.map { s ->
                val cx = (s.minX + s.maxX) / 2f
                val cy = (s.minY + s.maxY) / 2f
                shapeBounds.indexOfFirst { b ->
                    cx >= b.minX && cx <= b.maxX && cy >= b.minY && cy <= b.maxY
                }
            }

            // Group by shape assignment, then proximity-group unassigned strokes
            val result = mutableListOf<List<InkStroke>>()
            val byShape = strokes.indices.groupBy { assignments[it] }
            for ((shapeIdx, indices) in byShape) {
                val group = indices.map { strokes[it] }
                if (shapeIdx >= 0) {
                    // All strokes in the same shape form one group
                    result.add(group)
                } else {
                    // Unassigned strokes: group by proximity
                    result.addAll(groupByProximitySimple(group, maxGap))
                }
            }
            return result
        }

        return groupByProximitySimple(strokes, maxGap)
    }

    /** Simple proximity grouping without shape awareness. */
    private fun groupByProximitySimple(strokes: List<InkStroke>, maxGap: Float): List<List<InkStroke>> =
        SpatialGrouping.groupByProximity(strokes, maxGap) { s -> BBox(s.minX, s.minY, s.maxX, s.maxY) }

    /**
     * Split a group of strokes into horizontal rows for recognition.
     * A new row starts when there is a vertical gap > [maxGap] between
     * the bottom of one stroke cluster and the top of the next.
     *
     * This keeps tall letters (like "A" spanning two lines) together
     * while separating vertically stacked text blocks (like "Side" / "Text").
     *
     * @return list of rows, each row is a list of strokes sorted left-to-right
     */
    fun splitIntoRows(group: List<InkStroke>, maxGap: Float): List<List<InkStroke>> {
        if (group.isEmpty()) return emptyList()

        val byY = group.sortedBy { s ->
            (s.minY + s.maxY) / 2f
        }

        val rows = mutableListOf(mutableListOf(byY.first()))
        for (i in 1 until byY.size) {
            val prevMaxY = rows.last().maxOf { s -> s.maxY }
            val curMinY = byY[i].minY
            if (curMinY - prevMaxY > maxGap) {
                rows.add(mutableListOf(byY[i]))
            } else {
                rows.last().add(byY[i])
            }
        }

        return rows.map { row -> row.sortedBy { s -> s.minX } }
    }

    /**
     * Adaptive row splitting using median stroke height as the baseline reference.
     *
     * Instead of a fixed gap threshold, estimates the handwriting's x-height from
     * the median stroke height, then uses Y-centroid clustering to group strokes
     * into rows. This adapts to the user's writing size and handles:
     * - Tall letters (like "A") that span two ruled lines → stay as one row
     * - Stacked text blocks ("Side" / "Text") → split into separate rows
     *
     * Falls back to [splitIntoRows] with [fallbackGap] for groups with fewer
     * than 3 strokes (insufficient data for reliable median estimation).
     *
     * @param group strokes to split
     * @param fallbackGap gap threshold for small groups
     * @return list of rows, each sorted left-to-right
     */
    fun splitIntoRowsAdaptive(group: List<InkStroke>, fallbackGap: Float): List<List<InkStroke>> {
        if (group.isEmpty()) return emptyList()
        if (group.size < 3) return splitIntoRows(group, fallbackGap)

        // Compute median stroke height as x-height proxy
        val heights = group.map { s -> s.yRange }.filter { it > 0f }.sorted()

        if (heights.isEmpty()) return splitIntoRows(group, fallbackGap)

        val medianHeight = heights[heights.size / 2]

        // Use centroid-based clustering: sort by Y-centroid, split when
        // consecutive centroids are more than 0.8 * medianHeight apart
        val centroidGap = medianHeight * 0.8f

        data class StrokeWithCentroid(val stroke: InkStroke, val centroidY: Float)
        val sorted = group.map { s ->
            // Use center of mass (not bounding box centroid) — handles tall capitals
            // like "E" in "Empathy" that have a different bounding box center than
            // adjacent lowercase letters but similar center of mass.
            val yMean = s.points.sumOf { it.y.toDouble() }.toFloat() / s.points.size
            StrokeWithCentroid(s, yMean)
        }.sortedBy { it.centroidY }

        val rows = mutableListOf(mutableListOf(sorted.first().stroke))
        var lastCentroid = sorted.first().centroidY
        for (i in 1 until sorted.size) {
            if (sorted[i].centroidY - lastCentroid > centroidGap) {
                rows.add(mutableListOf(sorted[i].stroke))
            } else {
                rows.last().add(sorted[i].stroke)
            }
            // Update lastCentroid to the max centroid in current row for stability
            lastCentroid = maxOf(lastCentroid, sorted[i].centroidY)
        }

        return rows.map { row -> row.sortedBy { s -> s.minX } }
    }
}
