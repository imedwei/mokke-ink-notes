package com.writer.recognition

/**
 * Interface for streaming speech recognition, abstracting the Sherpa-ONNX
 * [OnlineRecognizer] API for testability.
 *
 * Production: [SherpaRecognizerWrapper] wraps the real JNI recognizer.
 * Tests: [FakeStreamingRecognizer] produces predetermined results.
 */
interface StreamingRecognizer {
    fun createStream(): RecognizerStream
    fun isReady(stream: RecognizerStream): Boolean
    fun decode(stream: RecognizerStream)
    fun isEndpoint(stream: RecognizerStream): Boolean
    fun getResult(stream: RecognizerStream): RecognitionResult
    fun reset(stream: RecognizerStream)
    fun release()
}

interface RecognizerStream {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun inputFinished()
    fun release()
}

data class RecognitionResult(
    val text: String,
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = floatArrayOf()
)
