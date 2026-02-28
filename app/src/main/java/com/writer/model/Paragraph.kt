package com.writer.model

import java.util.UUID

data class Paragraph(
    val paragraphId: String = UUID.randomUUID().toString(),
    var paragraphIndex: Int = 0,
    val inkLines: MutableList<InkLine> = mutableListOf(),
    var text: String = ""
) {
    fun appendLine(line: InkLine) {
        line.paragraphId = paragraphId
        line.lineIndex = inkLines.size
        inkLines.add(line)
        rebuildText()
    }

    fun rebuildText() {
        text = inkLines
            .filter { it.recognizedText != null }
            .joinToString(" ") { it.recognizedText!! }
    }
}
