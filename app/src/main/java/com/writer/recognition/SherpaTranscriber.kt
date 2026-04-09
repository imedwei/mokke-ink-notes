package com.writer.recognition

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.writer.audio.AudioRecordCapture
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [AudioTranscriber] using Sherpa-ONNX for real-time on-device transcription
 * with simultaneous audio recording.
 *
 * Same single-[AudioRecord] architecture as [VoskTranscriber]: tees each PCM
 * buffer to both the speech recognizer and an Opus/OGG encoder.
 *
 * The recognizer is obtained from [SherpaModelManager] via the [StreamingRecognizer]
 * interface — does NOT own it. The recognizer survives across recording sessions.
 *
 * @param pcmSourceFactory injectable for testing — defaults to creating an [AudioRecord].
 */
class SherpaTranscriber(
    private val context: Context?,
    private val modelManager: SherpaModelManager,
    private val pcmSourceFactory: (() -> PcmSource)? = null,
    private val mainThreadExecutor: (Runnable) -> Unit = { action ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
) : AudioTranscriber {

    /** Abstraction over AudioRecord for testability. */
    interface PcmSource {
        fun read(buffer: ByteArray, offset: Int, size: Int): Int
        fun stop()
        fun release()
    }

    private val tag = "SherpaTranscriber"

    private var pcmSource: PcmSource? = null
    private var audioCapture: AudioRecordCapture? = null
    private var stream: RecognizerStream? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var audioOffsetSec = 0f
    private var floatBuffer = FloatArray(BUFFER_SIZE / 2)

    private data class PendingFinal(val text: String, val words: List<com.writer.model.WordInfo>)
    private val pendingFinals = ConcurrentLinkedQueue<PendingFinal>()

    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onFinalResultWithWords: ((String, List<com.writer.model.WordInfo>) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    override var isListening: Boolean = false
        private set

    override fun start(languageTag: String) {
        if (isListening) stop()
        isListening = true

        val recognizer = modelManager.getRecognizer()
        if (recognizer == null) {
            Log.e(tag, "Model not ready")
            postMain { onStatusUpdate?.invoke("Speech engine not ready"); onError?.invoke(-1); isListening = false }
            return
        }

        postMain { onStatusUpdate?.invoke("Starting transcription...") }
        startRecording(recognizer)
    }

    private fun startRecording(recognizer: StreamingRecognizer) {
        val source = if (pcmSourceFactory != null) {
            pcmSourceFactory.invoke()
        } else {
            createAudioRecordSource() ?: run {
                onError?.invoke(-1); isListening = false; return
            }
        }

        // Start audio encoder (skip if using injected PcmSource — tests don't need encoding)
        if (pcmSourceFactory == null) {
            val capture = AudioRecordCapture(context!!.cacheDir)
            if (capture.startEncoderOnly()) {
                audioCapture = capture
                Log.i(tag, "Audio encoder started")
            } else {
                Log.w(tag, "Audio encoder failed to start — recording will have no audio")
            }
        }

        val onlineStream = recognizer.createStream()
        stream = onlineStream
        pcmSource = source
        recording = true
        pendingFinals.clear()

        recordingThread = Thread({
            val buffer = ByteArray(BUFFER_SIZE)
            var lastPartialText = ""
            var chunkCount = 0
            rmsFrameCount = 0
            audioOffsetSec = 0f
            var segmentSamples = 0

            while (recording) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                audioCapture?.feedPcm(buffer, read)

                rmsFrameCount++
                if (rmsFrameCount % RMS_REPORT_INTERVAL == 0) {
                    val rmsDb = computeRmsDb(buffer, read)
                    postMain { onRmsChanged?.invoke(rmsDb) }
                }

                val samples = read / 2
                segmentSamples += samples
                if (floatBuffer.size != samples) floatBuffer = FloatArray(samples)
                bytesToFloat(buffer, read, floatBuffer)
                onlineStream.acceptWaveform(floatBuffer, SAMPLE_RATE)

                while (recognizer.isReady(onlineStream)) {
                    recognizer.decode(onlineStream)
                }

                if (recognizer.isEndpoint(onlineStream)) {
                    val result = recognizer.getResult(onlineStream)
                    val text = normalizeCase(result.text.trim())
                    if (text.isNotBlank()) {
                        val words = SherpaTokenMerger.mergeTokens(
                            result.tokens, result.timestamps, audioOffsetSec
                        )
                        Log.i(tag, "Final (offset=%.2fs): %d words, text=\"%s\"".format(
                            audioOffsetSec, words.size, text.take(60)
                        ))
                        pendingFinals.add(PendingFinal(text, words))
                        postMain { deliverPendingFinals() }
                    }
                    audioOffsetSec += segmentSamples.toFloat() / SAMPLE_RATE
                    segmentSamples = 0
                    recognizer.reset(onlineStream)
                    lastPartialText = ""
                } else {
                    chunkCount++
                    if (chunkCount % PARTIAL_THROTTLE_CHUNKS == 0) {
                        val result = recognizer.getResult(onlineStream)
                        val text = normalizeCase(result.text.trim())
                        if (text.isNotBlank() && text != lastPartialText) {
                            lastPartialText = text
                            postMain { onPartialResult?.invoke(text) }
                        }
                    }
                }
            }
        }, "SherpaRecording").also { it.start() }

        Log.i(tag, "Recording + transcription started")
    }

    override fun stop() {
        recording = false
        pcmSource?.stop() // unblock read() so the recording thread can exit
        recordingThread?.join(3000)
        recordingThread = null

        // Deliver any finals queued by the recording thread.
        // Must happen before the caller clears lectureMode.
        deliverPendingFinals()

        // Flush remaining audio
        val recognizer = modelManager.getRecognizer()
        val onlineStream = stream
        if (recognizer != null && onlineStream != null) {
            onlineStream.inputFinished()
            while (recognizer.isReady(onlineStream)) {
                recognizer.decode(onlineStream)
            }
            val result = recognizer.getResult(onlineStream)
            val text = normalizeCase(result.text.trim())
            if (text.isNotBlank()) {
                val words = SherpaTokenMerger.mergeTokens(
                    result.tokens, result.timestamps, audioOffsetSec
                )
                Log.i(tag, "Final (flush, offset=%.2fs): %d words".format(audioOffsetSec, words.size))
                onFinalResultWithWords?.invoke(text, words)
                onFinalResult?.invoke(text)
            }
            onlineStream.release()
        }
        stream = null

        pcmSource?.stop() // idempotent — already called before join, but needed for release
        pcmSource?.release()
        pcmSource = null

        audioCapture?.stopEncoder()
        val audioFile = audioCapture?.getOutputFile()
        Log.i(tag, "Stopped. Audio file: ${audioFile?.absolutePath} (${audioFile?.length() ?: 0} bytes)")
        isListening = false
    }

    override fun close() {
        stop()
        audioCapture = null
    }

    fun getAudioFile(): File? = audioCapture?.getOutputFile()
    override fun readRecordedBytes(): ByteArray? = audioCapture?.readRecordedBytes()

    private fun deliverPendingFinals() {
        while (true) {
            val f = pendingFinals.poll() ?: break
            onFinalResultWithWords?.invoke(f.text, f.words)
            onFinalResult?.invoke(f.text)
        }
    }

    private fun normalizeCase(text: String): String {
        if (text.isEmpty()) return text
        return text.lowercase().replaceFirstChar { it.uppercase() }
    }

    private var rmsFrameCount = 0

    private fun computeRmsDb(buffer: ByteArray, length: Int): Float {
        var sumSq = 0.0
        val samples = length / 2
        for (i in 0 until samples) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            val sample = (hi shl 8 or lo).toShort().toFloat()
            sumSq += sample * sample
        }
        val rms = kotlin.math.sqrt(sumSq / samples)
        return if (rms > 0) (20 * kotlin.math.log10(rms / 32768.0) + 90).toFloat().coerceIn(-2f, 10f) else -2f
    }

    private fun bytesToFloat(buffer: ByteArray, length: Int, out: FloatArray) {
        val samples = length / 2
        for (i in 0 until samples) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            out[i] = (hi shl 8 or lo).toShort().toFloat() / 32768f
        }
    }

    private fun createAudioRecordSource(): PcmSource? {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(tag, "Mic permission denied", e); return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "AudioRecord failed to initialize"); recorder.release(); return null
        }

        recorder.startRecording()
        return object : PcmSource {
            override fun read(buffer: ByteArray, offset: Int, size: Int) = recorder.read(buffer, offset, size)
            override fun stop() = recorder.stop()
            override fun release() = recorder.release()
        }
    }

    private fun postMain(action: () -> Unit) {
        mainThreadExecutor(Runnable { action() })
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 4000
        private const val RMS_REPORT_INTERVAL = 8
        private const val PARTIAL_THROTTLE_CHUNKS = 4
    }
}
