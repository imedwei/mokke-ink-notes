package com.writer.recognition

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.writer.audio.AudioRecordCapture
import java.io.File

/**
 * [AudioTranscriber] using Sherpa-ONNX for real-time on-device transcription
 * with simultaneous audio recording.
 *
 * Same single-[AudioRecord] architecture as [VoskTranscriber]: tees each PCM
 * buffer to both the Sherpa decoder and an Opus/WebM encoder. The recognizer
 * is obtained from [SherpaModelManager] and is NOT owned by this class —
 * it survives across recording sessions.
 */
class SherpaTranscriber(
    private val context: Context,
    private val modelManager: SherpaModelManager
) : AudioTranscriber {

    private val tag = "SherpaTranscriber"

    private var audioRecord: AudioRecord? = null
    private var audioCapture: AudioRecordCapture? = null
    private var stream: OnlineStream? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var audioOffsetSec = 0f

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

        val recognizer = modelManager.getRecognizer() as? OnlineRecognizer
        if (recognizer == null) {
            Log.e(tag, "Model not ready")
            postMain { onStatusUpdate?.invoke("Speech engine not ready"); onError?.invoke(-1); isListening = false }
            return
        }

        postMain { onStatusUpdate?.invoke("Starting transcription...") }
        startRecording(recognizer)
    }

    private fun startRecording(recognizer: OnlineRecognizer) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(tag, "Mic permission denied", e)
            onError?.invoke(-1); isListening = false; return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "AudioRecord failed to initialize")
            recorder.release(); onError?.invoke(-1); isListening = false; return
        }

        val capture = AudioRecordCapture(context.cacheDir)
        if (capture.startEncoderOnly()) {
            audioCapture = capture
            Log.i(tag, "Audio encoder started")
        } else {
            Log.w(tag, "Audio encoder failed to start — recording will have no audio")
        }

        val onlineStream = recognizer.createStream()
        stream = onlineStream
        audioRecord = recorder
        recording = true
        recorder.startRecording()

        recordingThread = Thread({
            val buffer = ByteArray(BUFFER_SIZE)
            var lastPartialText = ""
            var chunkCount = 0
            // Track cumulative audio position — Sherpa resets timestamps after each
            // endpoint, but the audio recording is continuous.
            audioOffsetSec = 0f
            var segmentSamples = 0

            while (recording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Tee PCM to audio encoder
                audioCapture?.feedPcm(buffer, read)

                // RMS monitoring
                rmsFrameCount++
                if (rmsFrameCount % RMS_REPORT_INTERVAL == 0) {
                    val rmsDb = computeRmsDb(buffer, read)
                    postMain { onRmsChanged?.invoke(rmsDb) }
                }

                // Convert 16-bit PCM to float32 [-1, 1]
                val samples = read / 2
                segmentSamples += samples
                val floats = bytesToFloat(buffer, read)
                onlineStream.acceptWaveform(floats, SAMPLE_RATE)

                // Decode
                while (recognizer.isReady(onlineStream)) {
                    recognizer.decode(onlineStream)
                }

                // Check for endpoint (final)
                if (recognizer.isEndpoint(onlineStream)) {
                    val result = recognizer.getResult(onlineStream)
                    val text = normalizeCase(result.text.trim())
                    if (text.isNotBlank()) {
                        val words = SherpaTokenMerger.mergeTokens(
                            result.tokens ?: emptyArray(),
                            result.timestamps ?: floatArrayOf(),
                            audioOffsetSec
                        )
                        Log.i(tag, "Final (offset=%.2fs): %d words, text=\"%s\"".format(
                            audioOffsetSec, words.size, text.take(60)
                        ))
                        postMain {
                            onFinalResultWithWords?.invoke(text, words)
                            onFinalResult?.invoke(text)
                        }
                    }
                    // Advance offset by the audio duration of this segment
                    audioOffsetSec += segmentSamples.toFloat() / SAMPLE_RATE
                    segmentSamples = 0
                    recognizer.reset(onlineStream)
                    lastPartialText = ""
                } else {
                    // Partial — throttle to every PARTIAL_THROTTLE_CHUNKS chunks (~0.5s)
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
        recordingThread?.join(3000)
        recordingThread = null

        // Flush remaining audio
        val recognizer = modelManager.getRecognizer() as? OnlineRecognizer
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
                    result.tokens ?: emptyArray(),
                    result.timestamps ?: floatArrayOf(),
                    audioOffsetSec
                )
                Log.i(tag, "Final (flush, offset=%.2fs): %d words".format(audioOffsetSec, words.size))
                onFinalResultWithWords?.invoke(text, words)
                onFinalResult?.invoke(text)
            }
            onlineStream.release()
        }
        stream = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioCapture?.stopEncoder()
        val audioFile = audioCapture?.getOutputFile()
        Log.i(tag, "Stopped. Audio file: ${audioFile?.absolutePath} (${audioFile?.length() ?: 0} bytes)")
        isListening = false
    }

    override fun close() {
        stop()
        // Do NOT release the recognizer — it belongs to SherpaModelManager
        audioCapture = null
    }

    fun getAudioFile(): File? = audioCapture?.getOutputFile()
    override fun readRecordedBytes(): ByteArray? = audioCapture?.readRecordedBytes()

    /** Lowercase with first-letter capitalization (model outputs ALL CAPS). */
    private fun normalizeCase(text: String): String {
        if (text.isEmpty()) return text
        val lower = text.lowercase()
        return lower.replaceFirstChar { it.uppercase() }
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

    private fun bytesToFloat(buffer: ByteArray, length: Int): FloatArray {
        val samples = length / 2
        return FloatArray(samples) { i ->
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            (hi shl 8 or lo).toShort().toFloat() / 32768f
        }
    }

    private fun postMain(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 4000 // ~0.125s at 16kHz 16-bit mono
        private const val RMS_REPORT_INTERVAL = 8 // every ~1 second
        private const val PARTIAL_THROTTLE_CHUNKS = 4 // emit partial every ~0.5s
    }
}
