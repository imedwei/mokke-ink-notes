package com.writer.recognition

import com.writer.model.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTokenMergerTest {

    // The merger subtracts LATENCY_COMPENSATION_MS (320ms) and clamps to 0.
    // Tests use timestamps large enough that compensation doesn't clamp to 0,
    // so we can verify the math.
    private val comp = 320L // must match SherpaTokenMerger.LATENCY_COMPENSATION_MS

    @Test
    fun `empty tokens returns empty list`() {
        val result = SherpaTokenMerger.mergeTokens(emptyArray(), floatArrayOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single word token`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO"),
            floatArrayOf(1.0f)
        )
        assertEquals(1, result.size)
        assertEquals("hello", result[0].text)
        assertEquals(1000L - comp, result[0].startMs)
    }

    @Test
    fun `multiple whole words`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581THE", "\u2581CAT", "\u2581SAT"),
            floatArrayOf(1.0f, 1.5f, 2.0f)
        )
        assertEquals(3, result.size)
        assertEquals("the", result[0].text)
        assertEquals("cat", result[1].text)
        assertEquals("sat", result[2].text)
        assertEquals(1000L - comp, result[0].startMs)
        assertEquals(1500L - comp, result[1].startMs)
        assertEquals(2000L - comp, result[2].startMs)
    }

    @Test
    fun `subword merging`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581RETURN", "ED"),
            floatArrayOf(2.0f, 2.3f)
        )
        assertEquals(1, result.size)
        assertEquals("returned", result[0].text)
        assertEquals(2000L - comp, result[0].startMs)
        assertEquals(2300L - comp, result[0].endMs)
    }

    @Test
    fun `multiple subwords`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581UN", "BELIEV", "ABLE"),
            floatArrayOf(1.0f, 1.2f, 1.5f)
        )
        assertEquals(1, result.size)
        assertEquals("unbelievable", result[0].text)
        assertEquals(1000L - comp, result[0].startMs)
        assertEquals(1500L - comp, result[0].endMs)
    }

    @Test
    fun `mixed whole and subwords`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581THE", "\u2581RETURN", "ED", "\u2581CAT"),
            floatArrayOf(1.0f, 1.5f, 1.8f, 2.2f)
        )
        assertEquals(3, result.size)
        assertEquals("the", result[0].text)
        assertEquals("returned", result[1].text)
        assertEquals("cat", result[2].text)
        assertEquals(1500L - comp, result[1].startMs)
        assertEquals(2200L - comp, result[1].endMs) // endMs = next word's startMs
    }

    @Test
    fun `word endMs uses next word startMs when available`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581THE", "\u2581CAT"),
            floatArrayOf(1.0f, 1.5f)
        )
        assertEquals(1500L - comp, result[0].endMs)
        assertEquals(1500L - comp, result[1].endMs)
    }

    @Test
    fun `confidence is always 1f`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO", "\u2581WORLD"),
            floatArrayOf(1.0f, 1.5f)
        )
        result.forEach { assertEquals(1.0f, it.confidence) }
    }

    @Test
    fun `blank and empty tokens are skipped`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("", "\u2581HELLO", " ", "\u2581WORLD"),
            floatArrayOf(0.0f, 1.0f, 1.5f, 2.0f)
        )
        assertEquals(2, result.size)
        assertEquals("hello", result[0].text)
        assertEquals("world", result[1].text)
    }

    @Test
    fun `reconstructed text matches joined words`() {
        val tokens = arrayOf("\u2581THERE", "\u2581WAS", "\u2581SOME", "THING", "\u2581IN", "\u2581HIS")
        val timestamps = floatArrayOf(1.0f, 1.3f, 1.6f, 1.8f, 2.0f, 2.2f)
        val result = SherpaTokenMerger.mergeTokens(tokens, timestamps)
        assertEquals("there was something in his", result.joinToString(" ") { it.text })
    }

    @Test
    fun `mismatched token and timestamp lengths uses minimum`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO", "\u2581WORLD", "\u2581EXTRA"),
            floatArrayOf(1.0f, 1.5f)
        )
        assertEquals(2, result.size)
        assertEquals("hello", result[0].text)
        assertEquals("world", result[1].text)
    }

    @Test
    fun `tokens without word boundary prefix at start`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("ED", "\u2581THE"),
            floatArrayOf(1.0f, 1.5f)
        )
        assertEquals(2, result.size)
        assertEquals("ed", result[0].text)
        assertEquals("the", result[1].text)
    }

    @Test
    fun `space-prefixed tokens treated as word boundaries`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" TESTING", " THIS", " NEW", "VERSION"),
            floatArrayOf(1.0f, 1.5f, 2.0f, 2.3f)
        )
        assertEquals(3, result.size)
        assertEquals("testing", result[0].text)
        assertEquals("this", result[1].text)
        assertEquals("newversion", result[2].text)
        assertEquals(1000L - comp, result[0].startMs)
        assertEquals(1500L - comp, result[1].startMs)
        assertEquals(2000L - comp, result[2].startMs)
    }

    @Test
    fun `mixed space and sentencepiece boundaries`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" THE", "\u2581CAT", " SAT"),
            floatArrayOf(1.0f, 1.5f, 2.0f)
        )
        assertEquals(3, result.size)
        assertEquals("the", result[0].text)
        assertEquals("cat", result[1].text)
        assertEquals("sat", result[2].text)
    }

    @Test
    fun `timestamps near zero are clamped to zero`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO"),
            floatArrayOf(0.1f) // 100ms - comp = negative → clamped to 0
        )
        assertEquals(0L, result[0].startMs)
    }
}
