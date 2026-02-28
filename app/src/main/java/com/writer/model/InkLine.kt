package com.writer.model

import android.graphics.RectF
import java.util.UUID

data class InkLine(
    val lineId: String = UUID.randomUUID().toString(),
    var paragraphId: String? = null,
    var lineIndex: Int = 0,
    val strokes: MutableList<InkStroke> = mutableListOf(),
    var recognizedText: String? = null,
    var recognitionState: RecognitionState = RecognitionState.PENDING,
    var baselineY: Float = 0f,
    var boundingBox: RectF = RectF(),
    var isIndented: Boolean = false
) {
    fun computeBoundingBox(): RectF {
        if (strokes.isEmpty()) return RectF()
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
        boundingBox = RectF(minX, minY, maxX, maxY)
        baselineY = maxY
        return boundingBox
    }
}
