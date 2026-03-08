package com.writer.model

import java.util.UUID

data class DiagramArea(
    val id: String = UUID.randomUUID().toString(),
    val startLineIndex: Int,
    val heightInLines: Int
) {
    val endLineIndex: Int get() = startLineIndex + heightInLines - 1
    fun containsLine(lineIndex: Int) = lineIndex in startLineIndex..endLineIndex
}
