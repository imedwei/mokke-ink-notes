package com.writer.model

data class DocumentData(
    val strokes: List<InkStroke>,
    val scrollOffsetY: Float,
    val lineTextCache: Map<Int, String>,
    val everHiddenLines: Set<Int>,
    val highestLineIndex: Int,
    val currentLineIndex: Int,
    val userRenamed: Boolean = false,
    val diagramAreas: List<DiagramArea> = emptyList()
)
