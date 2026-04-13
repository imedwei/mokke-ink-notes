package com.writer.recognition

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for speech recognition model loading and basic transcription.
 *
 * These tests verify that models download, load without crashing (including
 * the native ONNX runtime), and produce non-empty output for a short audio
 * clip. They catch issues like:
 * - Corrupt model files (truncated downloads, HTML redirect pages)
 * - Native SIGABRT during ONNX model init
 * - Config mismatches (wrong file names, missing tokens.txt)
 * - ORT format incompatibilities
 *
 * Run:
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.recognition.ModelSmokeTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "ModelSmokeTest"

    /** Generate 2 seconds of silence as test audio. */
    private fun silentPcm(durationSec: Float = 2f, sampleRate: Int = 16000): FloatArray =
        FloatArray((durationSec * sampleRate).toInt())

    /** Generate 2 seconds of a 440Hz sine wave as test audio. */
    private fun toneAudioPcm(durationSec: Float = 2f, sampleRate: Int = 16000): FloatArray {
        val samples = FloatArray((durationSec * sampleRate).toInt())
        for (i in samples.indices) {
            samples[i] = (0.5f * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toFloat()
        }
        return samples
    }

    @Test
    fun streaming_model_loads_and_transcribes() {
        val manager = SherpaModelManager()
        Log.i(tag, "Loading streaming ORT model...")

        manager.preload(context)

        // Wait for async load (up to 60s for download + init)
        val deadline = System.currentTimeMillis() + 60_000
        while (manager.state == SherpaModelManager.State.LOADING && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
        }

        assertEquals(
            "Streaming model should reach READY state (state=${manager.state})",
            SherpaModelManager.State.READY, manager.state
        )

        val recognizer = manager.getRecognizer()
        assertNotNull("Recognizer should be non-null when READY", recognizer)

        // Feed audio and get a result (may be empty for silence, but should not crash)
        val stream = recognizer!!.createStream()
        val pcm = silentPcm()
        stream.acceptWaveform(pcm, 16000)
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val result = recognizer.getResult(stream)
        Log.i(tag, "Streaming result: \"${result.text}\"")
        // No assertion on text — silence may produce empty or garbage text.
        // The test passes if we get here without a native crash.

        stream.release()
        manager.release()
        assertEquals(SherpaModelManager.State.UNLOADED, manager.state)
        Log.i(tag, "Streaming model smoke test passed")
    }

    @Test
    fun offline_model_loads_and_transcribes() {
        val manager = OfflineModelManager()
        Log.i(tag, "Loading offline GigaSpeech ORT model...")

        manager.preload(context)

        // Wait for async load (up to 120s — first download is ~68 MB)
        val deadline = System.currentTimeMillis() + 120_000
        while (manager.state == OfflineModelManager.State.LOADING && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
        }

        assertEquals(
            "Offline model should reach READY state (state=${manager.state})",
            OfflineModelManager.State.READY, manager.state
        )

        val recognizer = manager.getRecognizer()
        assertNotNull("Offline recognizer should be non-null when READY", recognizer)

        // Decode silence — should not crash
        val result = recognizer!!.decode(silentPcm(), 16000)
        Log.i(tag, "Offline result: \"${result.text}\"")
        // No assertion on text content — just verifying no crash

        manager.release()
        assertEquals(OfflineModelManager.State.UNLOADED, manager.state)
        Log.i(tag, "Offline model smoke test passed")
    }

    @Test
    fun two_pass_end_to_end() {
        // Load both models
        val streamingManager = SherpaModelManager()
        val offlineManager = OfflineModelManager()

        Log.i(tag, "Loading both models for two-pass test...")
        streamingManager.preload(context)
        offlineManager.preload(context)

        // Wait for both
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val streamingDone = streamingManager.state != SherpaModelManager.State.LOADING
            val offlineDone = offlineManager.state != OfflineModelManager.State.LOADING
            if (streamingDone && offlineDone) break
            Thread.sleep(500)
        }

        assertEquals("Streaming model should be READY",
            SherpaModelManager.State.READY, streamingManager.state)
        assertEquals("Offline model should be READY",
            OfflineModelManager.State.READY, offlineManager.state)

        // Simulate two-pass: streaming detects audio, offline re-transcribes
        val pcm = toneAudioPcm(3f) // 3 seconds of tone
        val streamRec = streamingManager.getRecognizer()!!
        val offlineRec = offlineManager.getRecognizer()!!

        // Pass 1: streaming
        val stream = streamRec.createStream()
        val chunkSize = 2000 // 0.125s
        var off = 0
        while (off < pcm.size) {
            val end = minOf(off + chunkSize, pcm.size)
            stream.acceptWaveform(pcm.copyOfRange(off, end), 16000)
            while (streamRec.isReady(stream)) streamRec.decode(stream)
            off = end
        }
        stream.inputFinished()
        while (streamRec.isReady(stream)) streamRec.decode(stream)
        val streamResult = streamRec.getResult(stream)
        stream.release()
        Log.i(tag, "Streaming pass: \"${streamResult.text}\"")

        // Pass 2: offline
        val offlineResult = offlineRec.decode(pcm, 16000)
        Log.i(tag, "Offline pass: \"${offlineResult.text}\"")

        // Verify offline result has tokens/timestamps fields (may be empty for non-speech)
        assertNotNull("Offline result should have tokens array", offlineResult.tokens)
        assertNotNull("Offline result should have timestamps array", offlineResult.timestamps)

        streamingManager.release()
        offlineManager.release()
        Log.i(tag, "Two-pass end-to-end smoke test passed")
    }
}
