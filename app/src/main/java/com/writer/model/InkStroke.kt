package com.writer.model

import java.util.UUID

data class InkStroke(
    val strokeId: String = UUID.randomUUID().toString(),
    val points: List<StrokePoint>,
    val strokeWidth: Float = 3f,
    val startTime: Long = points.firstOrNull()?.timestamp ?: 0L,
    val endTime: Long = points.lastOrNull()?.timestamp ?: 0L
)
