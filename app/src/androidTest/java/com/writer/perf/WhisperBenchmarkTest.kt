package com.writer.perf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.writer.recognition.WhisperLib
import com.writer.recognition.WhisperTranscriber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * Benchmark whisper.cpp transcription speed on the connected device.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.perf.WhisperBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchmarkTest {

    private val tag = "WhisperBenchmark"

    /** Generate a synthetic 10-second audio signal (sine wave at 440Hz). */
    private fun generateTestAudio(durationSeconds: Float = 10f): FloatArray {
        val sampleRate = 16000
        val numSamples = (sampleRate * durationSeconds).toInt()
        return FloatArray(numSamples) { i ->
            val t = i.toFloat() / sampleRate
            (0.3f * Math.sin(2.0 * Math.PI * 440.0 * t)).toFloat()
        }
    }

    private fun ensureModel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "whisper_models")
        modelDir.mkdirs()

        // Use the same model as WhisperTranscriber
        val modelFile = File(modelDir, "ggml-tiny.en-q5_1.bin")
        if (modelFile.exists() && modelFile.length() > 0) {
            return modelFile.absolutePath
        }

        // Download
        Log.i(tag, "Downloading model...")
        val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin")
        url.openStream().use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.i(tag, "Model downloaded: ${modelFile.length()} bytes")
        return modelFile.absolutePath
    }

    private fun ensureModelByName(filename: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "whisper_models")
        modelDir.mkdirs()
        val modelFile = File(modelDir, filename)
        if (modelFile.exists() && modelFile.length() > 0) return modelFile.absolutePath
        Log.i(tag, "Downloading $filename...")
        val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$filename")
        url.openStream().use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(tag, "Downloaded: ${modelFile.length()} bytes")
        return modelFile.absolutePath
    }

    private fun ensureVadModel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "whisper_models")
        modelDir.mkdirs()
        val vadFile = File(modelDir, "ggml-silero-v5.1.2.bin")
        if (vadFile.exists() && vadFile.length() > 0) return vadFile.absolutePath
        Log.i(tag, "Downloading VAD model...")
        val url = URL("https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin")
        url.openStream().use { input ->
            vadFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(tag, "VAD model downloaded: ${vadFile.length()} bytes")
        return vadFile.absolutePath
    }

    @Test
    fun benchmark_10s_audio() {
        val modelPath = ensureModel()

        // Load model
        val loadStart = System.currentTimeMillis()
        val ptr = WhisperLib.initContext(modelPath)
        val loadMs = System.currentTimeMillis() - loadStart
        assertTrue("Model should load", ptr != 0L)
        Log.i(tag, "Model loaded in ${loadMs}ms")

        // Generate test audio
        val audio = generateTestAudio(10f)
        Log.i(tag, "Test audio: ${audio.size} samples (${audio.size / 16000f}s)")

        // Benchmark transcription with different thread counts
        for (threads in listOf(2, 4)) {
            val transcribeStart = System.currentTimeMillis()
            WhisperLib.fullTranscribe(ptr, threads, audio)
            val transcribeMs = System.currentTimeMillis() - transcribeStart

            val segments = WhisperLib.getTextSegmentCount(ptr)
            val text = buildString {
                for (i in 0 until segments) {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }

            val realtimeFactor = transcribeMs / 1000f / (audio.size / 16000f)
            Log.i(tag, "=== BENCHMARK: $threads threads ===")
            Log.i(tag, "Transcription time: ${transcribeMs}ms")
            Log.i(tag, "Realtime factor: ${realtimeFactor}x")
            Log.i(tag, "Segments: $segments, Text: \"${text.take(60)}\"")
            Log.i(tag, "===================================")
        }

        WhisperLib.freeContext(ptr)
    }

    @Test
    fun benchmark_vad_comparison() {
        // Generate 10s audio with ~50% silence (alternating 1s speech / 1s silence)
        val sampleRate = 16000
        val duration = 10f
        val numSamples = (sampleRate * duration).toInt()
        val audio = FloatArray(numSamples) { i ->
            val t = i.toFloat() / sampleRate
            val inSpeechWindow = (t.toInt() % 2 == 0) // speech on even seconds
            if (inSpeechWindow) (0.3f * Math.sin(2.0 * Math.PI * 440.0 * t)).toFloat() else 0f
        }

        val modelPath = ensureModelByName("ggml-tiny.en.bin")
        val vadPath = ensureVadModel()
        val ptr = WhisperLib.initContext(modelPath)
        assertTrue("Model should load", ptr != 0L)

        // Without VAD
        val startNoVad = System.currentTimeMillis()
        WhisperLib.fullTranscribe(ptr, 4, audio, null)
        val msNoVad = System.currentTimeMillis() - startNoVad
        Log.i(tag, "NO_VAD TIME=${msNoVad}ms FACTOR=${msNoVad / 1000f / duration}x")

        // With VAD
        val startVad = System.currentTimeMillis()
        WhisperLib.fullTranscribe(ptr, 4, audio, vadPath)
        val msVad = System.currentTimeMillis() - startVad
        Log.i(tag, "WITH_VAD TIME=${msVad}ms FACTOR=${msVad / 1000f / duration}x")

        Log.i(tag, "VAD speedup: %.2fx".format(msNoVad.toFloat() / msVad))

        WhisperLib.freeContext(ptr)
    }
}
