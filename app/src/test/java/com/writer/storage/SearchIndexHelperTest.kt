package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DocumentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for search index helper functions.
 *
 * Tests the logic for building searchable text from DocumentData
 * and finding per-line matches from search results — no Android context needed.
 */
class SearchIndexHelperTest {

    private fun docWithText(
        mainLines: Map<Int, String> = emptyMap(),
        cueLines: Map<Int, String> = emptyMap()
    ) = DocumentData(
        main = ColumnData(lineTextCache = mainLines),
        cue = ColumnData(lineTextCache = cueLines)
    )

    // ── buildBodyText ─────────────────────────────────────────────────────

    @Test
    fun buildBodyText_concatenatesMainLines() {
        val data = docWithText(mainLines = mapOf(0 to "hello", 1 to "world"))
        val body = SearchIndexManager.buildBodyText(data)
        assertTrue("Body should contain 'hello'", body.contains("hello"))
        assertTrue("Body should contain 'world'", body.contains("world"))
    }

    @Test
    fun buildBodyText_includesCueLines() {
        val data = docWithText(
            mainLines = mapOf(0 to "main text"),
            cueLines = mapOf(0 to "cue text")
        )
        val body = SearchIndexManager.buildBodyText(data)
        assertTrue("Body should contain main text", body.contains("main text"))
        assertTrue("Body should contain cue text", body.contains("cue text"))
    }

    @Test
    fun buildBodyText_lowercasesText() {
        val data = docWithText(mainLines = mapOf(0 to "Hello World"))
        val body = SearchIndexManager.buildBodyText(data)
        assertTrue("Body should be lowercase", body.contains("hello world"))
    }

    @Test
    fun buildBodyText_skipsEmptyLines() {
        val data = docWithText(mainLines = mapOf(0 to "hello", 1 to "", 2 to "world"))
        val body = SearchIndexManager.buildBodyText(data)
        assertTrue("Body should contain 'hello'", body.contains("hello"))
        assertTrue("Body should contain 'world'", body.contains("world"))
    }

    @Test
    fun buildBodyText_emptyDocument_returnsEmpty() {
        val data = docWithText()
        val body = SearchIndexManager.buildBodyText(data)
        assertTrue("Empty doc should produce empty/blank body", body.isBlank())
    }

    // ── buildLineDataJson ─────────────────────────────────────────────────

    @Test
    fun buildLineDataJson_mainLinesUseNumericKeys() {
        val data = docWithText(mainLines = mapOf(0 to "hello", 3 to "world"))
        val json = SearchIndexManager.buildLineDataJson(data)
        assertEquals("hello", json.getString("0"))
        assertEquals("world", json.getString("3"))
    }

    @Test
    fun buildLineDataJson_cueLinesUseCPrefix() {
        val data = docWithText(cueLines = mapOf(0 to "cue text", 2 to "more cue"))
        val json = SearchIndexManager.buildLineDataJson(data)
        assertEquals("cue text", json.getString("c0"))
        assertEquals("more cue", json.getString("c2"))
    }

    @Test
    fun buildLineDataJson_lowercasesText() {
        val data = docWithText(mainLines = mapOf(0 to "Hello World"))
        val json = SearchIndexManager.buildLineDataJson(data)
        assertEquals("hello world", json.getString("0"))
    }

    @Test
    fun buildLineDataJson_skipsEmptyLines() {
        val data = docWithText(mainLines = mapOf(0 to "hello", 1 to ""))
        val json = SearchIndexManager.buildLineDataJson(data)
        assertTrue("Should have key 0", json.has("0"))
        assertEquals("Should not have key 1", false, json.has("1"))
    }

    @Test
    fun buildLineDataJson_mixesMainAndCue() {
        val data = docWithText(
            mainLines = mapOf(0 to "main zero"),
            cueLines = mapOf(0 to "cue zero")
        )
        val json = SearchIndexManager.buildLineDataJson(data)
        assertEquals("main zero", json.getString("0"))
        assertEquals("cue zero", json.getString("c0"))
    }

    @Test
    fun buildLineDataJson_emptyDocument_returnsEmptyJson() {
        val data = docWithText()
        val json = SearchIndexManager.buildLineDataJson(data)
        assertEquals("Empty doc should produce empty JSON", 0, json.length())
    }

    // ── findLineMatches ───────────────────────────────────────────────────

    @Test
    fun findLineMatches_findsMatchingLines() {
        val lineData = """{"0":"hello world","1":"goodbye","2":"hello again"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "hello")
        assertEquals("Should find 2 matches", 2, matches.size)
        assertTrue("Should match line 0", matches.any { it.lineIndex == 0 })
        assertTrue("Should match line 2", matches.any { it.lineIndex == 2 })
    }

    @Test
    fun findLineMatches_noMatches_returnsEmpty() {
        val lineData = """{"0":"hello world","1":"goodbye"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "nonexistent")
        assertTrue("No matches expected", matches.isEmpty())
    }

    @Test
    fun findLineMatches_caseInsensitive() {
        val lineData = """{"0":"Hello World"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "hello")
        assertEquals("Should find case-insensitive match", 1, matches.size)
    }

    @Test
    fun findLineMatches_handlesCuePrefix() {
        val lineData = """{"0":"main text","c0":"cue text","c1":"more cue"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "cue")
        assertEquals("Should find 2 cue matches", 2, matches.size)
    }

    @Test
    fun findLineMatches_sortedByLineIndex() {
        val lineData = """{"5":"match","2":"match","8":"match"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "match")
        val indices = matches.map { it.lineIndex }
        assertEquals("Should be sorted", listOf(2, 5, 8), indices)
    }

    @Test
    fun findLineMatches_emptyQuery_returnsEmpty() {
        val lineData = """{"0":"hello world"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "")
        assertTrue("Empty query should return no matches", matches.isEmpty())
    }

    @Test
    fun findLineMatches_emptyLineData_returnsEmpty() {
        val matches = SearchIndexManager.findLineMatches("{}", "hello")
        assertTrue("Empty line data should return no matches", matches.isEmpty())
    }

    @Test
    fun findLineMatches_substringMatch() {
        val lineData = """{"0":"concatenation"}"""
        val matches = SearchIndexManager.findLineMatches(lineData, "cat")
        assertEquals("Should find substring match", 1, matches.size)
    }
}
