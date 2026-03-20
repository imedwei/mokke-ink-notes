package com.writer.view

import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DiagramMarkdown].
 */
class DiagramMarkdownTest {

    // ── nodeLabel ─────────────────────────────────────────────────────────────

    @Test fun nodeLabel_rectangle_squareBrackets() {
        val result = DiagramMarkdown.nodeLabel("N1", "Process", StrokeType.RECTANGLE)
        assertEquals("N1[Process]", result)
    }

    @Test fun nodeLabel_ellipse_doubleRoundBrackets() {
        val result = DiagramMarkdown.nodeLabel("N2", "Start", StrokeType.ELLIPSE)
        assertEquals("N2((Start))", result)
    }

    @Test fun nodeLabel_diamond_curlyBrackets() {
        val result = DiagramMarkdown.nodeLabel("N3", "Valid?", StrokeType.DIAMOND)
        assertEquals("N3{Valid?}", result)
    }

    @Test fun nodeLabel_roundedRectangle_roundBrackets() {
        val result = DiagramMarkdown.nodeLabel("N4", "Step", StrokeType.ROUNDED_RECTANGLE)
        assertEquals("N4(Step)", result)
    }

    @Test fun nodeLabel_triangle_doubleCurlyBrackets() {
        val result = DiagramMarkdown.nodeLabel("N5", "If", StrokeType.TRIANGLE)
        assertEquals("N5{{If}}", result)
    }

    // ── connector ─────────────────────────────────────────────────────────────

    @Test fun connector_arrowHead_rightArrow() {
        val result = DiagramMarkdown.connector(StrokeType.ARROW_HEAD)
        assertEquals("-->", result)
    }

    @Test fun connector_arrowTail_leftArrow() {
        val result = DiagramMarkdown.connector(StrokeType.ARROW_TAIL)
        assertEquals("<--", result)
    }

    @Test fun connector_arrowBoth_bidirectional() {
        val result = DiagramMarkdown.connector(StrokeType.ARROW_BOTH)
        assertEquals("<-->", result)
    }

    @Test fun connector_line_dashes() {
        val result = DiagramMarkdown.connector(StrokeType.LINE)
        assertEquals("---", result)
    }

    // ── sanitizeLabel ─────────────────────────────────────────────────────────

    @Test fun sanitizeLabel_squareBrackets_removed() {
        val result = DiagramMarkdown.sanitizeLabel("He[llo]")
        assertEquals("Hello", result)
    }

    @Test fun sanitizeLabel_angleBracketsRemoved_questionMarkKept() {
        val result = DiagramMarkdown.sanitizeLabel("Va<l>id?")
        assertEquals("Valid?", result)
    }
}
