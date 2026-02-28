package com.writer.model

import java.util.UUID

class DocumentModel(
    val documentId: String = UUID.randomUUID().toString(),
    var language: String = "en-US"
) {
    val paragraphs: MutableList<Paragraph> = mutableListOf()
    val activeStrokes: MutableList<InkStroke> = mutableListOf()
    var scrollOffsetY: Float = 0f

    fun getCurrentParagraph(): Paragraph {
        if (paragraphs.isEmpty()) {
            val p = Paragraph(paragraphIndex = 0)
            paragraphs.add(p)
        }
        return paragraphs.last()
    }

    fun startNewParagraph(): Paragraph {
        val p = Paragraph(paragraphIndex = paragraphs.size)
        paragraphs.add(p)
        return p
    }

    fun getFullText(): String {
        return paragraphs.joinToString("\n\n") { it.text }
    }

    fun getAllInkLines(): List<InkLine> {
        return paragraphs.flatMap { it.inkLines }
    }
}
