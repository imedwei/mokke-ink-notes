package com.writer.model

class DocumentModel(
    var language: String = "en-US"
) {
    val activeStrokes: MutableList<InkStroke> = mutableListOf()
    val diagramAreas: MutableList<DiagramArea> = mutableListOf()
    val diagram: DiagramModel = DiagramModel()
}
