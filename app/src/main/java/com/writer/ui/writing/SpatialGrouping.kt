package com.writer.ui.writing

/** Bounding box for spatial proximity grouping. */
data class BBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/** Grid-based proximity grouping using union-find. O(n) average. */
object SpatialGrouping {
    fun <T> groupByProximity(
        items: List<T>,
        maxGap: Float,
        toBBox: (T) -> BBox
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()

        val boxes = items.map { toBBox(it) }

        // Union-find
        val parent = IntArray(items.size) { it }
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
        // Boxes in the same cell are candidates for union -- O(n) average.
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

        return items.indices.groupBy { find(it) }
            .values.map { indices -> indices.map { items[it] } }
    }
}
