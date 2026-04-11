package com.writer.recognition

/**
 * Wraps [com.k2fsa.sherpa.onnx.OfflineRecognizer] (JNI) to implement [OfflineRecognizer].
 */
class SherpaOfflineRecognizerWrapper(
    private val recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer
) : OfflineRecognizer {

    override fun decode(samples: FloatArray, sampleRate: Int): OfflineResult {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        return OfflineResult(
            text = result.text.trim(),
            tokens = result.tokens ?: emptyArray(),
            timestamps = result.timestamps ?: floatArrayOf()
        )
    }

    override fun release() = recognizer.release()
}
