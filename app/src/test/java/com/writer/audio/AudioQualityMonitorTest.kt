package com.writer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioQualityMonitorTest {

    private lateinit var monitor: AudioQualityMonitor

    @Before
    fun setUp() {
        monitor = AudioQualityMonitor()
    }

    @Test
    fun initialState_isMeasuring() {
        assertEquals(AudioQualityMonitor.Quality.MEASURING, monitor.quality)
        assertFalse(monitor.shouldWarn)
    }

    @Test
    fun fewSamples_stillMeasuring() {
        repeat(10) { monitor.onRmsChanged(5f) }
        assertEquals(AudioQualityMonitor.Quality.MEASURING, monitor.quality)
    }

    @Test
    fun goodSnr_quietNoiseAndLoudSpeech() {
        // Simulate quiet background noise
        repeat(30) { monitor.onRmsChanged(-1f) }
        // Simulate loud speech
        repeat(30) { monitor.onRmsChanged(8f) }
        assertEquals(AudioQualityMonitor.Quality.GOOD, monitor.quality)
        assertFalse(monitor.shouldWarn)
        assertTrue(monitor.snr >= 6f)
    }

    @Test
    fun poorSnr_loudNoiseFloor() {
        // High noise floor with barely-above-threshold speech
        repeat(30) { monitor.onRmsChanged(1.5f) }
        repeat(30) { monitor.onRmsChanged(3f) }
        assertEquals(AudioQualityMonitor.Quality.POOR, monitor.quality)
        assertTrue(monitor.shouldWarn)
    }

    @Test
    fun fairSnr_moderateNoise() {
        repeat(30) { monitor.onRmsChanged(0f) }
        repeat(30) { monitor.onRmsChanged(4f) }
        // SNR ~4 dB = fair
        assertEquals(AudioQualityMonitor.Quality.FAIR, monitor.quality)
        assertFalse(monitor.shouldWarn)
    }

    @Test
    fun reset_clearsState() {
        repeat(60) { monitor.onRmsChanged(8f) }
        assertEquals(AudioQualityMonitor.Quality.GOOD, monitor.quality)
        monitor.reset()
        assertEquals(AudioQualityMonitor.Quality.MEASURING, monitor.quality)
    }

    @Test
    fun currentRms_reflectsRecentValues() {
        repeat(30) { monitor.onRmsChanged(5f) }
        assertEquals(5f, monitor.currentRms, 0.5f)
    }

    @Test
    fun qualityMessage_containsActionableText() {
        repeat(30) { monitor.onRmsChanged(1.5f) }
        repeat(30) { monitor.onRmsChanged(3f) }
        assertTrue(monitor.qualityMessage.contains("background noise"))
    }
}
