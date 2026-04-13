package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MarkdownExporter] — markdown block building and
 * cue interleaving.
 */
class MarkdownExporterTest {

    private lateinit var lineSegmenter: LineSegmenter
    private lateinit var paragraphBuilder: ParagraphBuilder

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        lineSegmenter = LineSegmenter()
        paragraphBuilder = ParagraphBuilder(StrokeClassifier(lineSegmenter))
    }

    /** Create a simple stroke centered on the given line. */
    private fun strokeOnLine(lineIndex: Int, width: Float = 200f): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.4f
        return InkStroke(
            points = listOf(
                StrokePoint(50f, y, 0.5f, 0L),
                StrokePoint(50f + width, y, 0.5f, 1L),
                StrokePoint(50f + width, y + ls * 0.2f, 0.5f, 2L)
            )
        )
    }

    private fun block(start: Int, end: Int, text: String) =
        MarkdownExporter.MdBlock(start, end, text)

    // --- buildBlocks ---

    @Test
    fun `empty cache and no diagrams returns empty`() {
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = emptyMap(),
            activeStrokes = emptyList(),
            diagramAreas = emptyList(),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { false }
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single text line produces single block`() {
        val strokes = listOf(strokeOnLine(0))
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "Hello world"),
            activeStrokes = strokes,
            diagramAreas = emptyList(),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { false }
        )
        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].text)
        assertEquals(0, result[0].startLine)
        assertEquals(0, result[0].endLine)
    }

    @Test
    fun `consecutive plain lines merge into one paragraph`() {
        // Use narrow strokes (40px) to avoid underline/heading detection
        val strokes = listOf(strokeOnLine(0, width = 40f), strokeOnLine(1, width = 40f))
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "First line of text", 1 to "second line continues"),
            activeStrokes = strokes,
            diagramAreas = emptyList(),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { false }
        )
        assertEquals("Expected 1 block but got ${result.size}: $result", 1, result.size)
        assertTrue(result[0].text.contains("First"))
        assertTrue(result[0].text.contains("second"))
        assertEquals(0, result[0].startLine)
        assertEquals(1, result[0].endLine)
    }

    @Test
    fun `diagram between lines forces paragraph break`() {
        val strokes = listOf(strokeOnLine(0), strokeOnLine(4))
        val diagram = DiagramArea(startLineIndex = 2, heightInLines = 2)
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "Before", 4 to "After"),
            activeStrokes = strokes,
            diagramAreas = listOf(diagram),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { it in 2..3 }
        )
        // Text blocks should be separate because diagram sits between them
        val textBlocks = result.filter { !it.text.contains("diagram") }
        assertEquals(2, textBlocks.size)
        assertEquals("Before", textBlocks[0].text)
        assertEquals("After", textBlocks[1].text)
    }

    @Test
    fun `diagram lines excluded from text blocks`() {
        val strokes = listOf(strokeOnLine(0), strokeOnLine(2))
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "Text", 2 to "Should be excluded"),
            activeStrokes = strokes,
            diagramAreas = emptyList(),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { it == 2 }
        )
        // Only line 0 should produce a block
        assertTrue(result.all { !it.text.contains("excluded") })
        assertTrue(result.any { it.text == "Text" })
    }

    @Test
    fun `diagram area produces block via svgEncoder`() {
        val strokes = listOf(strokeOnLine(2))
        val area = DiagramArea(startLineIndex = 2, heightInLines = 2)
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = emptyMap(),
            activeStrokes = strokes,
            diagramAreas = listOf(area),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { it in 2..3 },
            svgEncoder = { _, _, _, _ -> "![diagram](test-svg)" }
        )
        assertEquals(1, result.size)
        assertEquals("![diagram](test-svg)", result[0].text)
        assertEquals(2, result[0].startLine)
        assertEquals(3, result[0].endLine)
    }

    @Test
    fun `text and diagram blocks sorted by startLine`() {
        val strokes = listOf(strokeOnLine(0), strokeOnLine(3))
        val diagram = DiagramArea(startLineIndex = 3, heightInLines = 1)
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "Text"),
            activeStrokes = strokes,
            diagramAreas = listOf(diagram),
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { it == 3 },
            svgEncoder = { _, _, _, _ -> "![diagram](svg)" }
        )
        assertEquals(2, result.size)
        assertEquals(0, result[0].startLine)
        assertEquals(3, result[1].startLine)
    }

    // --- buildText (cue interleaving) ---

    @Test
    fun `no cues produces plain markdown`() {
        val result = MarkdownExporter.buildText(
            listOf(block(0, 1, "Intro"), block(2, 4, "Body")),
            emptyList()
        )
        assertEquals("Intro\n\nBody", result)
    }

    @Test
    fun `single cue appends blockquote`() {
        val result = MarkdownExporter.buildText(
            listOf(block(0, 2, "Content")),
            listOf(block(0, 0, "KEY"))
        )
        assertTrue(result.contains("> **Cue:** KEY"))
    }

    @Test
    fun `multiple cues merged into one blockquote`() {
        val result = MarkdownExporter.buildText(
            listOf(block(0, 4, "Long paragraph")),
            listOf(block(0, 0, "First"), block(3, 3, "Second"))
        )
        assertTrue(result.contains("> **Cue:**\n> First\n> Second"))
    }

    @Test
    fun `cues only for matching paragraph`() {
        val result = MarkdownExporter.buildText(
            listOf(block(0, 1, "Paragraph A"), block(3, 5, "Paragraph B")),
            listOf(block(4, 4, "Cue for B"))
        )
        assertTrue(!result.substringBefore("Paragraph B").contains("Cue"))
        assertTrue(result.substringAfter("Paragraph B").contains("> **Cue:** Cue for B"))
    }

    @Test
    fun `empty main blocks returns empty string`() {
        val result = MarkdownExporter.buildText(emptyList(), listOf(block(0, 0, "Orphan cue")))
        assertEquals("", result)
    }

    // --- TextBlock support ---

    @Test
    fun `textBlock appears at correct line position`() {
        val textBlocks = listOf(
            TextBlock(id = "tb1", startLineIndex = 3, heightInLines = 1, text = "voice memo")
        )
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = emptyMap(),
            activeStrokes = emptyList(),
            diagramAreas = emptyList(),
            textBlocks = textBlocks,
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { false }
        )
        assertEquals(1, result.size)
        assertEquals("voice memo", result[0].text)
        assertEquals(3, result[0].startLine)
        assertEquals(3, result[0].endLine)
    }

    @Test
    fun `textBlock with empty text is skipped`() {
        val textBlocks = listOf(
            TextBlock(id = "tb1", startLineIndex = 2, heightInLines = 1, text = "")
        )
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "hello"),
            activeStrokes = listOf(strokeOnLine(0)),
            diagramAreas = emptyList(),
            textBlocks = textBlocks,
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { false }
        )
        assertTrue("Empty TextBlock should be skipped", result.none { it.startLine == 2 })
    }

    @Test
    fun `textBlock and diagram coexist`() {
        val diagramArea = DiagramArea(id = "d1", startLineIndex = 5, heightInLines = 3)
        val diagramStroke = strokeOnLine(6)
        val textBlocks = listOf(
            TextBlock(id = "tb1", startLineIndex = 2, heightInLines = 1, text = "memo text")
        )
        val result = MarkdownExporter.buildBlocks(
            lineTextCache = mapOf(0 to "hello"),
            activeStrokes = listOf(strokeOnLine(0), diagramStroke),
            diagramAreas = listOf(diagramArea),
            textBlocks = textBlocks,
            writingWidth = 800f,
            paragraphBuilder = paragraphBuilder,
            lineSegmenter = lineSegmenter,
            isDiagramLine = { diagramArea.containsLine(it) },
            svgEncoder = { _, _, _, _ -> "![diagram](data:test)" }
        )
        assertTrue("Should have TextBlock", result.any { it.text == "memo text" })
        assertTrue("Should have diagram", result.any { it.text.contains("diagram") })
    }
}
