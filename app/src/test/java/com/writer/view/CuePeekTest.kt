package com.writer.view

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the cue peek feature:
 * - Dot hit-testing: which line index does a Y position correspond to?
 * - Contiguous block detection: given a pressed line, find the full block of
 *   consecutive cue lines (paragraphs/diagrams without gaps).
 */
class CuePeekTest {

    private lateinit var segmenter: LineSegmenter
    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        segmenter = LineSegmenter()
    }

    private fun strokeAtLine(lineIndex: Int, id: String = "s$lineIndex"): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.4f
        return InkStroke(
            strokeId = id,
            points = listOf(
                StrokePoint(50f, y, 0.5f, 0L),
                StrokePoint(200f, y + 5f, 0.5f, 100L)
            )
        )
    }

    // ── Dot hit-testing ──────────────────────────────────────────────────

    @Test
    fun dotLineIndex_firstLine() {
        val y = tm + ls / 2  // center of line 0
        val lineIndex = ((y - tm) / ls).toInt()
        assertEquals(0, lineIndex)
    }

    @Test
    fun dotLineIndex_thirdLine() {
        val y = tm + 3 * ls + ls / 2  // center of line 3
        val lineIndex = ((y - tm) / ls).toInt()
        assertEquals(3, lineIndex)
    }

    // ── Contiguous block detection ───────────────────────────────────────

    @Test
    fun singleLineCue_returnsJustThatLine() {
        val cueStrokes = listOf(strokeAtLine(3))
        val block = findContiguousCueBlock(3, cueStrokes)
        assertEquals(listOf(3), block)
    }

    @Test
    fun consecutiveLines_returnsFullBlock() {
        val cueStrokes = listOf(
            strokeAtLine(3),
            strokeAtLine(4),
            strokeAtLine(5)
        )
        val block = findContiguousCueBlock(4, cueStrokes)
        assertEquals(listOf(3, 4, 5), block)
    }

    @Test
    fun gapInLines_stopsAtGap() {
        val cueStrokes = listOf(
            strokeAtLine(2),
            strokeAtLine(3),
            // gap at line 4
            strokeAtLine(5),
            strokeAtLine(6)
        )
        // Pressing line 3 should only include 2-3, not 5-6
        val block = findContiguousCueBlock(3, cueStrokes)
        assertEquals(listOf(2, 3), block)
    }

    @Test
    fun pressedLineNotInCues_returnsEmpty() {
        val cueStrokes = listOf(strokeAtLine(5))
        val block = findContiguousCueBlock(3, cueStrokes)
        assertTrue(block.isEmpty())
    }

    @Test
    fun expandsUpAndDown() {
        val cueStrokes = listOf(
            strokeAtLine(1),
            strokeAtLine(2),
            strokeAtLine(3),
            strokeAtLine(4),
            strokeAtLine(5)
        )
        val block = findContiguousCueBlock(3, cueStrokes)
        assertEquals(listOf(1, 2, 3, 4, 5), block)
    }

    @Test
    fun multipleStrokesPerLine_stillOneBlock() {
        val cueStrokes = listOf(
            strokeAtLine(3, "s3a"),
            strokeAtLine(3, "s3b"),
            strokeAtLine(4, "s4a"),
            strokeAtLine(4, "s4b")
        )
        val block = findContiguousCueBlock(3, cueStrokes)
        assertEquals(listOf(3, 4), block)
    }

    @Test
    fun collectStrokesForBlock_returnsAllStrokesInRange() {
        val s3a = strokeAtLine(3, "s3a")
        val s3b = strokeAtLine(3, "s3b")
        val s4 = strokeAtLine(4, "s4")
        val s6 = strokeAtLine(6, "s6") // not in block
        val cueStrokes = listOf(s3a, s3b, s4, s6)

        val block = findContiguousCueBlock(3, cueStrokes)
        assertEquals(listOf(3, 4), block)

        val blockStrokes = collectStrokesForBlock(block, cueStrokes)
        assertEquals(3, blockStrokes.size)
        assertTrue(blockStrokes.contains(s3a))
        assertTrue(blockStrokes.contains(s3b))
        assertTrue(blockStrokes.contains(s4))
        assertFalse(blockStrokes.contains(s6))
    }

    // ── Contiguous block with diagram areas ────────────────────────────────

    @Test
    fun diagramAreaBridgesGap() {
        // Strokes on lines 2 and 5, diagram area spanning 2-5 bridges the gap
        val cueStrokes = listOf(strokeAtLine(2), strokeAtLine(5))
        val diagramAreas = listOf(DiagramArea(startLineIndex = 2, heightInLines = 4)) // lines 2-5
        val block = findContiguousCueBlock(2, cueStrokes, diagramAreas)
        assertEquals(listOf(2, 3, 4, 5), block)
    }

    @Test
    fun diagramAreaExtendsBlock() {
        // Strokes on lines 3, 4. Diagram area 4-7 extends the block downward.
        val cueStrokes = listOf(strokeAtLine(3), strokeAtLine(4))
        val diagramAreas = listOf(DiagramArea(startLineIndex = 4, heightInLines = 4)) // lines 4-7
        val block = findContiguousCueBlock(3, cueStrokes, diagramAreas)
        assertEquals(listOf(3, 4, 5, 6, 7), block)
    }

    @Test
    fun noDiagramArea_gapStillStops() {
        val cueStrokes = listOf(strokeAtLine(2), strokeAtLine(5))
        val block = findContiguousCueBlock(2, cueStrokes, emptyList())
        assertEquals(listOf(2), block)
    }

    // ── Strip visual grouping ────────────────────────────────────────────

    @Test
    fun computeBlocks_singleDot() {
        val blocks = computeVisualBlocks(setOf(3), emptyList())
        assertEquals(1, blocks.size)
        assertEquals(3, blocks[0].first)
        assertEquals(3, blocks[0].last)
    }

    @Test
    fun computeBlocks_consecutiveLines_oneSegment() {
        val blocks = computeVisualBlocks(setOf(3, 4, 5), emptyList())
        assertEquals(1, blocks.size)
        assertEquals(3, blocks[0].first)
        assertEquals(5, blocks[0].last)
    }

    @Test
    fun computeBlocks_twoSeparateBlocks() {
        val blocks = computeVisualBlocks(setOf(1, 2, 5, 6), emptyList())
        assertEquals(2, blocks.size)
        assertEquals(1, blocks[0].first)
        assertEquals(2, blocks[0].last)
        assertEquals(5, blocks[1].first)
        assertEquals(6, blocks[1].last)
    }

    @Test
    fun computeBlocks_diagramBridgesGap() {
        // Lines 2 and 5 have strokes, diagram 2-5 bridges → one block
        val diagramAreas = listOf(DiagramArea(startLineIndex = 2, heightInLines = 4))
        val blocks = computeVisualBlocks(setOf(2, 5), diagramAreas)
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].first)
        assertEquals(5, blocks[0].last)
    }

    @Test
    fun computeBlocks_empty() {
        val blocks = computeVisualBlocks(emptySet(), emptyList())
        assertTrue(blocks.isEmpty())
    }

    // ── Helper functions (same logic that will be in the production code) ─

    private fun findContiguousCueBlock(
        pressedLine: Int,
        cueStrokes: List<InkStroke>,
        diagramAreas: List<DiagramArea> = emptyList()
    ): List<Int> {
        val strokeLines = cueStrokes.map { segmenter.getStrokeLineIndex(it) }.toSet()
        val diagramLines = mutableSetOf<Int>()
        for (area in diagramAreas) {
            for (l in area.startLineIndex..area.endLineIndex) diagramLines.add(l)
        }
        val occupiedLines = strokeLines + diagramLines
        if (pressedLine !in occupiedLines) return emptyList()

        var top = pressedLine
        while (top - 1 in occupiedLines) top--
        var bottom = pressedLine
        while (bottom + 1 in occupiedLines) bottom++

        return (top..bottom).toList()
    }

    private fun collectStrokesForBlock(blockLines: List<Int>, cueStrokes: List<InkStroke>): List<InkStroke> {
        val lineSet = blockLines.toSet()
        return cueStrokes.filter { segmenter.getStrokeLineIndex(it) in lineSet }
    }

    /**
     * Compute visual blocks from cue line indices + diagram areas.
     * Returns list of IntRange (first..last line) for each contiguous block.
     */
    private fun computeVisualBlocks(
        cueLineIndices: Set<Int>,
        diagramAreas: List<DiagramArea>
    ): List<IntRange> {
        val diagramLines = mutableSetOf<Int>()
        for (area in diagramAreas) {
            for (l in area.startLineIndex..area.endLineIndex) diagramLines.add(l)
        }
        val allOccupied = (cueLineIndices + diagramLines).sorted()
        if (allOccupied.isEmpty()) return emptyList()

        val blocks = mutableListOf<IntRange>()
        var blockStart = allOccupied.first()
        var blockEnd = blockStart

        for (i in 1 until allOccupied.size) {
            if (allOccupied[i] == blockEnd + 1) {
                blockEnd = allOccupied[i]
            } else {
                blocks.add(blockStart..blockEnd)
                blockStart = allOccupied[i]
                blockEnd = blockStart
            }
        }
        blocks.add(blockStart..blockEnd)
        return blocks
    }
}
