package com.writer.model

/**
 * Runtime state for one column of a document.
 * Holds mutable stroke and diagram collections.
 */
class ColumnModel {
    val activeStrokes: MutableList<InkStroke> = mutableListOf()
    val diagramAreas: MutableList<DiagramArea> = mutableListOf()
    val diagram: DiagramModel = DiagramModel()
}
