package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PreviewMetricsCalculator.compute] — bounding box computation and
 * scale factor for the peek preview popup.
 *
 * Uses Robolectric so StaticLayout text measurement works with the real
 * default measurer (no mocks/fakes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CuePreviewMetricsTest {

    // Use ScreenMetrics directly to avoid loading HandwritingCanvasView (SurfaceView)
    private val ls get() = ScreenMetrics.lineSpacing
    private val tm get() = ScreenMetrics.topMargin
    private val textBodySize get() = ScreenMetrics.textBody
    private val pad = 15f
    private val previewWidth = 400

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun strokeAtLine(lineIndex: Int, startX: Float = 50f, endX: Float = 300f): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.4f
        return InkStroke(
            points = listOf(
                StrokePoint(startX, y, 0.5f, 0L),
                StrokePoint(endX, y + 5f, 0.5f, 100L)
            )
        )
    }

    private fun compute(
        strokes: List<InkStroke> = emptyList(),
        textBlocks: List<TextBlock> = emptyList(),
        canvasWidth: Float = 700f
    ) = PreviewMetricsCalculator.compute(
        strokes, textBlocks, previewWidth, canvasWidth,
        ls, tm, textBodySize, pad
    )

    // ── Empty input ─────────────────────────────────────────────────────

    @Test
    fun emptyStrokesAndTextBlocks_returnsNull() {
        assertNull(compute())
    }

    // ── Strokes only ────────────────────────────────────────────────────

    @Test
    fun strokesOnly_boundsMatchStrokeExtent() {
        val strokes = listOf(strokeAtLine(2, startX = 50f, endX = 400f))
        val m = compute(strokes = strokes)!!
        assertEquals(50f, m.minX, 1f)
        assertEquals(400f, m.maxX, 1f)
        assertTrue(m.contentWidth > 0)
        assertTrue(m.contentHeight > 0)
    }

    @Test
    fun strokesOnly_scaleAtMostOne() {
        val strokes = listOf(strokeAtLine(2, startX = 50f, endX = 100f))
        val m = compute(strokes = strokes)!!
        assertTrue("Scale should be at most 1.0", m.scale <= 1f)
    }

    @Test
    fun wideStrokes_scaleDown() {
        val strokes = listOf(strokeAtLine(2, startX = 0f, endX = 1000f))
        val m = compute(strokes = strokes)!!
        assertTrue("Wide content should scale down", m.scale < 1f)
    }

    // ── TextBlocks only ─────────────────────────────────────────────────

    @Test
    fun textBlockOnly_boundsIncludeTextLines() {
        val tb = TextBlock(startLineIndex = 3, heightInLines = 2, text = "hello world")
        val m = compute(textBlocks = listOf(tb))!!
        val expectedMinY = tm + 3 * ls
        val expectedMaxY = tm + 5 * ls
        assertEquals(expectedMinY, m.minY, 1f)
        assertEquals(expectedMaxY, m.maxY, 1f)
    }

    @Test
    fun textBlockOnly_maxXLessThanCanvasWidth() {
        val tb = TextBlock(startLineIndex = 0, heightInLines = 1, text = "short")
        val m = compute(textBlocks = listOf(tb))!!
        assertTrue("maxX should be less than canvas width for short text", m.maxX < 700f)
    }

    @Test
    fun textBlockOnly_maxXGrowsWithTextLength() {
        val short = TextBlock(startLineIndex = 0, heightInLines = 1, text = "hi")
        val long = TextBlock(startLineIndex = 0, heightInLines = 1, text = "a much longer piece of text")
        val mShort = compute(textBlocks = listOf(short))!!
        val mLong = compute(textBlocks = listOf(long))!!
        assertTrue("Longer text should have wider bounds", mLong.maxX > mShort.maxX)
    }

    @Test
    fun textBlockOnly_longTextWraps_maxXCappedAtWrapWidth() {
        val longText = "this is a very long text that definitely wraps multiple times on a narrow canvas"
        val tb = TextBlock(startLineIndex = 0, heightInLines = 3, text = longText)
        val canvasWidth = 200f
        val m = compute(textBlocks = listOf(tb), canvasWidth = canvasWidth)!!
        assertTrue("Wrapped text maxX should not exceed canvas width", m.maxX <= canvasWidth)
    }

    // ── Strokes + TextBlocks together ───────────────────────────────────

    @Test
    fun strokesAndTextBlocks_maxYCoversTextBlockBottom() {
        val strokes = listOf(strokeAtLine(2, startX = 80f, endX = 400f))
        val tb = TextBlock(startLineIndex = 3, heightInLines = 1, text = "transcribed text")
        val m = compute(strokes = strokes, textBlocks = listOf(tb))!!
        val expectedMaxY = tm + 4 * ls
        assertEquals(expectedMaxY, m.maxY, 1f)
    }

    @Test
    fun strokesAndTextBlocks_minXIncludesTextMargin() {
        val strokes = listOf(strokeAtLine(2, startX = 80f, endX = 400f))
        val tb = TextBlock(startLineIndex = 3, heightInLines = 1, text = "text")
        val m = compute(strokes = strokes, textBlocks = listOf(tb))!!
        val textLeftMargin = ls * 0.3f
        assertEquals(minOf(80f, textLeftMargin), m.minX, 1f)
    }

    @Test
    fun adjacentStrokeAndTextBlock_contiguousHeight() {
        val strokes = listOf(strokeAtLine(2))
        val tb = TextBlock(startLineIndex = 3, heightInLines = 1, text = "memo")
        val m = compute(strokes = strokes, textBlocks = listOf(tb))!!
        val tbMaxY = tm + 4 * ls
        assertEquals(tbMaxY, m.maxY, 1f)
    }

    // ── Scale computation ───────────────────────────────────────────────

    @Test
    fun narrowContent_scaleIsOne() {
        val strokes = listOf(strokeAtLine(2, startX = 190f, endX = 200f))
        val m = compute(strokes = strokes)!!
        assertEquals(1f, m.scale, 0.001f)
    }

    @Test
    fun previewHeight_accountsForScaleAndPadding() {
        val strokes = listOf(strokeAtLine(2))
        val m = compute(strokes = strokes)!!
        val expectedHeight = (m.contentHeight * m.scale + 2 * pad).toInt()
        assertEquals(expectedHeight, m.previewHeight)
    }

    // ── Blank text blocks ignored ───────────────────────────────────────

    @Test
    fun blankTextBlock_ignoredInBounds() {
        val strokes = listOf(strokeAtLine(2, startX = 50f, endX = 300f))
        val blankTb = TextBlock(startLineIndex = 5, heightInLines = 1, text = "")
        val m = compute(strokes = strokes, textBlocks = listOf(blankTb))!!
        val line5Bottom = tm + 6 * ls
        assertTrue("Blank text block should not extend bounds", m.maxY < line5Bottom)
    }

    // ── Multi-line text block ───────────────────────────────────────────

    @Test
    fun multiLineTextBlock_heightCoversAllLines() {
        val tb = TextBlock(startLineIndex = 1, heightInLines = 3, text = "line one two three")
        val m = compute(textBlocks = listOf(tb))!!
        val expectedMinY = tm + 1 * ls
        val expectedMaxY = tm + 4 * ls
        assertEquals(expectedMinY, m.minY, 1f)
        assertEquals(expectedMaxY, m.maxY, 1f)
    }

    // ── Multiple text blocks ────────────────────────────────────────────

    @Test
    fun multipleTextBlocks_boundsEncompassAll() {
        val tb1 = TextBlock(startLineIndex = 1, heightInLines = 1, text = "first")
        val tb2 = TextBlock(startLineIndex = 5, heightInLines = 2, text = "second longer text here")
        val m = compute(textBlocks = listOf(tb1, tb2))!!
        assertEquals(tm + 1 * ls, m.minY, 1f)
        assertEquals(tm + 7 * ls, m.maxY, 1f)
    }

    @Test
    fun multipleTextBlocks_maxXReflectsWidest() {
        val narrow = TextBlock(startLineIndex = 1, heightInLines = 1, text = "hi")
        val wide = TextBlock(startLineIndex = 3, heightInLines = 1, text = "a significantly wider text block")
        val m = compute(textBlocks = listOf(narrow, wide))!!
        val narrowOnly = compute(textBlocks = listOf(narrow))!!
        assertTrue("maxX should reflect the wider text block", m.maxX > narrowOnly.maxX)
    }
}
