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
    override var isListening: Boolean = false
        private set

    override fun start(languageTag: String) {
        if (isListening) stop()
        isListening = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Preparing whisper model...")
                }
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
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Loading whisper model...")
                }
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
                    onStatusUpdate?.invoke("Listening...")
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

        // Continuous recording + periodic transcription
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val shortBuffer = ShortArray(sampleRate) // 1 second chunks
            var chunkCount = 0

            while (isActive && isListening) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    synchronized(audioBuffer) {
                        for (i in 0 until read) {
                            audioBuffer.add(shortBuffer[i] / 32768f) // Convert to float [-1, 1]
                        }
                    }
                    chunkCount++

                    // Transcribe every CHUNK_SECONDS seconds of audio
                    if (chunkCount % CHUNK_SECONDS == 0) {
                        transcribeAccumulated()
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

        // Final transcription of all accumulated audio
        CoroutineScope(Dispatchers.IO).launch {
            val samples: FloatArray
            synchronized(audioBuffer) {
                samples = audioBuffer.toFloatArray()
                audioBuffer.clear()
            }

            if (samples.size >= WHISPER_SAMPLE_RATE) {
                val text = withContext(scope.coroutineContext) {
                    transcribe(samples)
                }
                withContext(Dispatchers.Main) {
                    onFinalResult?.invoke(text.trim())
                }
            } else {
                withContext(Dispatchers.Main) {
                    onFinalResult?.invoke("")
                }
            }
        }
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

    private fun transcribe(samples: FloatArray): String {
        if (whisperPtr == 0L) return ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        WhisperLib.fullTranscribe(whisperPtr, threads, samples)
        val segmentCount = WhisperLib.getTextSegmentCount(whisperPtr)
        return buildString {
            for (i in 0 until segmentCount) {
                append(WhisperLib.getTextSegment(whisperPtr, i))
            }
        }
    }

    /** Download the whisper model if not already cached. Returns the file path. */
    private suspend fun ensureModel(): String? = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "whisper_models")
        modelDir.mkdirs()
        val modelFile = File(modelDir, MODEL_FILENAME)

        if (modelFile.exists() && modelFile.length() > 0) {
            Log.i(tag, "Model already downloaded: ${modelFile.absolutePath}")
            return@withContext modelFile.absolutePath
        }

        Log.i(tag, "Downloading whisper model ($MODEL_FILENAME)...")
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
                        if (totalBytes > 0) {
                            val percent = (downloaded * 100 / totalBytes).toInt()
                            if (percent != lastReportPercent && percent % 10 == 0) {
                                lastReportPercent = percent
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate?.invoke("Downloading model: $percent%")
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
            modelFile.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed to download model", e)
            modelFile.delete()
            null
        }
    }

    companion object {
        private const val WHISPER_SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 5 // Transcribe every N seconds

        // ggml-base.en model (~75 MB) — best balance of size and quality for English
        private const val MODEL_FILENAME = "ggml-base.en.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"

        fun isAvailable(): Boolean = try {
            WhisperLib // Triggers native library load
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
