package com.writer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBlockTest {

    @Test
    fun endLineIndex_computed() {
        val block = TextBlock(startLineIndex = 3, heightInLines = 4)
        assertEquals(6, block.endLineIndex)
    }

    @Test
    fun endLineIndex_singleLine() {
        val block = TextBlock(startLineIndex = 5, heightInLines = 1)
        assertEquals(5, block.endLineIndex)
    }

    @Test
    fun containsLine_insideRange_true() {
        val block = TextBlock(startLineIndex = 3, heightInLines = 4)
        assertTrue(block.containsLine(3))
        assertTrue(block.containsLine(5))
        assertTrue(block.containsLine(6))
    }

    @Test
    fun containsLine_outsideRange_false() {
        val block = TextBlock(startLineIndex = 3, heightInLines = 4)
        assertFalse(block.containsLine(2))
        assertFalse(block.containsLine(7))
    }

    @Test
    fun defaultValues_correct() {
        val block = TextBlock(startLineIndex = 0, heightInLines = 1)
        assertEquals("", block.text)
        assertEquals("", block.audioFile)
        assertEquals(0L, block.audioStartMs)
        assertEquals(0L, block.audioEndMs)
    }

    @Test
    fun equality_matchesDataClassContract() {
        val id = "test-id"
        val a = TextBlock(id = id, startLineIndex = 1, heightInLines = 2, text = "hello")
        val b = TextBlock(id = id, startLineIndex = 1, heightInLines = 2, text = "hello")
        assertEquals(a, b)

        val c = TextBlock(id = id, startLineIndex = 1, heightInLines = 2, text = "world")
        assertNotEquals(a, c)
    }
}
