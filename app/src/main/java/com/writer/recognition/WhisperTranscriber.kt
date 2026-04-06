package com.writer.recognition

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

/**
 * [AudioTranscriber] using whisper.cpp for on-device transcription.
 *
 * Records audio via [AudioRecord] at 16kHz mono (whisper's native format),
 * then runs whisper inference on accumulated audio chunks.
 *
 * The whisper model is downloaded on first use (~75 MB for base.en).
 */
class WhisperTranscriber(private val context: Context) : AudioTranscriber {

    private val tag = "WhisperTranscriber"
    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    private var whisperPtr = 0L
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val audioBuffer = mutableListOf<Float>()

    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    /** Status updates for model download progress, initialization, etc. */
    var onStatusUpdate: ((String) -> Unit)? = null
    /** Progress updates during transcription (0.0 to 1.0, audioDurationSec). */
    var onTranscriptionProgress: ((progress: Float, audioDurationSec: Float) -> Unit)? = null
    override var isListening: Boolean = false
        private set

    override fun start(languageTag: String) {
        if (isListening) stop()
        isListening = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelPath = ensureModel()
                if (modelPath == null) {
                    withContext(Dispatchers.Main) {
                        onStatusUpdate?.invoke("Model download failed")
                        onError?.invoke(-1)
                        isListening = false
                    }
                    return@launch
                }

                // Initialize whisper context on the whisper thread
                whisperPtr = withContext(scope.coroutineContext) {
                    WhisperLib.initContext(modelPath)
                }
                if (whisperPtr == 0L) {
                    Log.e(tag, "Failed to initialize whisper context")
                    withContext(Dispatchers.Main) {
                        onStatusUpdate?.invoke("Failed to load model")
                        onError?.invoke(-1)
                        isListening = false
                    }
                    return@launch
                }
                Log.i(tag, "Whisper context initialized")
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Lecture mode started")
                }

                // Start recording
                withContext(Dispatchers.Main) {
                    startRecording()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start whisper", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke(-1)
                    isListening = false
                }
            }
        }
    }

    private fun startRecording() {
        val sampleRate = WHISPER_SAMPLE_RATE
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2) // At least 1 second buffer

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(tag, "AudioRecord failed to initialize")
            onError?.invoke(-1)
            isListening = false
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        audioBuffer.clear()

        // Record audio continuously — transcription happens on stop()
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val shortBuffer = ShortArray(sampleRate) // 1 second chunks

            while (isActive && isListening) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    synchronized(audioBuffer) {
                        for (i in 0 until read) {
                            audioBuffer.add(shortBuffer[i] / 32768f)
                        }
                    }
                }
            }
        }
    }

    private suspend fun transcribeAccumulated() {
        val samples: FloatArray
        synchronized(audioBuffer) {
            if (audioBuffer.size < WHISPER_SAMPLE_RATE) return // Need at least 1 second
            samples = audioBuffer.toFloatArray()
        }

        val text = withContext(scope.coroutineContext) {
            transcribe(samples)
        }

        if (text.isNotBlank()) {
            withContext(Dispatchers.Main) {
                onPartialResult?.invoke(text.trim())
            }
        }
    }

    override fun stop() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Transcribe all accumulated audio (batch mode — may take a while)
        CoroutineScope(Dispatchers.IO).launch {
            val samples: FloatArray
            synchronized(audioBuffer) {
                samples = audioBuffer.toFloatArray()
                // Save for getRecordedWavBytes()
                synchronized(lastRecordedSamples) {
                    lastRecordedSamples.clear()
                    lastRecordedSamples.addAll(audioBuffer)
                }
                audioBuffer.clear()
            }

            if (samples.size >= WHISPER_SAMPLE_RATE) {
                val durationSec = samples.size / WHISPER_SAMPLE_RATE.toFloat()
                val estimatedMs = (durationSec * calibratedRealtimeFactor * 1000).toLong()
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Transcribing %.0fs of audio...".format(durationSec))
                    onTranscriptionProgress?.invoke(0f, durationSec)
                }

                // Run progress ticker on main thread while transcription runs on whisper thread
                val startTime = System.currentTimeMillis()
                val progressJob = CoroutineScope(Dispatchers.Main).launch {
                    while (true) {
                        kotlinx.coroutines.delay(PROGRESS_UPDATE_INTERVAL_MS)
                        val elapsed = System.currentTimeMillis() - startTime
                        val progress = (elapsed.toFloat() / estimatedMs).coerceIn(0f, 0.95f)
                        onTranscriptionProgress?.invoke(progress, durationSec)
                    }
                }

                val text = withContext(scope.coroutineContext) {
                    transcribe(samples)
                }

                progressJob.cancel()
                withContext(Dispatchers.Main) {
                    onTranscriptionProgress?.invoke(1f, durationSec)
                    onFinalResult?.invoke(text.trim())
                }
            } else {
                withContext(Dispatchers.Main) {
                    onFinalResult?.invoke("")
                }
            }
        }
    }

    /** Get the last recorded audio as WAV bytes. Available after stop(). */
    fun getRecordedWavBytes(): ByteArray? {
        synchronized(lastRecordedSamples) {
            if (lastRecordedSamples.isEmpty()) return null
            return encodeWav(lastRecordedSamples.toFloatArray(), WHISPER_SAMPLE_RATE)
        }
    }
    private val lastRecordedSamples = mutableListOf<Float>()

    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            pcm[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        val dataSize = pcm.size
        val fileSize = 36 + dataSize
        val header = java.io.ByteArrayOutputStream(44)
        fun writeInt(v: Int) { header.write(v and 0xFF); header.write(v shr 8 and 0xFF); header.write(v shr 16 and 0xFF); header.write(v shr 24 and 0xFF) }
        fun writeShort(v: Int) { header.write(v and 0xFF); header.write(v shr 8 and 0xFF) }
        header.write("RIFF".toByteArray()); writeInt(fileSize)
        header.write("WAVE".toByteArray())
        header.write("fmt ".toByteArray()); writeInt(16); writeShort(1) // PCM
        writeShort(1); writeInt(sampleRate); writeInt(sampleRate * 2); writeShort(2); writeShort(16)
        header.write("data".toByteArray()); writeInt(dataSize)
        return header.toByteArray() + pcm
    }

    override fun close() {
        isListening = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        scope.launch {
            if (whisperPtr != 0L) {
                WhisperLib.freeContext(whisperPtr)
                whisperPtr = 0
            }
        }
    }

    private var vadModelPath: String? = null

    /** Calibrated realtime factor — updated after each transcription via EMA. */
    var calibratedRealtimeFactor: Float
        get() = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getFloat(PREF_REALTIME_FACTOR, ESTIMATED_REALTIME_FACTOR)
        private set(value) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putFloat(PREF_REALTIME_FACTOR, value).apply()
        }

    private fun transcribe(samples: FloatArray): String {
        if (whisperPtr == 0L) return ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val startMs = System.currentTimeMillis()
        WhisperLib.fullTranscribe(whisperPtr, threads, samples, vadModelPath)
        val elapsedMs = System.currentTimeMillis() - startMs
        val segmentCount = WhisperLib.getTextSegmentCount(whisperPtr)
        val result = buildString {
            for (i in 0 until segmentCount) {
                append(WhisperLib.getTextSegment(whisperPtr, i))
            }
        }
        val audioDurationSec = samples.size / WHISPER_SAMPLE_RATE.toFloat()
        val measuredFactor = elapsedMs / 1000f / audioDurationSec
        Log.i(tag, "Transcribed %.1fs audio in %dms (%.1fx realtime): %d segments, \"%s\"".format(
            audioDurationSec, elapsedMs, measuredFactor, segmentCount, result.take(80)
        ))

        // Update calibrated factor with exponential moving average (α=0.3)
        val prev = calibratedRealtimeFactor
        calibratedRealtimeFactor = prev * 0.7f + measuredFactor * 0.3f
        Log.i(tag, "Realtime factor: measured=%.1fx, calibrated=%.1fx (was %.1fx)".format(
            measuredFactor, calibratedRealtimeFactor, prev
        ))

        return result
    }

    /** Download the whisper model if not already cached. Returns the file path. */
    private suspend fun ensureModel(): String? = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "whisper_models")
        modelDir.mkdirs()
        val modelFile = File(modelDir, MODEL_FILENAME)

        if (modelFile.exists() && modelFile.length() > 0) {
            Log.i(tag, "Model already downloaded: ${modelFile.absolutePath}")
            val vadFile = File(modelDir, VAD_MODEL_FILENAME)
            if (vadFile.exists()) vadModelPath = vadFile.absolutePath
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("Loading whisper model...")
            }
            return@withContext modelFile.absolutePath
        }

        Log.i(tag, "Downloading whisper model ($MODEL_FILENAME)...")
        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke("Downloading model...")
        }
        try {
            val url = URL(MODEL_URL)
            val connection = url.openConnection()
            val totalBytes = connection.contentLengthLong
            connection.getInputStream().use { input ->
                modelFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastReportPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val downloadedMB = downloaded / (1024 * 1024)
                        if (totalBytes > 0) {
                            val percent = (downloaded * 100 / totalBytes).toInt()
                            if (percent != lastReportPercent && percent % 5 == 0) {
                                lastReportPercent = percent
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate?.invoke("Downloading model: $percent%")
                                }
                            }
                        } else {
                            // No content-length — report MB downloaded
                            val prevMB = (downloaded - read) / (1024 * 1024)
                            if (downloadedMB != prevMB) {
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate?.invoke("Downloading model: ${downloadedMB} MB")
                                }
                            }
                        }
                    }
                }
            }
            Log.i(tag, "Model downloaded: ${modelFile.length()} bytes")
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("Model downloaded")
            }

            // Also download VAD model if not cached
            val vadFile = File(modelDir, VAD_MODEL_FILENAME)
            if (!vadFile.exists() || vadFile.length() == 0L) {
                Log.i(tag, "Downloading VAD model...")
                try {
                    URL(VAD_MODEL_URL).openStream().use { input ->
                        vadFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.i(tag, "VAD model downloaded: ${vadFile.length()} bytes")
                } catch (e: Exception) {
                    Log.w(tag, "Failed to download VAD model — continuing without VAD", e)
                }
            }
            if (vadFile.exists()) vadModelPath = vadFile.absolutePath

            modelFile.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed to download model", e)
            modelFile.delete()
            null
        }
    }

    companion object {
        private const val WHISPER_SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 10
        private const val ESTIMATED_REALTIME_FACTOR = 5f // initial guess, calibrated over time
        private const val PROGRESS_UPDATE_INTERVAL_MS = 3000L
        private const val PREFS_NAME = "whisper_prefs"
        private const val PREF_REALTIME_FACTOR = "realtime_factor"

        // ggml-tiny.en f16 (~75 MB) — fastest on Snapdragon 690 (q5_1 is slower due to dequant overhead)
        private const val MODEL_FILENAME = "ggml-tiny.en.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"

        // Silero VAD model (~2 MB) — skips silent segments for faster transcription
        private const val VAD_MODEL_FILENAME = "ggml-silero-v5.1.2.bin"
        private const val VAD_MODEL_URL =
            "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin?download=true"

        fun isAvailable(): Boolean = try {
            WhisperLib // Triggers native library load
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
