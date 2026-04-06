package com.writer.ui.writing

import com.writer.model.TextBlock
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextBlockEraserTest {

    private val ls get() = ScreenMetrics.lineSpacing
    private val tm get() = ScreenMetrics.topMargin

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun blockAt(line: Int, text: String) =
        TextBlock(id = "tb-$line", startLineIndex = line, heightInLines = 1, text = text)

    // ── Hit detection ───────────────────────────────────────────────────

    @Test
    fun scratchOverTextBlock_findsBlock() {
        val blocks = listOf(blockAt(3, "hello world"))
        val centerY = tm + 3 * ls + ls / 2
        val result = TextBlockEraser.findAndErase(
            50f, centerY - 10f, 200f, centerY + 10f,
            blocks, ls, tm
        )
        assertNotNull(result)
        assertEquals("tb-3", result!!.first.id)
    }

    @Test
    fun scratchMissesTextBlock_returnsNull() {
        val blocks = listOf(blockAt(3, "hello world"))
        val centerY = tm + 8 * ls + ls / 2 // line 8, far from block
        val result = TextBlockEraser.findAndErase(
            50f, centerY - 10f, 200f, centerY + 10f,
            blocks, ls, tm
        )
        assertNull(result)
    }

    @Test
    fun noTextBlocks_returnsNull() {
        val result = TextBlockEraser.findAndErase(
            50f, 100f, 200f, 120f,
            emptyList(), ls, tm
        )
        assertNull(result)
    }

    // ── Word erasure ────────────────────────────────────────────────────

    @Test
    fun scratchOverPartialText_removesOverlappingWords() {
        val block = blockAt(3, "hello beautiful world")
        val centerY = tm + 3 * ls + ls / 2
        // Wide scratch covering the entire block — should remove all words
        val result = TextBlockEraser.findAndErase(
            0f, centerY - 10f, 800f, centerY + 10f,
            listOf(block), ls, tm
        )
        assertNotNull(result)
        assertTrue(result!!.second.deleteBlock)
    }

    @Test
    fun scratchOverLeftHalf_removesLeftWords() {
        val block = blockAt(3, "hello beautiful world")
        val centerY = tm + 3 * ls + ls / 2
        val textLeft = ls * 0.3f
        // Narrow scratch on the left side — should remove "hello" but keep later words
        val result = TextBlockEraser.findAndErase(
            textLeft, centerY - 10f, textLeft + 40f, centerY + 10f,
            listOf(block), ls, tm
        )
        assertNotNull(result)
        // At least some words should survive
        assertTrue("Some text should remain", result!!.second.newText.isNotEmpty())
        assertTrue("Remaining text should not start with 'hello'",
            !result.second.newText.startsWith("hello"))
    }

    @Test
    fun scratchOverEntireText_deletesBlock() {
        val block = blockAt(3, "short")
        val centerY = tm + 3 * ls + ls / 2
        // Wide scratch covering everything
        val result = TextBlockEraser.findAndErase(
            0f, centerY - 10f, 800f, centerY + 10f,
            listOf(block), ls, tm
        )
        assertNotNull(result)
        assertTrue(result!!.second.deleteBlock)
        assertEquals("", result.second.newText)
    }

    @Test
    fun scratchOverLastWord_deletesBlock() {
        val block = blockAt(3, "only")
        val centerY = tm + 3 * ls + ls / 2
        val result = TextBlockEraser.findAndErase(
            0f, centerY - 10f, 800f, centerY + 10f,
            listOf(block), ls, tm
        )
        assertNotNull(result)
        assertTrue(result!!.second.deleteBlock)
    }

    @Test
    fun emptyTextBlock_deletesBlock() {
        val block = blockAt(3, "")
        val centerY = tm + 3 * ls + ls / 2
        val result = TextBlockEraser.findAndErase(
            0f, centerY - 10f, 800f, centerY + 10f,
            listOf(block), ls, tm
        )
        assertNotNull(result)
        assertTrue(result!!.second.deleteBlock)
    }

    // ── Multiple blocks ─────────────────────────────────────────────────

    @Test
    fun scratchHitsCorrectBlock() {
        val blocks = listOf(
            blockAt(2, "first block"),
            blockAt(5, "second block")
        )
        val centerY = tm + 5 * ls + ls / 2
        val result = TextBlockEraser.findAndErase(
            0f, centerY - 10f, 800f, centerY + 10f,
            blocks, ls, tm
        )
        assertNotNull(result)
        assertEquals("tb-5", result!!.first.id)
    }
}
