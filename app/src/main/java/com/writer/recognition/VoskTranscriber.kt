package com.writer.recognition

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.writer.audio.AudioRecordCapture
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException

/**
 * [AudioTranscriber] using Vosk for real-time on-device transcription
 * with simultaneous audio recording.
 *
 * Owns a single [AudioRecord] stream and tees each PCM buffer to both:
 * 1. Vosk [Recognizer] for streaming speech-to-text
 * 2. [AudioRecordCapture] for Opus/WebM compressed audio file
 *
 * No mic contention — single consumer architecture.
 */
class VoskTranscriber(private val context: Context) : AudioTranscriber {

    private val tag = "VoskTranscriber"

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioCapture: AudioRecordCapture? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false

    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    /** Final result with per-word confidence and timestamps. */
    var onFinalResultWithWords: ((String, List<com.writer.model.WordInfo>) -> Unit)? = null
    /** Called with RMS dB level computed from raw PCM — use for quality monitoring. */
    var onRmsChanged: ((Float) -> Unit)? = null
    override var isListening: Boolean = false
        private set

    override fun start(languageTag: String) {
        if (isListening) stop()
        isListening = true

        Thread {
            try {
                // Ensure model is available
                val modelPath = ensureModel()
                if (modelPath == null) {
                    postMain { onStatusUpdate?.invoke("Model download failed"); onError?.invoke(-1); isListening = false }
                    return@Thread
                }

                postMain { onStatusUpdate?.invoke("Loading Vosk model...") }
                model = Model(modelPath)
                val rec = Recognizer(model, SAMPLE_RATE.toFloat())
                rec.setWords(true) // Enable word-level timestamps
                rec.setPartialWords(true)
                recognizer = rec

                postMain { onStatusUpdate?.invoke("Lecture mode started") }
                postMain { startRecording() }
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize Vosk", e)
                postMain { onStatusUpdate?.invoke("Vosk init failed"); onError?.invoke(-1); isListening = false }
            }
        }.start()
    }

    private fun startRecording() {
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

        // Start compressed audio capture alongside
        // Start Opus/WebM encoder for audio save (encoder-only, no own AudioRecord)
        val capture = AudioRecordCapture(context.cacheDir)
        if (capture.startEncoderOnly()) {
            audioCapture = capture
            Log.i(tag, "Audio encoder started for recording")
        }

        audioRecord = recorder
        recording = true
        recorder.startRecording()

        recordingThread = Thread({
            val buffer = ByteArray(BUFFER_SIZE)
            val rec = recognizer ?: return@Thread

            while (recording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // Tee PCM to both Vosk recognizer and audio encoder
                    audioCapture?.feedPcm(buffer, read)

                    // Compute RMS dB from 16-bit PCM for audio quality monitoring
                    rmsFrameCount++
                    if (rmsFrameCount % RMS_REPORT_INTERVAL == 0) {
                        val rmsDb = computeRmsDb(buffer, read)
                        postMain { onRmsChanged?.invoke(rmsDb) }
                    }

                    if (rec.acceptWaveForm(buffer, read)) {
                        val result = rec.finalResult
                        val text = parseVoskResult(result)
                        if (text.isNotBlank()) {
                            val words = parseVoskWords(result)
                            postMain {
                                onFinalResultWithWords?.invoke(text, words)
                                onFinalResult?.invoke(text)
                            }
                        }
                    } else {
                        val partial = rec.partialResult
                        val text = parseVoskPartial(partial)
                        if (text.isNotBlank()) {
                            postMain { onPartialResult?.invoke(text) }
                        }
                    }
                }
            }
        }, "VoskRecording").also { it.start() }

        Log.i(tag, "Recording + transcription started")
    }

    override fun stop() {
        recording = false
        recordingThread?.join(3000)
        recordingThread = null

        // Get any remaining result
        recognizer?.let { rec ->
            val result = rec.finalResult
            val finalText = parseVoskResult(result)
            if (finalText.isNotBlank()) {
                val words = parseVoskWords(result)
                onFinalResultWithWords?.invoke(finalText, words)
                onFinalResult?.invoke(finalText)
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Finalize audio encoding
        audioCapture?.stopEncoder()

        isListening = false
    }

    override fun close() {
        stop()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        audioCapture = null
    }

    /** Get the compressed audio file after stop(). */
    fun getAudioFile(): java.io.File? = audioCapture?.getOutputFile()

    /** Read the compressed audio bytes after stop(). */
    fun readRecordedBytes(): ByteArray? = audioCapture?.readRecordedBytes()

    private var rmsFrameCount = 0

    /** Compute RMS in dB from 16-bit PCM buffer (same scale as SpeechRecognizer). */
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
        // Convert to dB scale similar to SpeechRecognizer's onRmsChanged range (-2 to 10)
        return if (rms > 0) (20 * kotlin.math.log10(rms / 32768.0) + 90).toFloat().coerceIn(-2f, 10f) else -2f
    }

    /** Parse Vosk word-level data: [{word, conf, start, end}, ...] */
    private fun parseVoskWords(json: String): List<com.writer.model.WordInfo> {
        return try {
            val obj = JSONObject(json)
            val resultArray = obj.optJSONArray("result") ?: return emptyList()
            (0 until resultArray.length()).map { i ->
                val w = resultArray.getJSONObject(i)
                com.writer.model.WordInfo(
                    text = w.optString("word", ""),
                    confidence = w.optDouble("conf", 1.0).toFloat(),
                    startMs = (w.optDouble("start", 0.0) * 1000).toLong(),
                    endMs = (w.optDouble("end", 0.0) * 1000).toLong()
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseVoskResult(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (_: Exception) { "" }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            JSONObject(json).optString("partial", "")
        } catch (_: Exception) { "" }
    }

    private fun postMain(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun ensureModel(): String? {
        val modelDir = File(context.filesDir, "vosk_models")
        modelDir.mkdirs()
        val modelPath = File(modelDir, MODEL_DIR_NAME)

        if (modelPath.exists() && modelPath.isDirectory && modelPath.list()?.isNotEmpty() == true) {
            Log.i(tag, "Model already downloaded: ${modelPath.absolutePath}")
            return modelPath.absolutePath
        }

        Log.i(tag, "Downloading Vosk model...")
        postMain { onStatusUpdate?.invoke("Downloading Vosk model (~40 MB)...") }

        try {
            val url = java.net.URL(MODEL_URL)
            val zipFile = File(modelDir, "model.zip")
            url.openStream().use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val mb = downloaded / (1024 * 1024)
                        if (mb > 0 && downloaded % (5 * 1024 * 1024) < 8192) {
                            postMain { onStatusUpdate?.invoke("Downloading: ${mb} MB") }
                        }
                    }
                }
            }

            // Unzip
            postMain { onStatusUpdate?.invoke("Unpacking model...") }
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(modelDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            zipFile.delete()

            Log.i(tag, "Model unpacked to ${modelPath.absolutePath}")
            return modelPath.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed to download model", e)
            return null
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 4000 // ~0.125s at 16kHz 16-bit mono — responsive streaming
        private const val RMS_REPORT_INTERVAL = 8 // Report RMS every 8 buffers (~1 second)
        private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
