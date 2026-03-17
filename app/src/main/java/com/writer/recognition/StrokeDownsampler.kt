package com.writer.recognition

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import kotlin.math.abs
import kotlin.math.sqrt

object StrokeDownsampler {

    private const val DWELL_EPSILON = 0.5f  // sub-pixel threshold for dwell collapse

    /**
     * Collapse runs of nearly-identical (x,y) positions to first + last point.
     * Handles pen-down dwell where the device reports many points at the same location.
     */
    fun collapseIdenticalPositions(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size <= 1) return points
        val result = mutableListOf<StrokePoint>()
        var runStart = 0
        for (i in 1..points.size) {
            val samePos = i < points.size &&
                    abs(points[i].x - points[runStart].x) < DWELL_EPSILON &&
                    abs(points[i].y - points[runStart].y) < DWELL_EPSILON
            if (!samePos) {
                result.add(points[runStart])
                if (i - 1 > runStart) {
                    result.add(points[i - 1])
                }
                runStart = i
            }
        }
        return result
    }

    /**
     * Ramer-Douglas-Peucker simplification on (x,y).
     * Preserves pressure and timestamp of surviving points.
     */
    fun rdp(points: List<StrokePoint>, epsilon: Float): List<StrokePoint> {
        if (points.size <= 2) return points

        // Find the point with the maximum distance from the line (first, last)
        val first = points.first()
        val last = points.last()
        var maxDist = 0f
        var maxIdx = 0
        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }

        return if (maxDist > epsilon) {
            val left = rdp(points.subList(0, maxIdx + 1), epsilon)
            val right = rdp(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    /**
     * Full downsampling pipeline: collapse dwells then RDP simplify.
     */
    fun downsample(stroke: InkStroke, epsilon: Float = 0.5f): InkStroke {
        val collapsed = collapseIdenticalPositions(stroke.points)
        val simplified = rdp(collapsed, epsilon)
        return stroke.copy(points = simplified)
    }

    private fun perpendicularDistance(
        point: StrokePoint,
        lineStart: StrokePoint,
        lineEnd: StrokePoint
    ): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0f) {
            val px = point.x - lineStart.x
            val py = point.y - lineStart.y
            return sqrt(px * px + py * py)
        }
        val num = abs(dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
        return num / sqrt(lengthSq)
    }
}
