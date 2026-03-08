package com.writer.view

import com.writer.model.StrokePoint
import com.writer.model.StrokeType

object ArrowDwellDetection {

    /**
     * Returns true if the last points in [pts] clustered within [radiusPx] of ([ex],[ey])
     * for at least [dwellMs] milliseconds — indicating a deliberate dwell/pause at the tip.
     */
    fun hasDwellAtEnd(
        pts: List<StrokePoint>,
        ex: Float, ey: Float,
        radiusPx: Float,
        dwellMs: Long
    ): Boolean {
        if (pts.isEmpty()) return false
        val r2 = radiusPx * radiusPx
        val endTime = pts.last().timestamp
        var startTime = endTime
        for (pt in pts.asReversed()) {
            val dx = pt.x - ex; val dy = pt.y - ey
            if (dx * dx + dy * dy > r2) break
            startTime = pt.timestamp
        }
        return endTime - startTime >= dwellMs
    }

    /**
     * Returns true if [pts] started within [radiusPx] of its first point for at least [dwellMs] ms.
     * Used to check for a start-of-stroke dwell (the pen paused before beginning to move).
     */
    fun hasDwellAtStart(
        pts: List<StrokePoint>,
        radiusPx: Float,
        dwellMs: Long
    ): Boolean {
        if (pts.isEmpty()) return false
        val r2 = radiusPx * radiusPx
        val first = pts.first()
        val startTime = first.timestamp
        var endTime = startTime
        for (pt in pts) {
            val dx = pt.x - first.x; val dy = pt.y - first.y
            if (dx * dx + dy * dy > r2) break
            endTime = pt.timestamp
        }
        return endTime - startTime >= dwellMs
    }

    /**
     * Classify the arrow type from dwell flags.
     */
    fun classifyArrow(tipDwell: Boolean, tailDwell: Boolean): StrokeType = when {
        tipDwell && tailDwell -> StrokeType.ARROW_BOTH
        tipDwell              -> StrokeType.ARROW_HEAD
        tailDwell             -> StrokeType.ARROW_TAIL
        else                  -> StrokeType.LINE
    }
}
