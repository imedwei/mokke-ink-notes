package com.writer.model

/**
 * Per-word metadata from speech recognition.
 * Populated by Whisper (with confidence + timestamps),
 * empty for SpeechRecognizer results.
 */
data class WordInfo(
    val text: String,
    val confidence: Float = 1f,  // 0.0 - 1.0
    val startMs: Long = 0,       // audio timestamp
    val endMs: Long = 0
)
