package com.writer.model

import java.util.UUID

data class InkStroke(
    val strokeId: String = UUID.randomUUID().toString(),
    val points: List<StrokePoint>,
    val strokeWidth: Float = 3f,
    val startTime: Long = points.firstOrNull()?.timestamp ?: 0L,
    val endTime: Long = points.lastOrNull()?.timestamp ?: 0L,
    /** True for snapped geometric shapes (rectangle, triangle) rendered with sharp lineTo corners. */
    val isGeometric: Boolean = false,
    /** Stroke type for diagram model and arrow rendering. */
    val strokeType: StrokeType = StrokeType.FREEHAND
)
