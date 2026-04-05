package com.writer.model

/**
 * Serializable snapshot for one column of a document.
 * Holds strokes, recognized text cache, hidden line tracking, and diagram areas.
 */
data class ColumnData(
    val strokes: List<InkStroke> = emptyList(),
    val lineTextCache: Map<Int, String> = emptyMap(),
    val everHiddenLines: Set<Int> = emptySet(),
    val diagramAreas: List<DiagramArea> = emptyList(),
    val textBlocks: List<TextBlock> = emptyList()
)
