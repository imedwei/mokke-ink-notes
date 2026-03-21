package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DiagramTextFilter.diagramBandLines]: text written beside a diagram
 * (in its Y-band but outside its X-extent) should be detected as a band note.
 */
class DiagramBandLinesTest {

    // Diagram bbox: x=[100..300], y=[200..400]
    private val bbox = floatArrayOf(100f, 200f, 300f, 400f)
    private val yTol = 50f

    // Helper: a single-point freehand stroke at (cx, cy)
    private fun stroke(cx: Float, cy: Float, type: StrokeType = StrokeType.FREEHAND): InkStroke {
        val pt = StrokePoint(cx, cy, 1f, 0L)
        return InkStroke(points = listOf(pt), strokeType = type)
    }

    // Node bounds as [left, top, right, bottom]
    private fun nodeBounds(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    @Test fun strokeToRightOfDiagram_isDetected() {
        val strokesByLine = mapOf(0 to listOf(stroke(350f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertTrue("Stroke to the right should be detected as a band note", 0 in result)
    }

    @Test fun strokeToLeftOfDiagram_isDetected() {
        val strokesByLine = mapOf(0 to listOf(stroke(50f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertTrue("Stroke to the left should be detected as a band note", 0 in result)
    }

    @Test fun strokeAboveDiagram_notDetected() {
        // cy = 100 < (bTop - yTol) = 150
        val strokesByLine = mapOf(0 to listOf(stroke(350f, 100f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Stroke above Y-band should NOT be detected", 0 in result)
    }

    @Test fun strokeBelowDiagram_notDetected() {
        // cy = 500 > (bBottom + yTol) = 450
        val strokesByLine = mapOf(0 to listOf(stroke(350f, 500f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Stroke below Y-band should NOT be detected", 0 in result)
    }

    @Test fun strokeInsideNode_notDetected() {
        // Stroke is to the right of bbox X but inside a node — shape label, not a note
        val nodeB = nodeBounds(300f, 200f, 450f, 400f)
        val strokesByLine = mapOf(0 to listOf(stroke(375f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, listOf(nodeB), bbox, yTol)
        assertFalse("Stroke inside a node should NOT be detected (it is a shape label)", 0 in result)
    }

    @Test fun mixedLine_shapeLabelPlusBandNote_isDetected() {
        // Real-world case from Issue #5: "side" text written at the same Y-level as shape
        // label "A". The shape-label stroke is inside node A; the "side" stroke is outside X.
        // Shape-label strokes must be ignored so the band note is still detected.
        val nodeA = nodeBounds(100f, 200f, 200f, 320f)  // inside the diagram bbox
        val strokesByLine = mapOf(
            0 to listOf(
                stroke(150f, 260f),  // shape label "A" — inside nodeA
                stroke(350f, 260f)   // "side" — outside X, in Y-band
            )
        )
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, listOf(nodeA), bbox, yTol)
        assertTrue("Line with shape-label + band-note strokes should be detected as a band note", 0 in result)
    }

    @Test fun noFreehandStrokes_notDetected() {
        val strokesByLine = mapOf(0 to listOf(stroke(350f, 300f, StrokeType.RECTANGLE)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Line with no freehand strokes should NOT be detected", 0 in result)
    }

    @Test fun noDiagram_returnsEmpty() {
        // When there are no nodes the caller passes no diagramBBox, so emptySet is expected.
        // This tests the function directly with an arbitrary bbox but empty strokesByLine.
        val result = DiagramTextFilter.diagramBandLines(emptyMap(), emptyList(), bbox, yTol)
        assertTrue("No strokes → result should be empty", result.isEmpty())
    }

    @Test fun strokeJustOutsideBbox_detected() {
        // cx = bbox.right + 1 = 301 → strictly outside X
        val strokesByLine = mapOf(0 to listOf(stroke(301f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertTrue("Stroke just outside bbox X should be detected", 0 in result)
    }

    @Test fun strokeExactlyAtBboxEdge_notDetected() {
        // cx = bbox.right = 300 → on the X border → inside (not < bLeft, not > bRight)
        val strokesByLine = mapOf(0 to listOf(stroke(300f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Stroke exactly at bbox right edge should NOT be detected (on border = inside)", 0 in result)
    }

    // ── Right-margin exclusion (Bug #6) ───────────────────────────────────────
    //
    // After drawing a diagram, diagramBandLines activates.  Without an upper-X
    // bound, ANY text written to the right of the diagram's bbox and within its
    // Y-band is classified as a band note — including normal text at the far-right
    // margin of the page (just before the scroll gutter).  That text should not
    // be suppressed from the text paragraph panel.
    //
    // Fix: add a rightBandLimit parameter.  Only strokes with cx < rightBandLimit
    // qualify as "right-side" band notes.  The caller passes
    // canvasWidth − 2·gutterWidth so strokes in the rightmost gutter-zone are
    // treated as ordinary text.
    //
    // Canvas geometry used in these tests (matching a 3200 px wide device with
    // 129 px gutter, i.e. Tab X C at 300 PPI):
    //   rightBandLimit = 3200 − 2·129 = 2942

    private val rightBandLimit = 2942f  // canvasWidth − 2·gutterWidth

    @Test fun strokeFarRightOfDiagram_notBandNote() {
        // Stroke at x=2950 is to the right of the diagram (bbox.right=300) but
        // also past the rightBandLimit=2942 — it is regular text, NOT a band note.
        // FAILS currently (no rightBandLimit check → detected as band note).
        val strokesByLine = mapOf(0 to listOf(stroke(2950f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(
            strokesByLine, emptyList(), bbox, yTol, rightBandLimit = rightBandLimit)
        assertFalse("Text beyond rightBandLimit should not be a band note", 0 in result)
    }

    @Test fun strokeJustInsideRightBandLimit_isBandNote() {
        // Stroke at x=2940 is outside the diagram X and below rightBandLimit → IS a band note.
        val strokesByLine = mapOf(0 to listOf(stroke(2940f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(
            strokesByLine, emptyList(), bbox, yTol, rightBandLimit = rightBandLimit)
        assertTrue("Text just inside rightBandLimit should still be a band note", 0 in result)
    }

    @Test fun noRightBandLimit_farRightDetected() {
        // Explicit MAX_VALUE preserves the old unlimited behaviour for callers that need it.
        val strokesByLine = mapOf(0 to listOf(stroke(2950f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(
            strokesByLine, emptyList(), bbox, yTol, rightBandLimit = Float.MAX_VALUE)
        assertTrue("Explicit MAX_VALUE limit: far-right stroke is still detected", 0 in result)
    }

    // ── Default right-band limit (Bug #6 root cause) ──────────────────────────
    //
    // The previous fix used rightBandLimit = canvasWidth − 2·gutterWidth ≈ 2942,
    // meaning any stroke between bRight (300) and 2942 was classified as a band note.
    // That is 2600 px of canvas — nearly the entire page.  Text written on the right
    // side of the canvas after drawing a small left-side diagram was silently suppressed.
    //
    // Fix: make rightBandLimit default to bRight + yTolerance × 4 (a narrow column
    // proportional to the diagram's already-known line spacing).  Text further away
    // is ordinary writing, not a diagram side note.
    //
    // With bbox x=[100..300] and yTol=50: default rightBandLimit = 300 + 50×4 = 500.

    @Test fun textFarRightOfSmallDiagram_defaultLimit_notBandNote() {
        // Text at x=2800 is 2500 px to the right of a small diagram.
        // With the sensible default (rightBandLimit = bRight + 4·yTol = 500) it is NOT a band note.
        // CURRENTLY FAILS: default is Float.MAX_VALUE → 2800 IS classified as a band note.
        val strokesByLine = mapOf(0 to listOf(stroke(2800f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Text 2500 px from diagram right edge should not be a band note", 0 in result)
    }

    @Test fun textJustBeyondDefaultColumnWidth_notBandNote() {
        // x = bRight(300) + 4·yTol(50) + 1 = 501 → just outside the default column → not a band note
        val strokesByLine = mapOf(0 to listOf(stroke(501f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Stroke just outside default column should not be a band note", 0 in result)
    }

    @Test fun textWithinDefaultColumnWidth_isBandNote() {
        // x = 499 → inside default column (300 < 499 < 500) → IS a band note
        val strokesByLine = mapOf(0 to listOf(stroke(499f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertTrue("Stroke inside default column (x=499 < limit=500) should be a band note", 0 in result)
    }

    // ── Left-margin exclusion (Bug #7) ────────────────────────────────────────
    //
    // The left-side band check was `cx < bLeft` — unlimited on the left.  If the
    // diagram sits in the right column, the entire normal writing area (which lies
    // to the LEFT of the diagram) falls inside this half-plane.  Every text line
    // written beside the diagram in the standard left-column is wrongly suppressed.
    //
    // The user observes 4-5 "dead" lines: those are the writing lines that overlap
    // the diagram's Y-extent while the strokes are in the normal left column.
    //
    // Fix: add a symmetric leftBandLimit = bLeft − yTolerance × 4.  Only strokes
    // within a narrow column immediately to the LEFT of the diagram are captured
    // as left-side band notes; normal writing further left is ordinary text.
    //
    // With bbox x=[100..300] and yTol=50:
    //   leftBandLimit  = 100 − 50×4 = −100
    //   rightBandLimit = 300 + 50×4 = 500

    @Test fun textFarLeftOfDiagram_defaultLimit_notBandNote() {
        // Stroke at x=-200 is far to the left of the diagram (bLeft=100).
        // Default leftBandLimit = 100 − 4×50 = −100.  cx=−200 < −100 → NOT a band note.
        // FAILS currently: no leftBandLimit → any cx < bLeft qualifies.
        val strokesByLine = mapOf(0 to listOf(stroke(-200f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Text far to the left of the diagram should not be a band note", 0 in result)
    }

    @Test fun textJustInsideLeftBandLimit_isBandNote() {
        // x = bLeft(100) − 4×yTol(50) + 1 = −99 → just inside the left column → IS a band note
        val strokesByLine = mapOf(0 to listOf(stroke(-99f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertTrue("Stroke inside left column (x=−99 > limit=−100) should be a band note", 0 in result)
    }

    @Test fun textJustOutsideLeftBandLimit_notBandNote() {
        // x = bLeft(100) − 4×yTol(50) − 1 = −101 → just beyond the left column → NOT a band note
        val strokesByLine = mapOf(0 to listOf(stroke(-101f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(strokesByLine, emptyList(), bbox, yTol)
        assertFalse("Stroke just outside left column (x=−101 < limit=−100) should not be a band note", 0 in result)
    }

    @Test fun explicitUnlimitedLeftBand_farLeftDetected() {
        // Passing leftBandLimit = -MAX_VALUE restores the old unlimited-left behaviour.
        val strokesByLine = mapOf(0 to listOf(stroke(-200f, 300f)))
        val result = DiagramTextFilter.diagramBandLines(
            strokesByLine, emptyList(), bbox, yTol, leftBandLimit = -Float.MAX_VALUE)
        assertTrue("Explicit MIN_VALUE left limit: far-left stroke is still detected", 0 in result)
    }
}
