package com.writer.recognition

import com.writer.model.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTokenMergerTest {

    @Test
    fun `empty tokens returns empty list`() {
        val result = SherpaTokenMerger.mergeTokens(emptyArray(), floatArrayOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single word token`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO"),
            floatArrayOf(0.5f)
        )
        assertEquals(1, result.size)
        assertEquals("hello", result[0].text)
        assertEquals(500L, result[0].startMs)
    }

    @Test
    fun `multiple whole words`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581THE", "\u2581CAT", "\u2581SAT"),
            floatArrayOf(0.0f, 0.3f, 0.6f)
        )
        assertEquals(3, result.size)
        assertEquals("the", result[0].text)
        assertEquals("cat", result[1].text)
        assertEquals("sat", result[2].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(300L, result[1].startMs)
        assertEquals(600L, result[2].startMs)
    }

    @Test
    fun `subword merging`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581RETURN", "ed"),
            floatArrayOf(1.0f, 1.3f)
        )
        assertEquals(1, result.size)
        assertEquals("returned", result[0].text)
        assertEquals(1000L, result[0].startMs)
        assertEquals(1300L, result[0].endMs)
    }

    @Test
    fun `multiple subwords`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581UN", "BELIEV", "ABLE"),
            floatArrayOf(0.0f, 0.2f, 0.5f)
        )
        assertEquals(1, result.size)
        assertEquals("unbelievable", result[0].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(500L, result[0].endMs)
    }

    @Test
    fun `mixed whole and subwords`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581THE", "\u2581RETURN", "ed", "\u2581CAT"),
            floatArrayOf(0.0f, 0.3f, 0.5f, 0.8f)
        )
        assertEquals(3, result.size)
        assertEquals("the", result[0].text)
        assertEquals("returned", result[1].text)
        assertEquals("cat", result[2].text)
        assertEquals(300L, result[1].startMs)
        assertEquals(800L, result[1].endMs) // endMs = next word's startMs
    }

    @Test
    fun `word endMs uses next word startMs when available`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581THE", "\u2581CAT"),
            floatArrayOf(0.0f, 0.5f)
        )
        assertEquals(500L, result[0].endMs) // ends at next word's start
        assertEquals(500L, result[1].endMs) // last word: endMs = own startMs
    }

    @Test
    fun `confidence is always 1f`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO", "\u2581WORLD"),
            floatArrayOf(0.0f, 0.5f)
        )
        result.forEach { assertEquals(1.0f, it.confidence) }
    }

    @Test
    fun `blank and empty tokens are skipped`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("", "\u2581HELLO", " ", "\u2581WORLD"),
            floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f)
        )
        assertEquals(2, result.size)
        assertEquals("hello", result[0].text)
        assertEquals("world", result[1].text)
    }

    @Test
    fun `reconstructed text matches joined words`() {
        val tokens = arrayOf("\u2581THERE", "\u2581WAS", "\u2581SOME", "THING", "\u2581IN", "\u2581HIS")
        val timestamps = floatArrayOf(0.0f, 0.3f, 0.6f, 0.8f, 1.0f, 1.2f)
        val result = SherpaTokenMerger.mergeTokens(tokens, timestamps)
        assertEquals("there was something in his", result.joinToString(" ") { it.text })
    }

    @Test
    fun `mismatched token and timestamp lengths uses minimum`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("\u2581HELLO", "\u2581WORLD", "\u2581EXTRA"),
            floatArrayOf(0.0f, 0.5f) // only 2 timestamps for 3 tokens
        )
        assertEquals(2, result.size)
        assertEquals("hello", result[0].text)
        assertEquals("world", result[1].text)
    }

    @Test
    fun `tokens without word boundary prefix at start`() {
        // Edge case: first token has no ▁ prefix (orphan continuation)
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf("ed", "\u2581THE"),
            floatArrayOf(0.0f, 0.3f)
        )
        assertEquals(2, result.size)
        assertEquals("ed", result[0].text)
        assertEquals("the", result[1].text)
    }

    @Test
    fun `space-prefixed tokens treated as word boundaries`() {
        // Some models use regular space instead of ▁ (U+2581)
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" TESTING", " THIS", " NEW", "VERSION"),
            floatArrayOf(0.5f, 1.0f, 1.5f, 1.8f)
        )
        assertEquals(3, result.size)
        assertEquals("testing", result[0].text)
        assertEquals("this", result[1].text)
        assertEquals("newversion", result[2].text)
        assertEquals(500L, result[0].startMs)
        assertEquals(1000L, result[1].startMs)
        assertEquals(1500L, result[2].startMs)
    }

    @Test
    fun `mixed space and sentencepiece boundaries`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" THE", "\u2581CAT", " SAT"),
            floatArrayOf(0.0f, 0.5f, 1.0f)
        )
        assertEquals(3, result.size)
        assertEquals("the", result[0].text)
        assertEquals("cat", result[1].text)
        assertEquals("sat", result[2].text)
    }
}
