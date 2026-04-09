package com.writer.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for timestamp offset handling in [SherpaTokenMerger].
 *
 * Bug: Sherpa resets its internal clock after each endpoint detection
 * (recognizer.reset(stream)), so timestamps for the second utterance
 * start from 0 again. Without an offset, all utterances' words would
 * have timestamps relative to their own segment start, not the
 * continuous audio recording.
 */
class SherpaTokenMergerOffsetTest {

    @Test
    fun `zero offset produces timestamps relative to segment`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" THE", " CAT"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 0f
        )
        assertEquals(1000L, result[0].startMs)
        assertEquals(1500L, result[1].startMs)
    }

    @Test
    fun `offset shifts all timestamps by cumulative audio duration`() {
        // Simulates second utterance: first utterance consumed 5 seconds of audio
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" THE", " CAT"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 5.0f
        )
        assertEquals(6000L, result[0].startMs) // 1.0 + 5.0 = 6.0s
        assertEquals(6500L, result[1].startMs) // 1.5 + 5.0 = 6.5s
    }

    @Test
    fun `endMs also includes offset`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" HELLO", " WORLD"),
            floatArrayOf(0.5f, 1.0f),
            offsetSec = 10.0f
        )
        // endMs of first word = next word's startMs
        assertEquals(11000L, result[0].endMs) // next word at 1.0 + 10.0 = 11.0s
        // endMs of last word = own timestamp
        assertEquals(11000L, result[1].endMs)
    }

    @Test
    fun `subword merging with offset`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" RETURN", "ED"),
            floatArrayOf(0.2f, 0.4f),
            offsetSec = 8.0f
        )
        assertEquals(1, result.size)
        assertEquals("returned", result[0].text)
        assertEquals(8200L, result[0].startMs)
        assertEquals(8400L, result[0].endMs)
    }

    @Test
    fun `simulates multi-segment recording with two mergeTokens calls`() {
        // First utterance: 0-4 seconds of audio
        val segment1 = SherpaTokenMerger.mergeTokens(
            arrayOf(" HELLO", " WORLD"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 0f
        )

        // Second utterance: stream was reset, timestamps restart from 0
        // Audio offset = 4 seconds (total audio fed before reset)
        val segment2 = SherpaTokenMerger.mergeTokens(
            arrayOf(" GOOD", " BYE"),
            floatArrayOf(0.8f, 1.2f),
            offsetSec = 4.0f
        )

        // First segment: timestamps relative to start of recording
        assertEquals(1000L, segment1[0].startMs)
        assertEquals(1500L, segment1[1].startMs)

        // Second segment: timestamps shifted by 4 seconds
        assertEquals(4800L, segment2[0].startMs) // 0.8 + 4.0
        assertEquals(5200L, segment2[1].startMs) // 1.2 + 4.0
    }
}
