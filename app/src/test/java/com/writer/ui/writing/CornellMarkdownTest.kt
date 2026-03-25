package com.writer.ui.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Cornell Notes markdown export — cue blockquotes interleaved with main content.
 */
class CornellMarkdownTest {

    private fun block(start: Int, end: Int, text: String) =
        WritingCoordinator.MdBlock(start, end, text)

    @Test fun noCues_producesPlainMarkdown() {
        val mainBlocks = listOf(
            block(0, 1, "Introduction"),
            block(2, 4, "Main content here")
        )
        val result = buildMarkdown(mainBlocks, emptyList())
        assertEquals("Introduction\n\nMain content here", result)
    }

    @Test fun singleCue_appendsBlockquote() {
        val mainBlocks = listOf(
            block(0, 2, "Introduction to concepts")
        )
        val cueBlocks = listOf(
            block(0, 0, "KEY CONCEPT")
        )
        val result = buildMarkdown(mainBlocks, cueBlocks)
        assertTrue(result.contains("> **Cue:** KEY CONCEPT"))
    }

    @Test fun multipleCues_mergedIntoOneBlockquote() {
        val mainBlocks = listOf(
            block(0, 4, "Long paragraph spanning several lines")
        )
        val cueBlocks = listOf(
            block(0, 0, "First cue"),
            block(3, 3, "Second cue")
        )
        val result = buildMarkdown(mainBlocks, cueBlocks)
        assertTrue(result.contains("> **Cue:**\n> First cue\n> Second cue"))
    }

    @Test fun cuesOnlyForMatchingParagraph() {
        val mainBlocks = listOf(
            block(0, 1, "Paragraph A"),
            block(3, 5, "Paragraph B")
        )
        val cueBlocks = listOf(
            block(4, 4, "Cue for B")
        )
        val result = buildMarkdown(mainBlocks, cueBlocks)
        // Paragraph A should not have a cue
        assertTrue(!result.substringBefore("Paragraph B").contains("Cue"))
        // Paragraph B should have the cue
        assertTrue(result.substringAfter("Paragraph B").contains("> **Cue:** Cue for B"))
    }

    @Test fun paragraphWithNoCues_nothingAppended() {
        val mainBlocks = listOf(
            block(0, 1, "First paragraph"),
            block(3, 4, "Second paragraph"),
            block(6, 7, "Third paragraph")
        )
        val cueBlocks = listOf(
            block(3, 3, "Only for second")
        )
        val result = buildMarkdown(mainBlocks, cueBlocks)
        val parts = result.split("\n\n")
        assertEquals("First paragraph", parts[0])
        assertEquals("Second paragraph", parts[1])
        assertTrue(parts[2].startsWith("> **Cue:**"))
        assertEquals("Third paragraph", parts[3])
    }

    /** Simulate the markdown building logic from WritingCoordinator.getMarkdownText(cueBlocks). */
    private fun buildMarkdown(mainBlocks: List<WritingCoordinator.MdBlock>, cueBlocks: List<WritingCoordinator.MdBlock>): String {
        if (mainBlocks.isEmpty()) return ""
        val result = StringBuilder()
        for (block in mainBlocks) {
            if (result.isNotEmpty()) result.append("\n\n")
            result.append(block.text)

            val overlapping = cueBlocks.filter { cue ->
                cue.startLine <= block.endLine && cue.endLine >= block.startLine
            }
            if (overlapping.isNotEmpty()) {
                result.append("\n\n")
                if (overlapping.size == 1) {
                    result.append("> **Cue:** ${overlapping[0].text}")
                } else {
                    result.append("> **Cue:**")
                    for (cue in overlapping) {
                        result.append("\n> ${cue.text}")
                    }
                }
            }
        }
        return result.toString()
    }
}
