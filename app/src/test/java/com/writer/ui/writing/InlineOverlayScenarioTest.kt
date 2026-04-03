package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.recognition.RecognitionResult
import com.writer.recognition.StrokeClassifier
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Scenario-based tests for the inline text overlay consolidation lifecycle.
 *
 * Key invariant: the line the user is currently writing on must never change
 * its position on screen. When consolidation causes word-wrap overflow (more
 * Hershey lines than original handwriting lines), scroll offset must compensate
 * so the writing line stays fixed.
 *
 * Screen Y of writing line = TOP_MARGIN + lineIndex * LINE_SPACING
 *                            + consolidationOverflowShiftPx - scrollOffsetY
 *
 * This value must remain constant across consolidation updates.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class InlineOverlayScenarioTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var column: ColumnModel
    private lateinit var textCache: MutableMap<Int, String>
    private lateinit var lineSegmenter: LineSegmenter
    private lateinit var font: HersheyFont
    private lateinit var canvas: HandwritingCanvasView

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        column = ColumnModel()
        textCache = mutableMapOf()
        lineSegmenter = LineSegmenter()

        val ctx = RuntimeEnvironment.getApplication()
        val rawLines = ctx.assets.open("scripts.jhf").bufferedReader().readLines()
        font = HersheyFont(rawLines)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Create a simple stroke on a given line. */
    private fun stroke(lineIndex: Int, startX: Float, endX: Float, id: String): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.5f
        return InkStroke(
            strokeId = id,
            points = listOf(
                StrokePoint(startX, y - 3f, 0.5f, 0L),
                StrokePoint((startX + endX) / 2f, y + 3f, 0.5f, 50L),
                StrokePoint(endX, y - 1f, 0.5f, 100L)
            )
        )
    }

    private fun createDm(host: DisplayManagerHost, width: Int = 824, height: Int = 1648): DisplayManager {
        canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        canvas.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        canvas.layout(0, 0, width, height)
        return DisplayManager(
            canvas,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            lineSegmenter, ParagraphBuilder(StrokeClassifier(lineSegmenter)), host, font
        )
    }

    /** Compute the screen Y of the current writing line (document Y + overflow shift - scroll). */
    private fun writingLineScreenY(currentLineIndex: Int): Float {
        val docY = tm + currentLineIndex * ls
        return docY + canvas.consolidationOverflowShiftPx - canvas.scrollOffsetY
    }

    // ── Scenario 1: Basic consolidation lifecycle ───────────────────────

    @Test
    fun `lines before current are consolidated, current line is not`() {
        textCache[0] = "Hello world"
        textCache[1] = "Good morning"
        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 100f, "s2"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)
        dm.updateInlineOverlays(2)

        val overlays = canvas.inlineTextOverlays
        assertTrue("Line 0 consolidated", overlays[0]?.consolidated == true)
        assertTrue("Line 1 consolidated", overlays[1]?.consolidated == true)
        assertFalse("Line 2 (current) not consolidated",
            overlays[2]?.consolidated == true && overlays[2]?.unConsolidated != true)
    }

    @Test
    fun `advancing to next line consolidates the previous line`() {
        textCache[0] = "Hello world"
        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))

        // Phase 1: writing on line 0 — not consolidated
        val host = TestHost(column, textCache, currentLine = 0)
        val dm = createDm(host)
        dm.updateInlineOverlays(0)
        assertFalse("Line 0 should NOT be consolidated while writing on it",
            canvas.inlineTextOverlays[0]?.consolidated == true &&
            canvas.inlineTextOverlays[0]?.unConsolidated != true)

        // Phase 2: advance to line 1 — line 0 consolidates
        host.currentLine = 1
        dm.lastOverlayHash = 0  // force rebuild
        dm.updateInlineOverlays(1)
        assertTrue("Line 0 should be consolidated after advancing",
            canvas.inlineTextOverlays[0]?.consolidated == true)
    }

    @Test
    fun `short text consolidation has no overflow and no scroll change`() {
        textCache[0] = "Hello"
        textCache[1] = "World"
        column.activeStrokes.add(stroke(0, 10f, 100f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 100f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 100f, "s2"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)

        val screenYBefore = writingLineScreenY(2)
        dm.updateInlineOverlays(2)
        val screenYAfter = writingLineScreenY(2)

        assertEquals("No overflow shift for short text", 0f, canvas.consolidationOverflowShiftPx, 0.01f)
        assertEquals("Writing line screen position unchanged", screenYBefore, screenYAfter, 0.01f)
    }

    // ── Scenario 2: Overflow — writing line screen position invariant ───

    @Test
    fun `overflow consolidation keeps writing line at same screen position`() {
        // Two lines of long text that will word-wrap into 4+ Hershey lines.
        // User is writing on line 2, which should stay put on screen.
        val longLine0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val longLine1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        textCache[0] = longLine0
        textCache[1] = longLine1
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 200f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)

        val screenYBefore = writingLineScreenY(2)
        dm.updateInlineOverlays(2)
        val screenYAfter = writingLineScreenY(2)

        assertTrue("Overflow shift should be positive", canvas.consolidationOverflowShiftPx > 0f)
        assertEquals(
            "Writing line must stay at same screen position after overflow consolidation",
            screenYBefore, screenYAfter, 0.01f
        )
    }

    @Test
    fun `progressive consolidation keeps writing line stable across multiple advances`() {
        // Write 3 lines of long text, advancing one line at a time.
        // Each advance may trigger word-wrap overflow. Writing line must stay fixed.
        val long0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val long1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        val long2 = "meanwhile the crafty jester performed his most elaborate and dazzling tricks for the enchanted audience"

        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 700f, "s2"))
        column.activeStrokes.add(stroke(3, 10f, 200f, "s3_writing"))

        val host = TestHost(column, textCache, currentLine = 0)
        val dm = createDm(host)

        // Advance to line 1 — line 0 consolidates
        textCache[0] = long0
        host.currentLine = 1
        dm.lastOverlayHash = 0
        val screenY1Before = writingLineScreenY(1)
        dm.updateInlineOverlays(1)
        val screenY1After = writingLineScreenY(1)
        assertEquals("Writing line stable after first consolidation",
            screenY1Before, screenY1After, 0.01f)

        // Advance to line 2 — lines 0+1 consolidate together
        textCache[1] = long1
        host.currentLine = 2
        dm.lastOverlayHash = 0
        val screenY2Before = writingLineScreenY(2)
        dm.updateInlineOverlays(2)
        val screenY2After = writingLineScreenY(2)
        assertEquals("Writing line stable after second consolidation",
            screenY2Before, screenY2After, 0.01f)

        // Advance to line 3 — all 3 lines consolidate
        textCache[2] = long2
        host.currentLine = 3
        dm.lastOverlayHash = 0
        val screenY3Before = writingLineScreenY(3)
        dm.updateInlineOverlays(3)
        val screenY3After = writingLineScreenY(3)
        assertEquals("Writing line stable after third consolidation",
            screenY3Before, screenY3After, 0.01f)
    }

    @Test
    fun `overflow grows then shrinks when paragraph breaks are introduced`() {
        // Lines 0-1 form a long paragraph that overflows.
        // Then line 3 has separate short text (gap at line 2 = paragraph break).
        // User writes on line 4.
        val long0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val long1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        textCache[0] = long0
        textCache[1] = long1
        textCache[3] = "Short separate paragraph"
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(3, 10f, 300f, "s3"))
        column.activeStrokes.add(stroke(4, 10f, 200f, "s4_writing"))

        val host = TestHost(column, textCache, currentLine = 4)
        val dm = createDm(host)

        val screenYBefore = writingLineScreenY(4)
        dm.updateInlineOverlays(4)
        val screenYAfter = writingLineScreenY(4)

        assertEquals("Writing line must stay at same screen position",
            screenYBefore, screenYAfter, 0.01f)
    }

    // ── Scenario 3: No overflow when large gap before current line ──────

    @Test
    fun `word-wrap overflow absorbed by gap between last consolidated and current line`() {
        // Lines 0-1 have long text that wraps to several Hershey lines,
        // but user is on line 30 — plenty of room, no overflow.
        val long0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val long1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        textCache[0] = long0
        textCache[1] = long1
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(30, 10f, 200f, "s30_writing"))

        val host = TestHost(column, textCache, currentLine = 30)
        val dm = createDm(host)
        dm.updateInlineOverlays(30)

        assertEquals("No overflow when gap is large enough",
            0f, canvas.consolidationOverflowShiftPx, 0.01f)
        assertEquals("No scroll adjustment needed",
            0f, canvas.scrollOffsetY, 0.01f)
    }

    // ── Scenario 4: Reconsolidation after un-consolidate ────────────────

    @Test
    fun `un-consolidate then re-consolidate restores same screen position`() {
        textCache[0] = "Hello world from line zero"
        textCache[1] = "Goodbye moon from line one"
        column.activeStrokes.add(stroke(0, 10f, 300f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 300f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 100f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)
        dm.updateInlineOverlays(2)
        val screenYConsolidated = writingLineScreenY(2)

        // Un-consolidate line 0's paragraph
        dm.toggleUnConsolidate(0)
        dm.lastOverlayHash = 0
        dm.updateInlineOverlays(2)
        val screenYUnconsolidated = writingLineScreenY(2)

        assertEquals("Writing line stable after un-consolidation",
            screenYConsolidated, screenYUnconsolidated, 0.01f)

        // Re-consolidate (toggle again)
        dm.toggleUnConsolidate(0)
        dm.lastOverlayHash = 0
        dm.updateInlineOverlays(2)
        val screenYReconsolidated = writingLineScreenY(2)

        assertEquals("Writing line stable after re-consolidation",
            screenYConsolidated, screenYReconsolidated, 0.01f)
    }

    // ── Scenario 5: Consolidation with diagram breaks ───────────────────

    @Test
    fun `diagram line breaks paragraph grouping and each group consolidates independently`() {
        textCache[0] = "Hello"
        textCache[1] = "World"
        // Line 2 is a diagram — breaks the paragraph
        textCache[3] = "After diagram"
        column.activeStrokes.add(stroke(0, 10f, 100f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 100f, "s1"))
        column.activeStrokes.add(stroke(3, 10f, 200f, "s3"))
        column.activeStrokes.add(stroke(4, 10f, 100f, "s4_writing"))
        column.diagramAreas.add(DiagramArea(startLineIndex = 2, heightInLines = 1))

        val host = TestHost(column, textCache, currentLine = 4)
        val dm = createDm(host)
        dm.updateInlineOverlays(4)

        val overlays = canvas.inlineTextOverlays
        // Lines 0-1 should be one paragraph, line 3 another
        assertTrue("Line 0 consolidated", overlays[0]?.consolidated == true)
        assertTrue("Line 1 consolidated", overlays[1]?.consolidated == true)
        assertTrue("Line 3 consolidated", overlays[3]?.consolidated == true)
        // Line 2 (diagram) should not have a text overlay
        assertNull("Line 2 (diagram) should have no overlay", overlays[2])
    }

    @Test
    fun `word-wrap overflow must not spill into diagram area`() {
        // Long paragraph on lines 0-1 that would word-wrap to 3+ Hershey lines.
        // Line 3 is a diagram. Overflow must NOT create overlays on line 3.
        val long0 = "The gilded monster went to the magnificent grand ball and danced all night"
        val long1 = "and the elegant princess joined in until the clock struck twelve"
        textCache[0] = long0
        textCache[1] = long1
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(4, 10f, 100f, "s4_writing"))
        column.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 1))

        val host = TestHost(column, textCache, currentLine = 4)
        val dm = createDm(host)
        dm.updateInlineOverlays(4)

        val overlays = canvas.inlineTextOverlays
        // Diagram line must not have a consolidated text overlay
        val diagramOverlay = overlays[3]
        assertTrue("Diagram line should have no consolidated text overlay",
            diagramOverlay == null || !diagramOverlay.consolidated || diagramOverlay.recognizedText.isBlank())
    }

    // ── Scenario 6: All text consolidated on document reload ────────────

    @Test
    fun `on document reload all lines consolidate and writing line is stable`() {
        // Simulate document reload: all lines have cached text,
        // currentLineIndex advances past the highest line.
        textCache[0] = "First line of the document"
        textCache[1] = "Second line continues"
        textCache[2] = "Third line wraps up"
        column.activeStrokes.add(stroke(0, 10f, 300f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 300f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 300f, "s2"))

        // Advance past highest line (simulating restoreState)
        val host = TestHost(column, textCache, currentLine = 3)
        val dm = createDm(host)

        val screenYBefore = writingLineScreenY(3)
        dm.updateInlineOverlays(3)
        val screenYAfter = writingLineScreenY(3)

        assertTrue("Line 0 consolidated", canvas.inlineTextOverlays[0]?.consolidated == true)
        assertTrue("Line 1 consolidated", canvas.inlineTextOverlays[1]?.consolidated == true)
        assertTrue("Line 2 consolidated", canvas.inlineTextOverlays[2]?.consolidated == true)
        assertEquals("Writing line stable on reload",
            screenYBefore, screenYAfter, 0.01f)
    }

    // ── Scenario 7: Overflow with scrolled viewport ─────────────────────

    @Test
    fun `overflow consolidation with pre-existing scroll keeps writing line stable`() {
        // User has scrolled down — scrollOffsetY > 0 before consolidation.
        // Overflow must still keep the writing line at the same screen position.
        val long0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val long1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        textCache[0] = long0
        textCache[1] = long1
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 200f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)

        // Pre-existing scroll (user scrolled down a bit)
        canvas.scrollOffsetY = 2 * ls

        val screenYBefore = writingLineScreenY(2)
        dm.updateInlineOverlays(2)
        val screenYAfter = writingLineScreenY(2)

        assertTrue("Overflow shift should be positive", canvas.consolidationOverflowShiftPx > 0f)
        assertEquals(
            "Writing line must stay at same screen position even with pre-existing scroll",
            screenYBefore, screenYAfter, 0.01f
        )
    }

    // ── Scenario 8: Single line wrapping (one source → multiple Hershey) ─

    @Test
    fun `single very long line wraps to multiple Hershey lines without moving writing line`() {
        // One source line that is extremely long — wraps to 3+ Hershey lines.
        val veryLong = "The quick brown fox jumped over the lazy dog and then ran across the field " +
            "to find the hidden treasure buried beneath the ancient oak tree near the river bank"
        textCache[0] = veryLong
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1_writing"))

        val host = TestHost(column, textCache, currentLine = 1)
        val dm = createDm(host)

        val screenYBefore = writingLineScreenY(1)
        dm.updateInlineOverlays(1)
        val screenYAfter = writingLineScreenY(1)

        // The wrapped text should occupy multiple line slots
        val consolidatedCount = canvas.inlineTextOverlays.count {
            it.value.consolidated && it.value.recognizedText.isNotBlank()
        }
        assertTrue("Should wrap to multiple Hershey lines", consolidatedCount >= 2)

        assertEquals(
            "Writing line must stay at same screen position after single-line overflow",
            screenYBefore, screenYAfter, 0.01f
        )
    }

    // ── Scenario 9: Stroke capture accounts for overflow shift ────────

    @Test
    fun `stroke on shifted writing line lands on correct document line`() {
        // Overflow shifts the writing line down on screen. A stroke captured
        // at the shifted screen position must map to the expected document line,
        // not a higher line (which would cause premature consolidation).
        val veryLong = "The quick brown fox jumped over the lazy dog and then ran across the field " +
            "to find the hidden treasure buried beneath the ancient oak tree near the river bank"
        textCache[0] = veryLong
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1_writing"))

        val host = TestHost(column, textCache, currentLine = 1)
        val dm = createDm(host)
        dm.updateInlineOverlays(1)

        assertTrue("Overflow shift should be positive", canvas.consolidationOverflowShiftPx > 0f)

        // Simulate the user writing on the next visual line (one LINE_SPACING below
        // the shifted writing line). The screen Y of the writing line is:
        //   docY + overflowShift - scrollOffsetY
        // One line below that in screen space:
        val writingScreenY = writingLineScreenY(1) + ls + ls * 0.5f
        // Convert screen Y to document Y the same way toDocStrokePoint does:
        val docY = writingScreenY + canvas.scrollOffsetY - canvas.consolidationOverflowShiftPx
        val lineIdx = lineSegmenter.getLineIndex(docY)

        assertEquals("Stroke on next visual line should land on document line 2",
            2, lineIdx)
    }

    @Test
    fun `stroke capture with no overflow is unchanged`() {
        // When there's no overflow, subtracting 0 from Y has no effect.
        textCache[0] = "Hello"
        column.activeStrokes.add(stroke(0, 10f, 100f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 100f, "s1_writing"))

        val host = TestHost(column, textCache, currentLine = 1)
        val dm = createDm(host)
        dm.updateInlineOverlays(1)

        assertEquals("No overflow shift", 0f, canvas.consolidationOverflowShiftPx, 0.01f)

        // Screen Y for writing on line 1
        val screenY = tm + 1 * ls + ls * 0.5f
        // Document Y = screenY + scrollOffsetY - overflowShift = screenY + 0 - 0
        val docY = screenY + canvas.scrollOffsetY - canvas.consolidationOverflowShiftPx
        val lineIdx = lineSegmenter.getLineIndex(docY)

        assertEquals("Stroke lands on document line 1", 1, lineIdx)
    }

    // ── Scenario 10: Scratch-out targets raw strokes in overflow zone ──

    @Test
    fun `scratch on overflow-shifted line targets raw strokes not consolidated overlay`() {
        // When consolidation overflows, lines at/past currentWritingLineIndex have
        // both a consolidated overlay (Hershey text) AND raw strokes rendered shifted.
        // A scratch-out at the shifted position should target raw strokes, not the
        // consolidated overlay. Otherwise scratch-outs on the user's handwriting fail.
        val veryLong = "The quick brown fox jumped over the lazy dog and then ran across the field " +
            "to find the hidden treasure buried beneath the ancient oak tree near the river bank"
        textCache[0] = veryLong
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        // User's raw handwriting on line 2 (will be shifted down by overflow)
        column.activeStrokes.add(stroke(2, 10f, 400f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)
        dm.updateInlineOverlays(2)

        assertTrue("Overflow shift should be positive", canvas.consolidationOverflowShiftPx > 0f)

        // The consolidated overlay may extend to line 2 (word-wrap overflow).
        // A scratch at line 2's document Y hits the overlay.
        val overlayAtWritingLine = canvas.inlineTextOverlays[2]

        // Key invariant: even if there's a consolidated overlay at the writing line,
        // a scratch at/past currentWritingLineIndex should be treated as targeting
        // raw strokes, not the consolidated overlay.
        val scratchLineIdx = 2
        val hitsShiftedRawStrokes = canvas.consolidationOverflowShiftPx > 0f
            && host.currentLine >= 0 && scratchLineIdx >= host.currentLine
        assertTrue("Scratch at writing line in overflow zone should hit raw strokes",
            hitsShiftedRawStrokes)

        // Verify that raw strokes exist at this document line to be scratched
        val rawStrokesOnLine = column.activeStrokes.filter { stroke ->
            lineSegmenter.getLineIndex((stroke.points.first().y + stroke.points.last().y) / 2f) == 2
        }
        assertTrue("Raw strokes should exist on the writing line",
            rawStrokesOnLine.isNotEmpty())
    }

    @Test
    fun `scratch on consolidated line without overflow targets Hershey text`() {
        // Normal case: no overflow, scratch on a consolidated line should hit
        // the Hershey overlay, not raw strokes.
        textCache[0] = "Hello world"
        textCache[1] = "Goodbye moon"
        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 100f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host)
        dm.updateInlineOverlays(2)

        assertEquals("No overflow shift", 0f, canvas.consolidationOverflowShiftPx, 0.01f)

        // Scratch at line 0 (consolidated, no overflow) should target overlay
        val scratchLineIdx = 0
        val hitsShiftedRawStrokes = canvas.consolidationOverflowShiftPx > 0f
            && host.currentLine >= 0 && scratchLineIdx >= host.currentLine
        assertFalse("Scratch on consolidated line without overflow should NOT hit raw strokes",
            hitsShiftedRawStrokes)

        val overlay = canvas.inlineTextOverlays[0]
        assertNotNull("Consolidated overlay should exist at line 0", overlay)
        assertTrue("Overlay should be consolidated", overlay!!.consolidated)
    }

    // ── Scenario 11: All words present after overflow consolidation ────

    @Test
    fun `all words present in consolidated text after overflow word-wrap`() {
        // Reproduce Palma scenario: 3 source lines form a paragraph that
        // word-wraps to more Hershey lines than the source. All words must
        // appear in the consolidated overlays — none should be culled or lost.
        textCache[0] = "Combinatrine"
        textCache[1] = "Exuberance"
        textCache[2] = "in the closet of the"
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 700f, "s2"))
        // User is writing on line 3 (strokes on line 3 were scratched, cache removed)
        column.activeStrokes.add(stroke(3, 10f, 200f, "s3_writing"))

        val host = TestHost(column, textCache, currentLine = 3)
        // Use narrow width to force word-wrap (Palma-like: 408px)
        val dm = createDm(host, width = 408, height = 1648)
        dm.updateInlineOverlays(3)

        // Collect ALL consolidated text across all overlays
        val allConsolidatedText = canvas.inlineTextOverlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(" ") { it.value.recognizedText }

        val expectedFullText = "Combinatrine Exuberance in the closet of the"

        // Log detailed overlay state for diagnostics
        val overlayDetails = canvas.inlineTextOverlays.entries
            .sortedBy { it.key }
            .joinToString("\n") { "  line ${it.key}: consolidated=${it.value.consolidated} unConsol=${it.value.unConsolidated} text='${it.value.recognizedText}' strokes=${it.value.syntheticStrokes.size}" }
        println("Overlay state (width=408):\n$overlayDetails")
        println("Consolidated text: '$allConsolidatedText'")
        println("Overflow shift: ${canvas.consolidationOverflowShiftPx}, scroll: ${canvas.scrollOffsetY}")

        assertEquals("All words must appear in consolidated text",
            expectedFullText, allConsolidatedText)

        val consolidatedCount = canvas.inlineTextOverlays.count {
            it.value.consolidated && it.value.recognizedText.isNotBlank()
        }
        assertTrue("Should have multiple consolidated lines on narrow screen:\n$overlayDetails",
            consolidatedCount >= 2)
    }

    @Test
    fun `all words visible after un-consolidate and re-consolidate cycle without overflow`() {
        // Un-consolidation works when there's no overflow. After toggling
        // on and off, all words must still appear.
        textCache[0] = "Hello world"
        textCache[1] = "Good morning"
        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 100f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host, width = 824, height = 1648)
        dm.updateInlineOverlays(2)

        assertEquals("No overflow", 0f, canvas.consolidationOverflowShiftPx, 0.01f)

        val textBefore = canvas.inlineTextOverlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(" ") { it.value.recognizedText }

        // Un-consolidate
        dm.toggleUnConsolidate(0)
        dm.lastOverlayHash = 0
        dm.updateInlineOverlays(2)

        val unconsolCount = canvas.inlineTextOverlays.count { it.value.unConsolidated }
        assertTrue("Some lines should be un-consolidated", unconsolCount > 0)

        // Re-consolidate
        dm.toggleUnConsolidate(0)
        dm.lastOverlayHash = 0
        dm.updateInlineOverlays(2)

        val textAfter = canvas.inlineTextOverlays.entries
            .filter { it.value.consolidated && !it.value.unConsolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(" ") { it.value.recognizedText }

        val expectedFullText = "Hello world Good morning"
        assertEquals("All words present before toggle", expectedFullText, textBefore)
        assertEquals("All words present after toggle cycle", expectedFullText, textAfter)
    }

    @Test
    fun `un-consolidation blocked when word-wrap causes overflow`() {
        // When word-wrap causes overflow (more Hershey lines than source lines),
        // un-consolidation is blocked because raw strokes would show at positions
        // that don't match the word-wrapped text, causing the user to scratch
        // the wrong words.
        textCache[0] = "Combinatrine"
        textCache[1] = "Exuberance"
        textCache[2] = "in the closet of the"
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 700f, "s2"))
        column.activeStrokes.add(stroke(3, 10f, 200f, "s3_writing"))

        val host = TestHost(column, textCache, currentLine = 3)
        val dm = createDm(host, width = 408, height = 1648)
        dm.updateInlineOverlays(3)

        // Verify we have overflow lines
        val maxConsolidatedLine = canvas.inlineTextOverlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .maxOfOrNull { it.key } ?: -1
        assertTrue("Should have overflow lines past source lines",
            maxConsolidatedLine >= 3)

        // Attempt to un-consolidate — should be blocked due to overflow
        dm.toggleUnConsolidate(1)
        dm.lastOverlayHash = 0
        dm.updateInlineOverlays(3)

        // All lines should remain consolidated (toggle was blocked)
        val unconsolidated = canvas.inlineTextOverlays.count { it.value.unConsolidated }
        assertEquals("Un-consolidation should be blocked when overflow is active", 0, unconsolidated)
    }

    @Test
    fun `un-consolidation allowed when no overflow`() {
        // When text fits without overflow, un-consolidation works normally.
        textCache[0] = "Hello"
        textCache[1] = "World"
        column.activeStrokes.add(stroke(0, 10f, 100f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 100f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 100f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        val dm = createDm(host, width = 408, height = 1648)
        dm.updateInlineOverlays(2)

        assertEquals("No overflow", 0f, canvas.consolidationOverflowShiftPx, 0.01f)

        dm.toggleUnConsolidate(0)
        dm.lastOverlayHash = 0
        dm.updateInlineOverlays(2)

        val unconsolidated = canvas.inlineTextOverlays.count { it.value.unConsolidated }
        assertTrue("Un-consolidation should work without overflow", unconsolidated > 0)
    }

    @Test
    fun `overflow Hershey lines have synthetic strokes generated`() {
        // When word-wrap overflows, ALL Hershey lines must have synthetic strokes,
        // not just the lines within the viewport. This catches viewport culling bugs
        // where scroll compensation shifts the viewport past some Hershey lines.
        textCache[0] = "Combinatrine"
        textCache[1] = "Exuberance"
        textCache[2] = "in the closet of the"
        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 700f, "s2"))
        column.activeStrokes.add(stroke(3, 10f, 200f, "s3_writing"))

        val host = TestHost(column, textCache, currentLine = 3)
        val dm = createDm(host, width = 408, height = 1648)
        dm.updateInlineOverlays(3)

        // Every consolidated overlay with text must have synthetic strokes
        for ((lineIdx, state) in canvas.inlineTextOverlays) {
            if (state.consolidated && state.recognizedText.isNotBlank()) {
                assertTrue(
                    "Line $lineIdx ('${state.recognizedText}') must have synthetic strokes",
                    state.syntheticStrokes.isNotEmpty()
                )
            }
        }
    }

    // ── Test host ───────────────────────────────────────────────────────

    private class TestHost(
        override val columnModel: ColumnModel,
        val cache: MutableMap<Int, String>,
        var currentLine: Int = 0
    ) : DisplayManagerHost {
        override val diagramManager: DiagramManager by lazy {
            val stubRecognizer = object : com.writer.recognition.TextRecognizer {
                override suspend fun initialize(languageTag: String) {}
                override suspend fun recognizeLine(line: com.writer.model.InkLine, preContext: String) = ""
                override fun close() {}
            }
            val stubCanvas = object : DiagramCanvas {
                override var diagramAreas: List<DiagramArea>
                    get() = columnModel.diagramAreas
                    set(_) {}
                override var scrollOffsetY = 0f
                override fun loadStrokes(strokes: List<InkStroke>) {}
                override fun pauseAndRedraw() {}
            }
            val stubHost = object : DiagramManagerHost {
                override fun onDiagramAreasChanged() {}
                override fun getLineTextCache() = cache
            }
            DiagramManager(columnModel, LineSegmenter(), stubRecognizer, stubCanvas,
                stubHost, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        }
        override val lineTextCache: Map<Int, String> get() = cache
        override val highestLineIndex get() = currentLine
        override val currentLineIndex get() = currentLine
        override val lineRecognitionResults: Map<Int, RecognitionResult> = emptyMap()
        override val pendingWordEdit: PendingWordEdit? = null
        override fun eagerRecognizeLine(lineIndex: Int) {}
    }
}
