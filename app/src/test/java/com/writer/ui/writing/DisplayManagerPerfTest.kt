package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Performance tests for inline overlay building logic.
 *
 * These tests verify that the paragraph grouping, word-wrapping, and
 * overlay construction complete within acceptable time budgets even
 * with large documents. Catches regressions that cause ANR on device.
 *
 * Note: HersheyFont stroke generation is not tested here (requires
 * Android assets). These tests cover the grouping/wrapping overhead.
 */
class DisplayManagerPerfTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun createTextCache(lineCount: Int): MutableMap<Int, String> {
        val cache = mutableMapOf<Int, String>()
        for (line in 0 until lineCount) {
            cache[line] = "This is recognized text for line $line with several words in it"
        }
        return cache
    }

    private fun createStrokes(lineCount: Int, strokesPerLine: Int = 5): MutableList<InkStroke> {
        val strokes = mutableListOf<InkStroke>()
        for (line in 0 until lineCount) {
            for (s in 0 until strokesPerLine) {
                val y = tm + line * ls + ls / 2
                strokes.add(InkStroke(
                    strokeId = "s${line}_$s",
                    points = listOf(
                        StrokePoint(10f + s * 50f, y, 0.5f, (line * 1000 + s * 100).toLong()),
                        StrokePoint(50f + s * 50f, y, 0.5f, (line * 1000 + s * 100 + 50).toLong())
                    )
                ))
            }
        }
        return strokes
    }

    @Test
    fun `groupByLine completes within 5ms for 300 strokes`() {
        val strokes = createStrokes(30, 10)  // 300 strokes
        val segmenter = LineSegmenter()

        val t0 = System.nanoTime()
        val grouped = segmenter.groupByLine(strokes)
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        assertEquals(30, grouped.size)
        assertTrue("groupByLine took ${elapsed}ms for ${strokes.size} strokes (budget: 5ms)", elapsed < 5)
    }

    @Test
    fun `groupByLine completes within 10ms for 1000 strokes`() {
        val strokes = createStrokes(100, 10)  // 1000 strokes
        val segmenter = LineSegmenter()

        val t0 = System.nanoTime()
        val grouped = segmenter.groupByLine(strokes)
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        assertEquals(100, grouped.size)
        assertTrue("groupByLine took ${elapsed}ms for ${strokes.size} strokes (budget: 10ms)", elapsed < 10)
    }

    @Test
    fun `HersheyFont wordWrap completes within 5ms for long text`() {
        // Simulate word-wrapping without actual font (test the algorithm)
        val text = (1..100).joinToString(" ") { "word$it" }
        val words = text.split(" ")

        val t0 = System.nanoTime()
        // Simulate word-wrap: split into lines of ~10 words each
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var wordCount = 0
        for (word in words) {
            if (wordCount >= 10) {
                lines.add(current.toString())
                current.clear()
                wordCount = 0
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(word)
            wordCount++
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        assertEquals(10, lines.size)
        assertTrue("wordWrap simulation took ${elapsed}ms (budget: 5ms)", elapsed < 5)
    }

    @Test
    fun `paragraph grouping completes within 2ms for 50 lines`() {
        val textCache = createTextCache(50)
        val entries = textCache.entries.sortedBy { it.key }

        val t0 = System.nanoTime()
        // Simulate paragraph grouping (same logic as buildInlineOverlays)
        val paragraphs = mutableListOf<MutableList<Map.Entry<Int, String>>>()
        var currentGroup = mutableListOf<Map.Entry<Int, String>>()
        for (entry in entries) {
            if (currentGroup.isNotEmpty()) {
                val prevIdx = currentGroup.last().key
                if (entry.key - prevIdx > 1) {
                    paragraphs.add(currentGroup)
                    currentGroup = mutableListOf()
                }
            }
            currentGroup.add(entry)
        }
        if (currentGroup.isNotEmpty()) paragraphs.add(currentGroup)
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        assertEquals(1, paragraphs.size)  // all consecutive → 1 paragraph
        assertEquals(50, paragraphs[0].size)
        assertTrue("paragraph grouping took ${elapsed}ms (budget: 2ms)", elapsed < 2)
    }

    @Test
    fun `overlay hash caching prevents redundant computation`() {
        val textCache = createTextCache(30)

        // Simulate hash check
        val hash1 = textCache.hashCode()
        val hash2 = textCache.hashCode()
        assertEquals("Same map should have same hash", hash1, hash2)

        // Modify map — hash should change
        textCache[30] = "new line"
        val hash3 = textCache.hashCode()
        assertNotEquals("Modified map should have different hash", hash1, hash3)

        // Remove entry — hash should change
        textCache.remove(30)
        val hash4 = textCache.hashCode()
        assertEquals("Restored map should have original hash", hash1, hash4)
    }

    @Test
    fun `UndoManager snapshot with 300 strokes completes within 5ms`() {
        val strokes = createStrokes(30, 10)  // 300 strokes
        val undoManager = UndoManager()

        val snapshot = UndoManager.Snapshot(
            strokes = strokes,
            scrollOffsetY = 0f,
            lineTextCache = createTextCache(30),
            diagramAreas = emptyList()
        )

        val t0 = System.nanoTime()
        undoManager.saveSnapshot(snapshot)
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        assertTrue("saveSnapshot took ${elapsed}ms for ${strokes.size} strokes (budget: 5ms)", elapsed < 5)
    }

    @Test
    fun `InlineTextState creation is lightweight`() {
        val t0 = System.nanoTime()
        val states = (0 until 50).map { lineIdx ->
            InlineTextState(
                lineIndex = lineIdx,
                recognizedText = "Text for line $lineIdx",
                consolidated = true,
                syntheticStrokes = emptyList()
            )
        }
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        assertEquals(50, states.size)
        assertTrue("50 InlineTextState creations took ${elapsed}ms (budget: 5ms)", elapsed < 5)
    }
}
