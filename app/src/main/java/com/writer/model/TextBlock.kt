package com.writer.model

import java.util.UUID

data class TextBlock(
    val id: String = UUID.randomUUID().toString(),
    val startLineIndex: Int,
    val heightInLines: Int,
    val text: String = "",
    val audioFile: String = "",
    val audioStartMs: Long = 0,
    val audioEndMs: Long = 0,
    val words: List<WordInfo> = emptyList()
) {
    val endLineIndex: Int get() = startLineIndex + heightInLines - 1
    fun containsLine(lineIndex: Int) = lineIndex in startLineIndex..endLineIndex
}
