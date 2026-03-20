package com.writer.view

import com.writer.model.DiagramModel
import com.writer.model.StrokeType

object DiagramMarkdown {

    /**
     * Builds the Mermaid graph block from a [DiagramModel] and a map of
     * strokeId → [StrokeType] for edge arrow classification.
     *
     * Returns an empty string if the diagram has no nodes.
     */
    fun buildMermaidBlock(
        diagram: DiagramModel,
        edgeTypes: Map<String, StrokeType> = emptyMap(),
        direction: String = "TD",
        notes: List<String> = emptyList()
    ): String {
        if (diagram.nodes.isEmpty()) return ""

        val nodeIds = diagram.nodes.keys.mapIndexed { i, key -> key to "N${i + 1}" }.toMap()
        val sb = StringBuilder()
        sb.appendLine("graph $direction")

        val connectedNodeIds = diagram.edges.values
            .flatMap { listOfNotNull(it.fromNodeId, it.toNodeId) }.toSet()

        // Orphan nodes first
        for (nodeId in diagram.nodes.keys.filter { it !in connectedNodeIds }) {
            val node = diagram.nodes[nodeId] ?: continue
            val nId = nodeIds[nodeId] ?: nodeId
            sb.appendLine("    ${nodeLabel(nId, sanitizeLabel(node.label), node.shapeType)}")
        }

        // Edges with inline node definitions
        for (edge in diagram.edges.values) {
            val fromNode = diagram.nodes[edge.fromNodeId]
            val toNode = diagram.nodes[edge.toNodeId]
            val fnId = edge.fromNodeId?.let { nodeIds[it] } ?: continue
            val tnId = edge.toNodeId?.let { nodeIds[it] } ?: continue
            val conn = connector(edgeTypes[edge.strokeId])
            val fromMermaid = if (fromNode != null)
                nodeLabel(fnId, sanitizeLabel(fromNode.label), fromNode.shapeType) else fnId
            val toMermaid = if (toNode != null)
                nodeLabel(tnId, sanitizeLabel(toNode.label), toNode.shapeType) else tnId
            sb.appendLine("    $fromMermaid $conn $toMermaid")
        }

        // Diagram band notes as styled floating nodes
        for ((i, note) in notes.withIndex()) {
            val nId = "NOTE${i + 1}"
            sb.appendLine("    $nId[\"↳ ${sanitizeLabel(note)}\"]")
            sb.appendLine("    style $nId fill:#FFFDE7,stroke:#F9A825,stroke-dasharray: 4 4,font-style:italic")
        }

        return "```mermaid\n${sb.toString().trimEnd()}\n```"
    }

    /** Returns the Mermaid node declaration syntax for a given shape type. */
    fun nodeLabel(id: String, label: String, shape: StrokeType): String = when (shape) {
        StrokeType.RECTANGLE         -> "$id[$label]"
        StrokeType.ROUNDED_RECTANGLE -> "$id($label)"
        StrokeType.ELLIPSE           -> "$id(($label))"
        StrokeType.DIAMOND           -> "$id{$label}"
        StrokeType.TRIANGLE          -> "$id{{$label}}"
        else                         -> "$id[$label]"
    }

    /** Returns the Mermaid edge connector for an arrow stroke type. */
    fun connector(strokeType: StrokeType?): String = when (strokeType) {
        StrokeType.ARROW_BOTH -> "<-->"
        StrokeType.ARROW_TAIL -> "<--"
        StrokeType.LINE       -> "---"
        else                  -> "-->"
    }

    /** Sanitize a label for Mermaid: strip special characters. */
    fun sanitizeLabel(label: String): String =
        label.replace(Regex("[\\[\\](){}\"<>]"), "").trim()
}
