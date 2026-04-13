package com.writer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioRecordingTest {

    @Test
    fun defaultValues_correct() {
        val recording = AudioRecording()
        assertEquals("", recording.audioFile)
        assertEquals(0L, recording.startTimeMs)
        assertEquals(0L, recording.durationMs)
    }

    @Test
    fun equality_matchesDataClassContract() {
        val a = AudioRecording(audioFile = "rec-001.opus", startTimeMs = 1000L, durationMs = 5000L)
        val b = AudioRecording(audioFile = "rec-001.opus", startTimeMs = 1000L, durationMs = 5000L)
        assertEquals(a, b)

        val c = AudioRecording(audioFile = "rec-002.opus", startTimeMs = 1000L, durationMs = 5000L)
        assertNotEquals(a, c)
    }
}
