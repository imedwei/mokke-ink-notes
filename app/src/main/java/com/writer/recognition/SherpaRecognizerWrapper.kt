package com.writer.recognition

import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream

/**
 * Wraps [OnlineRecognizer] (JNI) to implement [StreamingRecognizer].
 */
class SherpaRecognizerWrapper(private val recognizer: OnlineRecognizer) : StreamingRecognizer {

    override fun createStream(): RecognizerStream =
        SherpaStreamWrapper(recognizer, recognizer.createStream())

    override fun isReady(stream: RecognizerStream): Boolean =
        recognizer.isReady((stream as SherpaStreamWrapper).stream)

    override fun decode(stream: RecognizerStream) =
        recognizer.decode((stream as SherpaStreamWrapper).stream)

    override fun isEndpoint(stream: RecognizerStream): Boolean =
        recognizer.isEndpoint((stream as SherpaStreamWrapper).stream)

    override fun getResult(stream: RecognizerStream): RecognitionResult {
        val result = recognizer.getResult((stream as SherpaStreamWrapper).stream)
        return RecognitionResult(
            text = result.text,
            tokens = result.tokens ?: emptyArray(),
            timestamps = result.timestamps ?: floatArrayOf()
        )
    }

    override fun reset(stream: RecognizerStream) =
        recognizer.reset((stream as SherpaStreamWrapper).stream)

    override fun release() = recognizer.release()

    private class SherpaStreamWrapper(
        private val recognizer: OnlineRecognizer,
        val stream: OnlineStream
    ) : RecognizerStream {
        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
            stream.acceptWaveform(samples, sampleRate)

        override fun inputFinished() = stream.inputFinished()
        override fun release() = stream.release()
    }
}
