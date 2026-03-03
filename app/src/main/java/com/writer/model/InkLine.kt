package com.writer.model

import android.graphics.RectF

data class InkLine(
    val strokes: List<InkStroke>,
    val boundingBox: RectF
) {
    companion object {
        fun build(strokes: List<InkStroke>): InkLine {
            if (strokes.isEmpty()) return InkLine(strokes, RectF())
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (stroke in strokes) {
                for (point in stroke.points) {
                    if (point.x < minX) minX = point.x
                    if (point.y < minY) minY = point.y
                    if (point.x > maxX) maxX = point.x
                    if (point.y > maxY) maxY = point.y
                }
            }
            return InkLine(strokes, RectF(minX, minY, maxX, maxY))
        }
    }
}
