package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for heading underline detection, including the case where the
 * underline stroke's center of mass places it on the line below the text.
 */
class HeadingDetectionTest {

    private lateinit var segmenter: LineSegmenter
    private lateinit var classifier: StrokeClassifier
    private lateinit var builder: ParagraphBuilder
    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        segmenter = LineSegmenter()
        classifier = StrokeClassifier(segmenter)
        builder = ParagraphBuilder(classifier)
    }

    /** Create a text-like stroke on a given line. */
    private fun textStroke(lineIndex: Int, startX: Float = 50f, width: Float = 300f): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.4f
        return InkStroke(points = (0..20).map { i ->
            val t = i / 20f
            StrokePoint(startX + t * width, y + (i % 3) * 2f, 0.5f, i.toLong())
        })
    }

    /** Create a horizontal underline stroke at the bottom of a line.
     *  @param yFraction where in the line to place it (0.8 = bottom 20%, 1.0 = at line boundary) */
    private fun underlineStroke(lineIndex: Int, startX: Float = 50f, width: Float = 300f, yFraction: Float = 0.85f): InkStroke {
        val y = tm + lineIndex * ls + ls * yFraction
        return InkStroke(points = listOf(
            StrokePoint(startX, y, 0.5f, 100L),
            StrokePoint(startX + width * 0.5f, y + 2f, 0.5f, 150L),
            StrokePoint(startX + width, y - 1f, 0.5f, 200L)
        ))
    }

    /** Create a short dash stroke (list marker, not underline). */
    private fun dashStroke(lineIndex: Int, startX: Float = 50f): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.5f
        return InkStroke(points = listOf(
            StrokePoint(startX, y, 0.5f, 100L),
            StrokePoint(startX + 30f, y + 1f, 0.5f, 150L)
        ))
    }

    // ── Test 1: Underline on same line → heading detected ─────────────────

    @Test
    fun underlineOnSameLine_headingDetected() {
        val text = textStroke(2)
        val underline = underlineStroke(2)
        val strokes = listOf(text, underline)

        val result = builder.classifyLine(2, "Header", strokes, 1000f)
        assertNotNull(result)
        assertTrue("Should be heading", result!!.isHeading)
    }

    // ── Test 2: Underline assigned to next line → heading detected ────────

    @Test
    fun underlineOnNextLine_headingDetected() {
        val text = textStroke(2)
        // Underline at yFraction=1.05 — center of mass falls on line 3
        val underline = underlineStroke(2, yFraction = 1.05f)
        val underlineLine = segmenter.getStrokeLineIndex(underline)
        assertEquals("Underline should be assigned to line 3", 3, underlineLine)

        val strokesLine2 = listOf(text)
        val strokesLine3 = listOf(underline)

        val result = builder.classifyLine(2, "Header", strokesLine2, 1000f, strokesLine3)
        assertNotNull(result)
        assertTrue("Should be heading (underline on next line)", result!!.isHeading)
    }

    // ── Test 3: Underline with no text above → NOT heading ────────────────

    @Test
    fun underlineWithNoTextAbove_notHeading() {
        val underline = underlineStroke(3)
        val strokes = listOf(underline)

        // classifyLine returns null for empty/null text
        val result = builder.classifyLine(3, null, strokes, 1000f)
        assertNull("No text = no heading", result)
    }

    // ── Test 4: Short stroke (< 80% text width) → NOT heading ────────────

    @Test
    fun shortStroke_notHeading() {
        val text = textStroke(2, width = 300f)
        // Short underline — only 100px vs 300px text = 33%
        val shortLine = underlineStroke(2, width = 100f)
        val strokes = listOf(text, shortLine)

        val result = builder.classifyLine(2, "Header", strokes, 1000f)
        assertNotNull(result)
        assertFalse("Short stroke should not be heading", result!!.isHeading)
    }

    // ── Test 5: Underline on next line WITH text also on next line ────────

    @Test
    fun underlineOnNextLineWithText_headingDetectedTextPreserved() {
        val headerText = textStroke(2)
        // Underline center of mass on line 3
        val underline = underlineStroke(2, yFraction = 1.05f)
        // Regular text also on line 3
        val nextLineText = textStroke(3, startX = 50f, width = 200f)

        val strokesLine2 = listOf(headerText)
        val strokesLine3 = listOf(underline, nextLineText)

        // Line 2 should be a heading
        val result2 = builder.classifyLine(2, "Header", strokesLine2, 1000f, strokesLine3)
        assertNotNull(result2)
        assertTrue("Line 2 should be heading", result2!!.isHeading)

        // Line 3 should still have its text (not suppressed)
        val result3 = builder.classifyLine(3, "more text", strokesLine3, 1000f)
        assertNotNull(result3)
        assertFalse("Line 3 should not be heading", result3!!.isHeading)
    }

    // ── Test 6: List dash on next line → NOT treated as underline ─────────

    @Test
    fun listDashOnNextLine_notHeading() {
        val text = textStroke(2, width = 300f)
        val dash = dashStroke(3)

        val strokesLine2 = listOf(text)
        val strokesLine3 = listOf(dash)

        val result = builder.classifyLine(2, "Not a header", strokesLine2, 1000f, strokesLine3)
        assertNotNull(result)
        assertFalse("Dash on next line should not make heading", result!!.isHeading)
    }
}
