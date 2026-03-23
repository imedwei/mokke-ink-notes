package com.writer.ui.writing

import com.writer.model.InkStroke

/**
 * Groups freehand diagram strokes by 2D spatial proximity, then splits
 * groups into horizontal rows for line-based recognition.
 *
 * Extracted from [WritingCoordinator.recognizeDiagramArea] for testability.
 */
object DiagramTextGrouping {

    /**
     * A bounding box for a stroke.
     */
    data class BBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    /**
     * Group strokes by 2D spatial proximity. Strokes whose bounding boxes
     * are within [maxGap] of each other (in both X and Y) are merged into
     * the same group using union-find.
     *
     * @return list of stroke groups (each group is a list of strokes)
     */
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
                BBox(s.points.minOf { it.x }, s.points.minOf { it.y },
                     s.points.maxOf { it.x }, s.points.maxOf { it.y })
            }

            // Assign each freehand stroke to a shape (by centroid containment), or -1
            val assignments = strokes.map { s ->
                val cx = (s.points.minOf { it.x } + s.points.maxOf { it.x }) / 2f
                val cy = (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f
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
    private fun groupByProximitySimple(strokes: List<InkStroke>, maxGap: Float): List<List<InkStroke>> {
        if (strokes.isEmpty()) return emptyList()

        val boxes = strokes.map { s ->
            BBox(s.points.minOf { it.x }, s.points.minOf { it.y },
                 s.points.maxOf { it.x }, s.points.maxOf { it.y })
        }

        // Union-find
        val parent = IntArray(strokes.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var x = i
            while (x != r) { val n = parent[x]; parent[x] = r; x = n }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        // Grid-based spatial index: cell size = maxGap.
        // Each bbox is inserted into all cells it overlaps (expanded by maxGap).
        // Boxes in the same cell are candidates for union — O(n) average.
        val cellSize = maxGap.coerceAtLeast(1f)
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellKey(cx: Int, cy: Int) = cx.toLong() shl 32 or (cy.toLong() and 0xFFFFFFFFL)

        for (i in boxes.indices) {
            val b = boxes[i]
            val cxMin = ((b.minX - maxGap) / cellSize).toInt()
            val cxMax = ((b.maxX + maxGap) / cellSize).toInt()
            val cyMin = ((b.minY - maxGap) / cellSize).toInt()
            val cyMax = ((b.maxY + maxGap) / cellSize).toInt()
            for (cx in cxMin..cxMax) {
                for (cy in cyMin..cyMax) {
                    val key = cellKey(cx, cy)
                    val cell = grid.getOrPut(key) { mutableListOf() }
                    // Check proximity with existing entries in this cell
                    for (j in cell) {
                        if (find(i) == find(j)) continue  // already merged
                        val xDist = maxOf(0f, maxOf(boxes[i].minX - boxes[j].maxX, boxes[j].minX - boxes[i].maxX))
                        val yDist = maxOf(0f, maxOf(boxes[i].minY - boxes[j].maxY, boxes[j].minY - boxes[i].maxY))
                        if (xDist <= maxGap && yDist <= maxGap) {
                            union(i, j)
                        }
                    }
                    cell.add(i)
                }
            }
        }

        return strokes.indices.groupBy { find(it) }
            .values.map { indices -> indices.map { strokes[it] } }
    }

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
            (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f
        }

        val rows = mutableListOf(mutableListOf(byY.first()))
        for (i in 1 until byY.size) {
            val prevMaxY = rows.last().maxOf { s -> s.points.maxOf { it.y } }
            val curMinY = byY[i].points.minOf { it.y }
            if (curMinY - prevMaxY > maxGap) {
                rows.add(mutableListOf(byY[i]))
            } else {
                rows.last().add(byY[i])
            }
        }

        return rows.map { row -> row.sortedBy { s -> s.points.minOf { it.x } } }
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
        val heights = group.map { s ->
            s.points.maxOf { it.y } - s.points.minOf { it.y }
        }.filter { it > 0f }.sorted()

        if (heights.isEmpty()) return splitIntoRows(group, fallbackGap)

        val medianHeight = heights[heights.size / 2]

        // Use centroid-based clustering: sort by Y-centroid, split when
        // consecutive centroids are more than 0.8 * medianHeight apart
        val centroidGap = medianHeight * 0.8f

        data class StrokeWithCentroid(val stroke: InkStroke, val centroidY: Float)
        val sorted = group.map { s ->
            StrokeWithCentroid(s, (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f)
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

        return rows.map { row -> row.sortedBy { s -> s.points.minOf { it.x } } }
    }
}
