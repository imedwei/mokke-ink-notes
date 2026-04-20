package com.writer.model

/**
 * Serializable snapshot of a full document.
 * Holds three [ColumnData] instances (main + cue + transcript) and shared fields.
 *
 * `transcript` owns all audio-derived TextBlocks and their anchor metadata.
 * `main` and `cue` are pen-first writing surfaces with no TextBlocks (post v6 migration).
 */
data class DocumentData(
    val main: ColumnData,
    val cue: ColumnData = ColumnData(),
    val transcript: ColumnData = ColumnData(),
    val scrollOffsetY: Float = 0f,
    val highestLineIndex: Int = 0,
    val currentLineIndex: Int = 0,
    val userRenamed: Boolean = false,
    val audioRecordings: List<AudioRecording> = emptyList(),
)
