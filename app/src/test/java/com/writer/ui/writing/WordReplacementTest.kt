package com.writer.ui.writing

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that selecting a word alternative replaces only the tapped word
 * in the tapped line, not other occurrences or partial matches.
 */
class WordReplacementTest {

    /**
     * Replace a whole word at a specific position in the text.
     * Uses word-boundary-aware replacement to avoid substring matches.
     */
    companion object {
        fun replaceWordAt(text: String, wordIndex: Int, oldWord: String, newWord: String): String {
            val words = text.split(" ").toMutableList()
            if (wordIndex < 0 || wordIndex >= words.size) return text
            if (words[wordIndex] == oldWord) {
                words[wordIndex] = newWord
            }
            return words.joinToString(" ")
        }

        /** Replace first whole-word match (not substring). */
        fun replaceWholeWord(text: String, oldWord: String, newWord: String): String {
            val words = text.split(" ").toMutableList()
            val idx = words.indexOf(oldWord)
            if (idx >= 0) words[idx] = newWord
            return words.joinToString(" ")
        }
    }

    // ── Scoped to tapped line only ──────────────────────────────────────

    @Test
    fun `replacing a word changes only that word in the line`() {
        val result = replaceWholeWord("the quick brown fox", "quick", "slow")
        assertEquals("the slow brown fox", result)
    }

    @Test
    fun `replacing a word does not affect other lines`() {
        val lineTextCache = mutableMapOf(
            0 to "the quick brown fox",
            1 to "jumps over the quick dog",
            2 to "a quick summary"
        )

        // Only replace in line 0
        lineTextCache[0] = replaceWholeWord(lineTextCache[0]!!, "quick", "slow")

        assertEquals("the slow brown fox", lineTextCache[0])
        assertEquals("jumps over the quick dog", lineTextCache[1])
        assertEquals("a quick summary", lineTextCache[2])
    }

    // ── Whole-word matching ─────────────────────────────────────────────

    @Test
    fun `does not match partial words`() {
        val result = replaceWholeWord("there is the thing", "the", "a")
        assertEquals("there is a thing", result)  // "there" and "thing" untouched
    }

    @Test
    fun `does not match substring at start of word`() {
        val result = replaceWholeWord("they went there", "the", "a")
        assertEquals("they went there", result)  // no whole-word "the" exists
    }

    // ── Duplicate words ─────────────────────────────────────────────────

    @Test
    fun `replacing word that appears twice replaces only first`() {
        val result = replaceWholeWord("the the quick fox", "the", "a")
        assertEquals("a the quick fox", result)
    }

    // ── Position-based replacement ──────────────────────────────────────

    @Test
    fun `replaceWordAt targets specific position`() {
        val text = "the quick the fox"
        assertEquals("a quick the fox", replaceWordAt(text, 0, "the", "a"))
        assertEquals("the quick a fox", replaceWordAt(text, 2, "the", "a"))
    }

    @Test
    fun `replaceWordAt does nothing if word does not match at position`() {
        val text = "the quick brown fox"
        assertEquals("the quick brown fox", replaceWordAt(text, 1, "brown", "red"))  // wrong position
    }

    @Test
    fun `replaceWordAt with out of bounds index is no-op`() {
        val text = "hello world"
        assertEquals("hello world", replaceWordAt(text, 5, "hello", "hi"))
        assertEquals("hello world", replaceWordAt(text, -1, "hello", "hi"))
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `replacing with same word is a no-op`() {
        assertEquals("hello world", replaceWholeWord("hello world", "hello", "hello"))
    }

    @Test
    fun `replacing last word works`() {
        assertEquals("the quick dog", replaceWholeWord("the quick fox", "fox", "dog"))
    }

    @Test
    fun `replacing first word works`() {
        assertEquals("a quick fox", replaceWholeWord("the quick fox", "the", "a"))
    }

    @Test
    fun `word replacement is case sensitive`() {
        val result = replaceWholeWord("The quick The fox", "The", "A")
        assertEquals("A quick The fox", result)
    }

    @Test
    fun `single word line replacement`() {
        assertEquals("world", replaceWholeWord("hello", "hello", "world"))
    }

    @Test
    fun `empty text is no-op`() {
        assertEquals("", replaceWholeWord("", "hello", "world"))
    }
}
