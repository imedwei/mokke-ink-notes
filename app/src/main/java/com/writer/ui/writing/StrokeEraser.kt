package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minX
import com.writer.model.minY
import com.writer.model.maxX
import com.writer.model.maxY
import com.writer.view.ScratchOutDetection

/**
 * Pure-logic scratch-out erase operations: finding which strokes a scratch-out
 * overlaps, and expanding to connected-word boundaries.
 *
 * Extracted from [WritingCoordinator.onScratchOut] for testability.
 * No Android view dependencies — all inputs are primitives and model objects.
 */
object StrokeEraser {

    /**
     * Find strokes that a scratch-out physically intersects.
     *
     * For small strokes (< 5 points), uses proximity check within [radiusPx].
     * For larger strokes, uses segment intersection via [ScratchOutDetection].
     * Connector strokes (arrows) also match if the scratch bounding box
     * intersects their path.
     */
    fun findOverlappingStrokes(
        scratchPoints: List<StrokePoint>,
        allStrokes: List<InkStroke>,
        left: Float, top: Float, right: Float, bottom: Float,
        radiusPx: Float
    ): List<InkStroke> {
        val radiusSq = radiusPx * radiusPx
        return allStrokes.filter { stroke ->
            val sMinX = stroke.minX; val sMaxX = stroke.maxX
            val sMinY = stroke.minY; val sMaxY = stroke.maxY
            if (sMaxX < left - radiusPx || sMinX > right + radiusPx ||
                sMaxY < top - radiusPx || sMinY > bottom + radiusPx) return@filter false

            if (stroke.points.size < 5) {
                stroke.points.any { tp ->
                    scratchPoints.any { sp ->
                        val dx = sp.x - tp.x; val dy = sp.y - tp.y
                        dx * dx + dy * dy <= radiusSq
                    }
                }
            } else {
                ScratchOutDetection.strokesIntersect(scratchPoints, stroke.points)
            } || stroke.strokeType.isConnector
                    && ScratchOutDetection.strokeIntersectsRect(stroke.points, left, top, right, bottom)
        }
    }

    /**
     * Expand a set of directly-overlapping strokes to include the full connected
     * word within the scratch-out region.
     *
     * A stroke is included if it's on the same line, overlaps or is near the
     * scratch-out bounding box, and was written close in time. This ensures
     * e.g. the crossbar of 't' is removed along with the rest of "entry",
     * but doesn't jump to adjacent words outside the scratch-out region.
     *
     * @param getStrokeLineIndex maps a stroke to its line index (avoids LineSegmenter dependency)
     */
    fun expandToConnectedWord(
        directHits: List<InkStroke>,
        allStrokes: List<InkStroke>,
        scratchLeft: Float, scratchTop: Float,
        scratchRight: Float, scratchBottom: Float,
        lineSpacing: Float,
        getStrokeLineIndex: (InkStroke) -> Int
    ): List<InkStroke> {
        if (directHits.isEmpty()) return directHits

        val margin = lineSpacing * 0.5f
        val maxTimeGap = 2000L

        val hitLines = directHits.map { getStrokeLineIndex(it) }.toSet()

        val candidates = allStrokes.filter { stroke ->
            getStrokeLineIndex(stroke) in hitLines &&
                stroke.maxX >= scratchLeft - margin &&
                stroke.minX <= scratchRight + margin &&
                stroke.maxY >= scratchTop - margin &&
                stroke.minY <= scratchBottom + margin
        }

        val included = directHits.map { it.strokeId }.toMutableSet()
        for (candidate in candidates) {
            if (candidate.strokeId in included) continue
            val isTemporallyConnected = directHits.any { hit ->
                kotlin.math.abs(candidate.startTime - hit.endTime) < maxTimeGap ||
                    kotlin.math.abs(hit.startTime - candidate.endTime) < maxTimeGap
            }
            if (isTemporallyConnected) {
                included.add(candidate.strokeId)
            }
        }

        return allStrokes.filter { it.strokeId in included }
    }
}
