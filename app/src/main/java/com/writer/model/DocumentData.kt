package com.writer.model

/**
 * Serializable snapshot of a full document.
 * Holds two [ColumnData] instances (main + cue) and shared fields.
 */
data class DocumentData(
    val main: ColumnData,
    val cue: ColumnData = ColumnData(),
    val scrollOffsetY: Float = 0f,
    val highestLineIndex: Int = 0,
    val currentLineIndex: Int = 0,
    val userRenamed: Boolean = false,
    val audioRecordings: List<AudioRecording> = emptyList()
)
