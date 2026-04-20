package com.writer.model

import java.util.UUID

/** Which stroke column this TextBlock's audio strip renders into. */
enum class AnchorTarget { MAIN, CUE }

/** AUTO = computed from audio↔stroke correlation. MANUAL = user-dragged, sticky. */
enum class AnchorMode { AUTO, MANUAL }

data class TextBlock(
    val id: String = UUID.randomUUID().toString(),
    val startLineIndex: Int,
    val heightInLines: Int,
    val text: String = "",
    val audioFile: String = "",
    val audioStartMs: Long = 0,
    val audioEndMs: Long = 0,
    val words: List<WordInfo> = emptyList(),
    val anchorLineIndex: Int = -1,
    val anchorTarget: AnchorTarget = AnchorTarget.MAIN,
    val anchorMode: AnchorMode = AnchorMode.AUTO,
) {
    val endLineIndex: Int get() = startLineIndex + heightInLines - 1
    fun containsLine(lineIndex: Int) = lineIndex in startLineIndex..endLineIndex
}
