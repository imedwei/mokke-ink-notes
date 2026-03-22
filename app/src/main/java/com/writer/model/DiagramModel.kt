package com.writer.model

import android.graphics.RectF

data class DiagramNode(
    val strokeId: String,
    val shapeType: StrokeType,
    val bounds: RectF,
    var label: String = ""
)

data class DiagramEdge(
    val strokeId: String,
    val fromNodeId: String?,   // null = unconnected end
    val toNodeId: String?
)

class DiagramModel {
    val nodes: MutableMap<String, DiagramNode> = mutableMapOf()  // key = strokeId
    val edges: MutableMap<String, DiagramEdge> = mutableMapOf()  // key = strokeId
}
