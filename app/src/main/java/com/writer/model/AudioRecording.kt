package com.writer.model

data class AudioRecording(
    val audioFile: String = "",
    val startTimeMs: Long = 0,
    val durationMs: Long = 0
)
