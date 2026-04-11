package com.writer.recognition

/**
 * Interface for offline (non-streaming) speech recognition, abstracting the
 * Sherpa-ONNX [com.k2fsa.sherpa.onnx.OfflineRecognizer] API for testability.
 *
 * Production: [SherpaOfflineRecognizerWrapper] wraps the real JNI recognizer.
 * Tests: use a fake that returns predetermined results.
 */
interface OfflineRecognizer {
    /** Decode a complete audio segment. Returns text, tokens, and timestamps. */
    fun decode(samples: FloatArray, sampleRate: Int): OfflineResult

    /** Release native resources. */
    fun release()
}

data class OfflineResult(
    val text: String,
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = floatArrayOf()
)
