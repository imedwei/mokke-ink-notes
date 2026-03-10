package com.writer.model

val InkStroke.minX: Float get() = points.minOf { it.x }
val InkStroke.maxX: Float get() = points.maxOf { it.x }
val InkStroke.minY: Float get() = points.minOf { it.y }
val InkStroke.maxY: Float get() = points.maxOf { it.y }
val InkStroke.xRange: Float get() = maxX - minX
val InkStroke.yRange: Float get() = maxY - minY

val InkStroke.pathLength: Float get() = points.zipWithNext { a, b ->
    val dx = b.x - a.x
    val dy = b.y - a.y
    kotlin.math.sqrt(dx * dx + dy * dy)
}.sum()

val InkStroke.diagonal: Float get() {
    val w = xRange
    val h = yRange
    return kotlin.math.sqrt(w * w + h * h)
}

fun InkStroke.shiftY(dy: Float): InkStroke {
    val shiftedPoints = points.map { pt ->
        StrokePoint(pt.x, pt.y + dy, pt.pressure, pt.timestamp)
    }
    return InkStroke(
        strokeId = strokeId,
        points = shiftedPoints,
        strokeWidth = strokeWidth,
        isGeometric = isGeometric,
        strokeType = strokeType
    )
}
