package com.writer.view

import com.writer.model.DiagramArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PreviewLayoutCalculator] — the pure-logic engine that decides
 * what appears in the preview and how it's sized for flush alignment with
 * the canvas boundary.
 *
 * Uses concrete numbers based on the Go 7 device (density 1.875, line
 * spacing 118px, top margin 36px) for readability, but the logic is
 * density-independent.
 */
class PreviewLayoutCalculatorTest {

    companion object {
        // Go 7 at 300 PPI (density = 1.875)
        const val LINE_SPACING = 118f   // 63dp * 1.875
        const val TOP_MARGIN = 36f      // 19dp * 1.875
        const val STROKE_WIDTH = 5f
        const val PARAGRAPH_SPACING = 22f  // 12dp * 1.875 (approx)
        const val CANVAS_WIDTH = 824f
        const val TEXT_VIEW_WIDTH = 824f   // same width, no gutter
    }

    private fun lineTop(idx: Int) = TOP_MARGIN + idx * LINE_SPACING
    private fun lineBottom(idx: Int) = lineTop(idx) + LINE_SPACING
    private fun lineMid(idx: Int) = lineTop(idx) + LINE_SPACING / 2f

    // ── currentlyHiddenLines ────────────────────────────────────────────

    @Test fun hidden_noScroll_nothingHidden() {
        val hidden = PreviewLayoutCalculator.currentlyHiddenLines(
            lineIndices = setOf(0, 1, 2),
            scrollOffsetY = 0f,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        assertTrue("No lines should be hidden at scroll=0", hidden.isEmpty())
    }

    @Test fun hidden_scrollPastFirstLine_firstLineHidden() {
        val hidden = PreviewLayoutCalculator.currentlyHiddenLines(
            lineIndices = setOf(0, 1, 2),
            scrollOffsetY = lineBottom(0),
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        assertEquals(setOf(0), hidden)
    }

    @Test fun hidden_scrollPartiallyPastLine_notHidden() {
        // Scroll to just before line 0's bottom — not fully hidden
        val hidden = PreviewLayoutCalculator.currentlyHiddenLines(
            lineIndices = setOf(0, 1, 2),
            scrollOffsetY = lineBottom(0) - 1f,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        assertTrue("Line 0 should NOT be hidden when scroll is 1px short", hidden.isEmpty())
    }

    @Test fun hidden_scrollPastMultipleLines() {
        val hidden = PreviewLayoutCalculator.currentlyHiddenLines(
            lineIndices = setOf(0, 1, 2, 3, 4),
            scrollOffsetY = lineBottom(2),
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        assertEquals(setOf(0, 1, 2), hidden)
    }

    @Test fun hidden_onlyLinesWithStrokes_emptyGapsIgnored() {
        // Lines 0 and 3 have strokes, lines 1 and 2 don't
        val hidden = PreviewLayoutCalculator.currentlyHiddenLines(
            lineIndices = setOf(0, 3),
            scrollOffsetY = lineBottom(2),
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        // Line 0 is hidden, line 3 is not (its bottom is below scroll)
        assertEquals(setOf(0), hidden)
    }

    // ── notYetVisibleLines ──────────────────────────────────────────────

    @Test fun notYetVisible_usesLineMidpoint() {
        // Scroll to exactly line 1's midpoint
        val notVisible = PreviewLayoutCalculator.notYetVisibleLines(
            lineIndices = setOf(0, 1, 2),
            scrollOffsetY = lineMid(1),
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        // Line 0 midpoint is above scroll, line 1 midpoint is exactly at scroll (<=)
        assertEquals(setOf(0, 1), notVisible)
    }

    @Test fun notYetVisible_justBeforeMidpoint_notIncluded() {
        val notVisible = PreviewLayoutCalculator.notYetVisibleLines(
            lineIndices = setOf(0, 1),
            scrollOffsetY = lineMid(1) - 1f,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING
        )
        assertEquals(setOf(0), notVisible)
    }

    // ── diagramVisibilities ──────────────────────────────────────────────

    @Test fun diagram_notScrolledOff_notIncluded() {
        val areas = listOf(DiagramArea(startLineIndex = 5, heightInLines = 3))
        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = areas,
            scrollOffsetY = lineTop(5) - 1f, // 1px before area top
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = emptyMap(),
            strokeWidthPadding = STROKE_WIDTH
        )
        assertTrue("Diagram should not be included before any part scrolls off", result.isEmpty())
    }

    @Test fun diagram_1pxScrolledOff_included() {
        val areas = listOf(DiagramArea(startLineIndex = 5, heightInLines = 3))
        val areaTop = lineTop(5)
        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = areas,
            scrollOffsetY = areaTop + 1f,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = emptyMap(),
            strokeWidthPadding = STROKE_WIDTH
        )
        assertEquals(1, result.size)
        assertEquals(1f, result[0].visibleHeight, 0.01f)
        assertTrue("Should be partial", result[0].isPartial)
    }

    @Test fun diagram_fullyScrolledOff_fullHeight() {
        val areas = listOf(DiagramArea(startLineIndex = 2, heightInLines = 4))
        val areaTop = lineTop(2)
        val fullHeight = 4 * LINE_SPACING
        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = areas,
            scrollOffsetY = areaTop + fullHeight + 100f, // well past
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = emptyMap(),
            strokeWidthPadding = STROKE_WIDTH
        )
        assertEquals(1, result.size)
        assertEquals(fullHeight, result[0].visibleHeight, 0.01f)
        assertFalse("Should not be partial when fully visible", result[0].isPartial)
    }

    @Test fun diagram_croppedToStrokeBounds() {
        val area = DiagramArea(startLineIndex = 3, heightInLines = 5)
        val areaTop = lineTop(3)
        val fullHeight = 5 * LINE_SPACING
        // Strokes only reach 3 lines down (not all 5)
        val strokeMaxY = areaTop + 3 * LINE_SPACING - 10f

        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = listOf(area),
            scrollOffsetY = areaTop + fullHeight, // fully scrolled
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = mapOf(3 to strokeMaxY),
            strokeWidthPadding = STROKE_WIDTH
        )
        assertEquals(1, result.size)
        val expectedStrokeBottom = strokeMaxY - areaTop + STROKE_WIDTH
        assertEquals(expectedStrokeBottom, result[0].visibleHeight, 0.01f)
        assertTrue("Cropped diagram should be partial", result[0].isPartial)
    }

    @Test fun diagram_strokeBoundsExceedArea_clampedToFullHeight() {
        val area = DiagramArea(startLineIndex = 0, heightInLines = 3)
        val areaTop = lineTop(0)
        val fullHeight = 3 * LINE_SPACING
        // Stroke maxY is beyond the area bottom
        val strokeMaxY = areaTop + fullHeight + 50f

        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = listOf(area),
            scrollOffsetY = areaTop + fullHeight,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = mapOf(0 to strokeMaxY),
            strokeWidthPadding = STROKE_WIDTH
        )
        assertEquals(fullHeight, result[0].visibleHeight, 0.01f)
    }

    @Test fun diagram_partialScroll_croppedToScrolledOff() {
        val area = DiagramArea(startLineIndex = 2, heightInLines = 5)
        val areaTop = lineTop(2)
        // Scroll only 1 line into the diagram
        val scrolledOff = LINE_SPACING
        // Strokes go all the way down
        val strokeMaxY = areaTop + 4.5f * LINE_SPACING

        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = listOf(area),
            scrollOffsetY = areaTop + scrolledOff,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = mapOf(2 to strokeMaxY),
            strokeWidthPadding = STROKE_WIDTH
        )
        // visibleHeight = min(scrolledOff, strokeBottom) = scrolledOff (since strokes go further)
        assertEquals(scrolledOff, result[0].visibleHeight, 0.01f)
    }

    @Test fun diagram_noStrokes_usesFullHeight() {
        val area = DiagramArea(startLineIndex = 1, heightInLines = 3)
        val areaTop = lineTop(1)
        val fullHeight = 3 * LINE_SPACING

        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = listOf(area),
            scrollOffsetY = areaTop + fullHeight,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = emptyMap(), // no strokes
            strokeWidthPadding = STROKE_WIDTH
        )
        assertEquals(fullHeight, result[0].visibleHeight, 0.01f)
    }

    @Test fun diagram_multipleDiagrams_correctOrder() {
        val areas = listOf(
            DiagramArea(startLineIndex = 1, heightInLines = 2),
            DiagramArea(startLineIndex = 5, heightInLines = 3),
            DiagramArea(startLineIndex = 10, heightInLines = 2)
        )
        // Scroll past the first two, not the third
        val scrollOffsetY = lineTop(8)

        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = areas,
            scrollOffsetY = scrollOffsetY,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = emptyMap(),
            strokeWidthPadding = STROKE_WIDTH
        )
        assertEquals(2, result.size)
        assertEquals(1, result[0].startLineIndex)
        assertEquals(5, result[1].startLineIndex)
    }

    // ── diagramRenderMetrics ────────────────────────────────────────────

    @Test fun renderMetrics_sameWidth_scale1() {
        val metrics = PreviewLayoutCalculator.diagramRenderMetrics(
            visibleHeight = 200f,
            fullHeight = 400f,
            canvasWidth = CANVAS_WIDTH,
            textViewWidth = CANVAS_WIDTH, // same width
            paragraphSpacing = PARAGRAPH_SPACING
        )
        assertEquals(1f, metrics.scale, 0.001f)
        assertTrue(metrics.isPartial)
        assertEquals(200f, metrics.renderedHeight, 0.01f) // no spacing for partial
        assertEquals(400f + PARAGRAPH_SPACING, metrics.fullRenderedHeight, 0.01f)
    }

    @Test fun renderMetrics_fullDiagram_includesSpacing() {
        val metrics = PreviewLayoutCalculator.diagramRenderMetrics(
            visibleHeight = 400f,
            fullHeight = 400f,
            canvasWidth = CANVAS_WIDTH,
            textViewWidth = CANVAS_WIDTH,
            paragraphSpacing = PARAGRAPH_SPACING
        )
        assertFalse(metrics.isPartial)
        assertEquals(400f + PARAGRAPH_SPACING, metrics.renderedHeight, 0.01f)
    }

    @Test fun renderMetrics_partialDiagram_noSpacing() {
        val metrics = PreviewLayoutCalculator.diagramRenderMetrics(
            visibleHeight = 100f,
            fullHeight = 400f,
            canvasWidth = CANVAS_WIDTH,
            textViewWidth = CANVAS_WIDTH,
            paragraphSpacing = PARAGRAPH_SPACING
        )
        assertTrue(metrics.isPartial)
        assertEquals(100f, metrics.renderedHeight, 0.01f) // no spacing
    }

    @Test fun renderMetrics_differentWidths_scalesCorrectly() {
        val metrics = PreviewLayoutCalculator.diagramRenderMetrics(
            visibleHeight = 200f,
            fullHeight = 400f,
            canvasWidth = 800f,
            textViewWidth = 400f, // half width
            paragraphSpacing = PARAGRAPH_SPACING
        )
        assertEquals(0.5f, metrics.scale, 0.001f)
        assertEquals(200f * 0.5f, metrics.renderedHeight, 0.01f)
    }

    // ── trimLastItemHeight ──────────────────────────────────────────────

    @Test fun trimLast_textItem_usesLayoutHeight() {
        val result = PreviewLayoutCalculator.trimLastItemHeight(
            heightPx = 150f,     // includes spacing
            fullHeightPx = 150f,
            paragraphSpacing = PARAGRAPH_SPACING,
            isText = true,
            textLayoutHeight = 128f  // raw layout height without spacing
        )
        assertEquals(128f, result, 0.01f)
    }

    @Test fun trimLast_diagramItem_removesSpacing() {
        val fullRendered = 400f + PARAGRAPH_SPACING
        val result = PreviewLayoutCalculator.trimLastItemHeight(
            heightPx = fullRendered,
            fullHeightPx = fullRendered,
            paragraphSpacing = PARAGRAPH_SPACING,
            isText = false
        )
        assertEquals(400f, result, 0.01f)
    }

    @Test fun trimLast_partialDiagram_alreadySmall_noChange() {
        // Partial diagram: heightPx = 100 (no spacing), fullHeightPx = 422
        val result = PreviewLayoutCalculator.trimLastItemHeight(
            heightPx = 100f,
            fullHeightPx = 400f + PARAGRAPH_SPACING,
            paragraphSpacing = PARAGRAPH_SPACING,
            isText = false
        )
        // coerceAtMost(422 - 22) = coerceAtMost(400) → 100 (already smaller)
        assertEquals(100f, result, 0.01f)
    }

    // ── Complementary view invariants ───────────────────────────────────

    @Test fun complementary_previewAndCanvasShowComplementaryContent() {
        // For any scroll position, the hidden lines + visible lines should
        // cover all lines with no overlap.
        val allLines = setOf(0, 1, 2, 3, 4, 5)
        val scrollOffsetY = lineBottom(2) // lines 0-2 hidden

        val hidden = PreviewLayoutCalculator.currentlyHiddenLines(
            allLines, scrollOffsetY, TOP_MARGIN, LINE_SPACING
        )
        val visible = allLines - hidden

        assertEquals("Hidden + visible should cover all lines", allLines, hidden + visible)
        assertTrue("Hidden and visible should not overlap", hidden.intersect(visible).isEmpty())
        assertEquals(setOf(0, 1, 2), hidden)
        assertEquals(setOf(3, 4, 5), visible)
    }

    @Test fun complementary_diagramVisibleHeight_matchesScrolledOffPortion() {
        // The preview shows exactly what's scrolled off — no more, no less.
        val area = DiagramArea(startLineIndex = 3, heightInLines = 4)
        val areaTop = lineTop(3)
        val scrolledAmount = 2.5f * LINE_SPACING
        val scrollOffsetY = areaTop + scrolledAmount

        val result = PreviewLayoutCalculator.diagramVisibilities(
            areas = listOf(area),
            scrollOffsetY = scrollOffsetY,
            topMargin = TOP_MARGIN,
            lineSpacing = LINE_SPACING,
            strokeMaxYByArea = emptyMap(), // no stroke cropping
            strokeWidthPadding = STROKE_WIDTH
        )

        // Preview shows scrolledAmount, canvas shows the rest
        assertEquals(scrolledAmount, result[0].visibleHeight, 0.01f)
        val canvasRemaining = result[0].fullHeight - result[0].visibleHeight
        assertEquals(
            "Preview + canvas should equal full height",
            result[0].fullHeight,
            result[0].visibleHeight + canvasRemaining,
            0.01f
        )
    }

    @Test fun complementary_partialDiagram_noSpacingGap() {
        // A partial diagram should have zero spacing after it, ensuring the
        // preview content is flush against the divider.
        val metrics = PreviewLayoutCalculator.diagramRenderMetrics(
            visibleHeight = 150f,
            fullHeight = 400f,
            canvasWidth = CANVAS_WIDTH,
            textViewWidth = TEXT_VIEW_WIDTH,
            paragraphSpacing = PARAGRAPH_SPACING
        )
        // renderedHeight should NOT include spacing for partial diagrams
        assertEquals(150f, metrics.renderedHeight, 0.01f)
    }
}
