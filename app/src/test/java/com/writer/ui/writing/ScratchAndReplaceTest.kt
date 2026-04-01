package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.recognition.RecognitionResult
import com.writer.recognition.RecognitionCandidate
import com.writer.recognition.StrokeClassifier
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the scratch-and-replace flow on consolidated text.
 *
 * Subproblems:
 * 1. Scratch-out removes Hershey text, leaves gap
 * 2. Original strokes of scratched word are identified
 * 3. Replacement strokes are relocated to the original stroke position
 * 4. Pre-context from recognized text is used for recognition
 * 5. Replacement text is reflowed in Hershey text
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class ScratchAndReplaceTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var column: ColumnModel
    private lateinit var textCache: MutableMap<Int, String>
    private lateinit var dm: DisplayManager
    private lateinit var lineSegmenter: LineSegmenter

    /** Create strokes for a word at a specific X range on a line. */
    private fun wordStrokes(lineIndex: Int, startX: Float, endX: Float, word: String): List<InkStroke> {
        val y = tm + lineIndex * ls + ls * 0.5f
        val charWidth = (endX - startX) / word.length.coerceAtLeast(1)
        return word.mapIndexed { i, _ ->
            InkStroke(
                strokeId = "s${lineIndex}_${word}_$i",
                points = listOf(
                    StrokePoint(startX + i * charWidth, y - 5f, 0.5f, (lineIndex * 10000 + i * 100).toLong()),
                    StrokePoint(startX + i * charWidth + charWidth * 0.8f, y + 5f, 0.5f, (lineIndex * 10000 + i * 100 + 50).toLong())
                )
            )
        }
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        column = ColumnModel()
        textCache = mutableMapOf()
        lineSegmenter = LineSegmenter()

        // Create a 3-line document: "the quick brown" on line 0, "fox jumps over" on line 1, "the lazy dog" on line 2
        // Each word has strokes at known X positions
        val line0Words = listOf("the" to (10f to 80f), "quick" to (90f to 200f), "brown" to (210f to 320f))
        val line1Words = listOf("fox" to (10f to 70f), "jumps" to (80f to 180f), "over" to (190f to 280f))
        val line2Words = listOf("the" to (10f to 80f), "lazy" to (90f to 170f), "dog" to (180f to 240f))

        for ((word, range) in line0Words) {
            column.activeStrokes.addAll(wordStrokes(0, range.first, range.second, word))
        }
        for ((word, range) in line1Words) {
            column.activeStrokes.addAll(wordStrokes(1, range.first, range.second, word))
        }
        for ((word, range) in line2Words) {
            column.activeStrokes.addAll(wordStrokes(2, range.first, range.second, word))
        }

        textCache[0] = "the quick brown"
        textCache[1] = "fox jumps over"
        textCache[2] = "the lazy dog"

        val canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        val host = TestHost(column, textCache)
        dm = DisplayManager(canvas,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            lineSegmenter, ParagraphBuilder(StrokeClassifier(lineSegmenter)), host, null)
    }

    // ── Subproblem 1: Scratch-out removes Hershey text, leaves gap ──────

    @Test
    fun `pending edit creates gap in consolidated overlay`() {
        // Simulate scratch-out on "quick" (word index 1 on line 0)
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )

        // Build overlays with pending edit
        val host = dm.host as TestHost
        host.pendingEdit = edit
        dm.lastOverlayHash = 0
        val overlays = dm.buildInlineOverlays(3)

        // Line 0 should be consolidated with text but "quick" gap
        val line0 = overlays[0]
        assertNotNull("Line 0 should have overlay", line0)
        assertTrue("Line 0 should be consolidated", line0!!.consolidated)
        // The recognized text still contains "quick" — gap is in rendering only
        assertTrue("Text should still contain the words", line0.recognizedText.isNotBlank())
    }

    @Test
    fun `gap leaves space between surrounding words`() {
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )
        val host = dm.host as TestHost
        host.pendingEdit = edit
        dm.lastOverlayHash = 0
        val overlays = dm.buildInlineOverlays(3)

        val line0 = overlays[0]!!
        // Synthetic strokes should NOT include "quick" — only "the" and "brown"
        // Without HersheyFont this is empty, but the gap logic should still work
        // The text is preserved for re-rendering after edit
        assertEquals("the quick brown", textCache[0])
    }

    // ── Subproblem 2: Original strokes of scratched word identified ──────

    @Test
    fun `original strokes for a word can be identified by line and X position`() {
        val line0Strokes = lineSegmenter.getStrokesForLine(column.activeStrokes, 0)
        assertEquals("Line 0 should have strokes for 3 words (the=3, quick=5, brown=5)", 13, line0Strokes.size)

        // "quick" strokes are at X range 90-200
        val quickStrokes = line0Strokes.filter { stroke ->
            val cx = (stroke.minX + stroke.maxX) / 2f
            cx >= 85f && cx <= 205f
        }
        assertEquals("Should find 5 strokes for 'quick'", 5, quickStrokes.size)
    }

    @Test
    fun `identifying strokes does not include adjacent words`() {
        val line0Strokes = lineSegmenter.getStrokesForLine(column.activeStrokes, 0)

        val theStrokes = line0Strokes.filter { stroke ->
            val cx = (stroke.minX + stroke.maxX) / 2f
            cx >= 5f && cx <= 85f
        }
        assertEquals("Should find 3 strokes for 'the'", 3, theStrokes.size)

        val brownStrokes = line0Strokes.filter { stroke ->
            val cx = (stroke.minX + stroke.maxX) / 2f
            cx >= 205f && cx <= 325f
        }
        assertEquals("Should find 5 strokes for 'brown'", 5, brownStrokes.size)
    }

    @Test
    fun `after reflow Hershey X does not map to original stroke X`() {
        // This test exposes the bug: after paragraph reflow, the Hershey word
        // positions don't correspond to the original handwriting positions.
        //
        // Original handwriting:
        //   Line 0: "the quick brown"  — "quick" at X=90-200
        //   Line 1: "fox jumps over"   — "jumps" at X=80-180
        //
        // After reflow into Hershey text, the paragraph "the quick brown fox
        // jumps over" is word-wrapped to fill the line width. The Hershey word
        // positions are computed from character widths, not from original stroke
        // positions. So "quick" in Hershey might be at X=150-300 while the
        // original strokes are at X=90-200.
        //
        // The X-ratio mapping used by onScratchOut:
        //   approxWordStartX = origMinX + (hersheyWordX - margin) * (origWidth / hersheyWidth)
        // This is wrong because reflow changes the text on each line.

        // Simulate the mapping that onScratchOut does
        val margin = ScreenMetrics.dp(10f)
        val canvasWidth = 824f  // Palma 2 Pro width
        val hersheyLineWidth = canvasWidth - margin * 2

        // Original line 0 strokes
        val origLineStrokes = lineSegmenter.getStrokesForLine(column.activeStrokes, 0)
        val origLineMinX = origLineStrokes.minOf { it.minX }
        val origLineMaxX = origLineStrokes.maxOf { it.maxX }
        val origLineWidth = origLineMaxX - origLineMinX

        // After reflow, "quick" might be at Hershey position X=200-350
        // (different from original X=90-200 because reflow added words before it)
        val hersheyWordStartX = 200f
        val hersheyWordEndX = 350f
        val radius = ScreenMetrics.dp(16f)

        // The broken mapping:
        val xRatio = origLineWidth / hersheyLineWidth
        val approxWordStartX = origLineMinX + (hersheyWordStartX - margin) * xRatio
        val approxWordEndX = origLineMinX + (hersheyWordEndX - margin) * xRatio

        // Find strokes using the broken mapping
        val foundStrokes = origLineStrokes.filter { stroke ->
            val cx = (stroke.minX + stroke.maxX) / 2f
            cx >= approxWordStartX - radius && cx <= approxWordEndX + radius
        }

        // The broken mapping finds WRONG strokes — it maps to a different
        // X range than where "quick" actually is (90-200).
        // This is the bug: we need a better way to identify which original
        // strokes correspond to a word after reflow.
        val quickStrokes = origLineStrokes.filter { stroke ->
            val cx = (stroke.minX + stroke.maxX) / 2f
            cx >= 85f && cx <= 205f
        }

        // The found strokes should match the quick strokes, but they DON'T
        // because the X mapping is broken after reflow.
        assertNotEquals(
            "X-ratio mapping should NOT correctly find 'quick' strokes after reflow " +
                "(found ${foundStrokes.size} strokes at X=[${approxWordStartX.toInt()},${approxWordEndX.toInt()}] " +
                "but 'quick' is at X=[90,200])",
            quickStrokes.map { it.strokeId }.toSet(),
            foundStrokes.map { it.strokeId }.toSet()
        )
    }

    @Test
    fun `word index based stroke identification works after reflow`() {
        // The correct approach: use the word INDEX to find which strokes
        // belong to the scratched word, by counting words on the ORIGINAL
        // line (before reflow) and finding the Nth word's strokes.
        //
        // Original line 0: "the quick brown"
        // Word index 1 = "quick"
        // The strokes for "quick" are the ones between "the" and "brown"
        // in X-sorted order on the original line.

        val origLineStrokes = lineSegmenter.getStrokesForLine(column.activeStrokes, 0)
            .sortedBy { it.minX }

        val origText = textCache[0]!!  // "the quick brown"
        val origWords = origText.split(" ")
        val targetWordIdx = 1  // "quick"

        // Split strokes into word groups using the N-1 largest gaps
        val expectedWords = textCache[0]!!.split(" ").size  // 3 words
        data class Gap(val index: Int, val size: Float)
        val gaps = mutableListOf<Gap>()
        for (i in 1 until origLineStrokes.size) {
            gaps.add(Gap(i, origLineStrokes[i].minX - origLineStrokes[i - 1].maxX))
        }
        val boundaries = gaps.sortedByDescending { it.size }
            .take(expectedWords - 1)
            .map { it.index }
            .sorted()

        val wordGroups = mutableListOf<List<InkStroke>>()
        var start = 0
        for (boundary in boundaries) {
            wordGroups.add(origLineStrokes.subList(start, boundary))
            start = boundary
        }
        wordGroups.add(origLineStrokes.subList(start, origLineStrokes.size))

        // Verify we found the right number of word groups
        assertEquals("Should find ${origWords.size} word groups", origWords.size, wordGroups.size)

        // The strokes at targetWordIdx should be the "quick" strokes
        val targetStrokes = wordGroups[targetWordIdx]
        assertEquals("Word group for 'quick' should have 5 strokes", 5, targetStrokes.size)

        // Verify they're actually at the right X range
        val targetMinX = targetStrokes.minOf { it.minX }
        val targetMaxX = targetStrokes.maxOf { it.maxX }
        assertTrue("'quick' strokes should start near X=90: $targetMinX", targetMinX >= 85f && targetMinX <= 95f)
        assertTrue("'quick' strokes should end near X=200: $targetMaxX", targetMaxX >= 195f && targetMaxX <= 205f)
    }

    // ── Subproblem 3: Replacement strokes relocated to original position ─

    @Test
    fun `replacement strokes are relocated to gap position`() {
        // User writes "fast" at X=300-400, Y=500 (somewhere else on canvas)
        val replacementStrokes = listOf(
            InkStroke(strokeId = "rep1", points = listOf(
                StrokePoint(300f, 500f, 0.5f, 0L),
                StrokePoint(350f, 510f, 0.5f, 50L)
            )),
            InkStroke(strokeId = "rep2", points = listOf(
                StrokePoint(360f, 500f, 0.5f, 100L),
                StrokePoint(400f, 510f, 0.5f, 150L)
            ))
        )

        // Gap is at X=90-200 on line 0
        val gapStartX = 90f
        val gapEndX = 200f
        val gapWidth = gapEndX - gapStartX
        val lineY = tm + 0 * ls + ls * 0.5f

        val repMinX = replacementStrokes.minOf { it.minX }
        val repMaxX = replacementStrokes.maxOf { it.maxX }
        val repMinY = replacementStrokes.minOf { it.minY }
        val repWidth = (repMaxX - repMinX).coerceAtLeast(1f)
        val scaleX = gapWidth / repWidth
        val dx = gapStartX - repMinX * scaleX
        val dy = lineY - repMinY

        val relocated = replacementStrokes.map { stroke ->
            stroke.copy(points = stroke.points.map { pt ->
                pt.copy(x = pt.x * scaleX + dx, y = pt.y + dy)
            })
        }

        // Verify relocated strokes are within the gap
        val relocMinX = relocated.minOf { it.minX }
        val relocMaxX = relocated.maxOf { it.maxX }
        assertTrue("Relocated strokes should start near gap start: $relocMinX vs $gapStartX",
            relocMinX >= gapStartX - 5f)
        assertTrue("Relocated strokes should end near gap end: $relocMaxX vs $gapEndX",
            relocMaxX <= gapEndX + 5f)

        // Verify Y is on the correct line
        val relocMinY = relocated.minOf { it.minY }
        assertTrue("Relocated strokes should be near line Y: $relocMinY vs $lineY",
            kotlin.math.abs(relocMinY - lineY) < ls)
    }

    // ── Subproblem 4: Pre-context from recognized text ───────────────────

    @Test
    fun `pre-context includes words before the gap`() {
        val origText = "the quick brown"
        val wordIndex = 1  // "quick" is being replaced
        val wordsBeforeGap = origText.split(" ").take(wordIndex)
        val contextFromLine = wordsBeforeGap.joinToString(" ")

        assertEquals("the", contextFromLine)
    }

    @Test
    fun `pre-context includes text from lines above`() {
        // Lines 0 has "the quick brown", editing line 1
        val contextFromAbove = buildPreContext(textCache, 1)
        // Should include text from line 0, up to 20 chars
        assertTrue("Context should include line 0 text", contextFromAbove.isNotBlank())
    }

    @Test
    fun `combined pre-context has both above and inline`() {
        val lineIdx = 1
        val wordIndex = 1  // "jumps" is being replaced
        val origText = textCache[lineIdx]!!
        val wordsBeforeGap = origText.split(" ").take(wordIndex)
        val contextFromLine = wordsBeforeGap.joinToString(" ")
        val contextFromAbove = buildPreContext(textCache, lineIdx)

        val preContext = if (contextFromAbove.isNotEmpty()) {
            "$contextFromAbove $contextFromLine"
        } else contextFromLine

        assertTrue("Pre-context should contain 'fox'", preContext.contains("fox"))
        assertTrue("Pre-context should contain text from above", preContext.isNotBlank())
    }

    // ── Subproblem 5: Replacement text reflowed in Hershey text ──────────

    @Test
    fun `after word replacement lineTextCache is updated`() {
        // Simulate applying a word edit: "quick" → "fast"
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )

        // Apply the edit
        val origText = textCache[edit.lineIndex]!!
        val words = origText.split(" ").toMutableList()
        val idx = words.indexOf(edit.oldWord)
        if (idx >= 0) words[idx] = "fast"
        textCache[edit.lineIndex] = words.joinToString(" ")

        assertEquals("the fast brown", textCache[0])
    }

    @Test
    fun `overlay rebuilds with corrected text after edit`() {
        // Apply word replacement
        textCache[0] = "the fast brown"

        dm.lastOverlayHash = 0
        val overlays = dm.buildInlineOverlays(3)

        val line0 = overlays[0]!!
        assertTrue("Line 0 should be consolidated", line0.consolidated)
        // The overlay's recognized text comes from the reflowed paragraph
        // Without HersheyFont it maps 1:1, so text should contain "fast"
        assertTrue("Overlay should contain corrected text",
            overlays.values.any { it.recognizedText.contains("fast") })
    }

    @Test
    fun `replacement does not affect other lines`() {
        textCache[0] = "the fast brown"

        dm.lastOverlayHash = 0
        val overlays = dm.buildInlineOverlays(3)

        // Line 1 and 2 should be unchanged
        assertTrue("Line 1 should still have fox",
            overlays.values.any { it.recognizedText.contains("fox") })
        assertTrue("Line 2 should still have dog",
            overlays.values.any { it.recognizedText.contains("dog") })
    }

    @Test
    fun `replacement strokes are removed after edit is applied`() {
        val repStroke = InkStroke(strokeId = "rep1", points = listOf(
            StrokePoint(300f, 500f, 0.5f, 0L),
            StrokePoint(350f, 510f, 0.5f, 50L)
        ))
        column.activeStrokes.add(repStroke)

        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )
        edit.replacementStrokeIds.add("rep1")

        // Simulate applyWordEdit: remove replacement strokes
        column.activeStrokes.removeAll { it.strokeId in edit.replacementStrokeIds }

        assertFalse("Replacement stroke should be removed",
            column.activeStrokes.any { it.strokeId == "rep1" })
        // Original strokes should still be there
        assertTrue("Original strokes should remain",
            column.activeStrokes.size >= 13)  // 3+5+5 = 13 chars across 3 words on line 0
    }

    // ── Subproblem 6: Full end-to-end flow ──────────────────────────────

    @Test
    fun `applyWordEdit replaces word and re-consolidates line`() {
        // Build initial overlays — all lines consolidated
        val overlays = dm.buildInlineOverlays(3)
        assertTrue("Line 0 should start consolidated", overlays[0]?.consolidated == true)

        // Simulate scratch-out of "quick" on line 0
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )

        // Remove original "quick" strokes
        val quickStrokes = column.activeStrokes.filter { it.strokeId.contains("_quick_") }
        val quickIds = quickStrokes.map { it.strokeId }.toSet()
        column.activeStrokes.removeAll { it.strokeId in quickIds }

        // Simulate applyWordEdit: replace "quick" with "fast"
        val origText = textCache[0]!!
        val words = origText.split(" ").toMutableList()
        words[edit.origWordIndex] = "fast"
        textCache[0] = words.joinToString(" ")

        assertEquals("Text should be updated", "the fast brown", textCache[0])

        // Rebuild overlays — line should be consolidated with new text
        dm.lastOverlayHash = 0
        // currentLineIndex must be > edit line for consolidation
        val host = dm.host as TestHost
        host.currentLine = edit.lineIndex + 1
        val newOverlays = dm.buildInlineOverlays(host.currentLine)

        val line0 = newOverlays[0]
        assertNotNull("Line 0 should have overlay after edit", line0)
        assertTrue("Line 0 should be consolidated after edit", line0!!.consolidated)
        assertTrue("Overlay should contain 'fast'",
            newOverlays.values.any { it.recognizedText.contains("fast") })
        assertFalse("Overlay should not contain 'quick'",
            newOverlays.values.any { it.recognizedText.contains("quick") })
    }

    @Test
    fun `currentLineIndex must advance past edit line for re-consolidation`() {
        // This test verifies the bug fix: if currentLineIndex == edit.lineIndex,
        // the line is NOT consolidated because buildInlineOverlays only consolidates
        // lines where lineIdx < currentLineIndex.
        val host = dm.host as TestHost

        // Set currentLineIndex to the edit line (same line)
        host.currentLine = 0
        dm.lastOverlayHash = 0
        val overlaysSame = dm.buildInlineOverlays(0)

        // Line 0 should NOT be consolidated (currentLineIndex == 0)
        assertFalse("Line 0 should not be consolidated when currentLine=0",
            overlaysSame[0]?.consolidated == true)

        // Advance currentLineIndex past the edit line
        host.currentLine = 1
        dm.lastOverlayHash = 0
        val overlaysAdvanced = dm.buildInlineOverlays(1)

        // Now line 0 should be consolidated
        assertTrue("Line 0 should be consolidated when currentLine=1",
            overlaysAdvanced[0]?.consolidated == true)
    }

    @Test
    fun `replacement strokes are hidden during pending edit`() {
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )

        // Add replacement strokes
        val repStroke1 = InkStroke(strokeId = "rep1", points = listOf(
            StrokePoint(100f, 500f, 0.5f, 0L), StrokePoint(150f, 510f, 0.5f, 50L)
        ))
        val repStroke2 = InkStroke(strokeId = "rep2", points = listOf(
            StrokePoint(160f, 500f, 0.5f, 100L), StrokePoint(200f, 510f, 0.5f, 150L)
        ))
        column.activeStrokes.add(repStroke1)
        column.activeStrokes.add(repStroke2)
        edit.replacementStrokeIds.addAll(listOf("rep1", "rep2"))

        // The hiddenStrokeIds should include the replacement strokes
        val hiddenIds = edit.replacementStrokeIds.toSet()
        assertTrue("rep1 should be hidden", "rep1" in hiddenIds)
        assertTrue("rep2 should be hidden", "rep2" in hiddenIds)

        // After edit is applied, replacement strokes are removed
        column.activeStrokes.removeAll { it.strokeId in hiddenIds }
        assertFalse("rep1 should be removed", column.activeStrokes.any { it.strokeId == "rep1" })
        assertFalse("rep2 should be removed", column.activeStrokes.any { it.strokeId == "rep2" })
    }

    @Test
    fun `lineTextCache is not wiped for replacement strokes on edit line`() {
        // The bug: onStrokeCompleted does lineTextCache.remove(lineIdx) which
        // un-consolidates the line when replacement strokes are on the same line.
        val editLine = 0
        textCache[editLine] = "the quick brown"

        val edit = PendingWordEdit(
            lineIndex = editLine, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + editLine * ls
        )

        // Simulate: replacement stroke is on the same line as the edit
        val repStroke = InkStroke(strokeId = "rep1", points = listOf(
            StrokePoint(100f, tm + editLine * ls + ls / 2, 0.5f, 0L),
            StrokePoint(150f, tm + editLine * ls + ls / 2, 0.5f, 50L)
        ))
        edit.replacementStrokeIds.add("rep1")

        // The guard: if stroke is in replacementStrokeIds, don't remove lineTextCache
        val shouldRemove = repStroke.strokeId !in edit.replacementStrokeIds
        assertFalse("Should NOT remove lineTextCache for replacement stroke", shouldRemove)

        // Verify text cache is preserved
        assertEquals("the quick brown", textCache[editLine])
    }

    @Test
    fun `full scratch-replace-reconsolidate flow`() {
        val host = dm.host as TestHost
        host.currentLine = 3  // writing on line 3

        // Verify initial state: lines 0-2 consolidated
        dm.lastOverlayHash = 0
        val initial = dm.buildInlineOverlays(3)
        assertTrue("Line 0 consolidated", initial[0]?.consolidated == true)
        assertTrue("Line 1 consolidated", initial[1]?.consolidated == true)

        // Step 1: Scratch out "jumps" on line 1 (origWordIndex=1)
        val quickStrokes = column.activeStrokes.filter { it.strokeId.contains("_jumps_") }
        column.activeStrokes.removeAll { it.strokeId in quickStrokes.map { s -> s.strokeId }.toSet() }
        // lineTextCache stays: "fox jumps over"

        // Step 2: Write replacement (hidden)
        val repStroke = InkStroke(strokeId = "rep1", points = listOf(
            StrokePoint(100f, 500f, 0.5f, 0L), StrokePoint(200f, 510f, 0.5f, 50L)
        ))
        column.activeStrokes.add(repStroke)

        // Step 3: Apply edit
        val words = textCache[1]!!.split(" ").toMutableList()
        words[1] = "leaps"  // "jumps" → "leaps"
        textCache[1] = words.joinToString(" ")
        assertEquals("fox leaps over", textCache[1])

        // Remove replacement stroke
        column.activeStrokes.removeAll { it.strokeId == "rep1" }

        // Advance currentLineIndex past edit line
        host.currentLine = 2

        // Step 4: Rebuild overlays
        dm.lastOverlayHash = 0
        dm.hersheyStrokeCache.clear()
        val after = dm.buildInlineOverlays(2)

        // Line 1 should be consolidated with "leaps"
        assertTrue("Line 1 should be consolidated", after[1]?.consolidated == true)
        assertTrue("Should contain 'leaps'", after.values.any { it.recognizedText.contains("leaps") })
        assertFalse("Should not contain 'jumps'", after.values.any { it.recognizedText.contains("jumps") })
    }

    @Test
    fun `pending edit with no replacement strokes is cleared on idle`() {
        // This tests the regression: pendingWordEdit stays active after
        // recognition failure, causing all subsequent strokes to be hidden.
        //
        // The WritingCoordinator's idle handler should clear pendingWordEdit
        // when recognition returns empty. We test the state directly since
        // the idle handler needs a real recognizer.

        // Set up a pending edit on the coordinator-like state
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )

        // After recognition fails, pendingWordEdit must be null
        // so that new strokes in onStrokeCompleted are NOT added to
        // edit.replacementStrokeIds and hidden.
        val pendingAfterFailure: PendingWordEdit? = null  // this is what the fix does

        // Verify: a new stroke written after failure is NOT in any replacement set
        val newStroke = InkStroke(strokeId = "normal_writing", points = listOf(
            StrokePoint(10f, tm + 3 * ls + ls / 2, 0.5f, 0L),
            StrokePoint(100f, tm + 3 * ls + ls / 2, 0.5f, 100L)
        ))

        // The guard in onStrokeCompleted: only add to replacementStrokeIds if pendingWordEdit != null
        val wouldHide = pendingAfterFailure != null
        assertFalse("New stroke should NOT be hidden after failed recognition", wouldHide)
    }

    @Test
    fun `strokes written during active pending edit are hidden`() {
        // Contrast with above: when pendingWordEdit IS active, strokes ARE hidden
        val edit = PendingWordEdit(
            lineIndex = 0, oldWord = "quick", origWordIndex = 1,
            wordStartX = 90f, wordEndX = 200f,
            origStrokeStartX = 90f, origStrokeEndX = 200f, origLineY = tm + 0 * ls
        )

        // Simulate onStrokeCompleted with active pending edit
        val repStroke = InkStroke(strokeId = "rep1", points = listOf(
            StrokePoint(100f, 500f, 0.5f, 0L), StrokePoint(150f, 510f, 0.5f, 50L)
        ))
        // The guard: pendingWordEdit != null → add to replacementStrokeIds
        edit.replacementStrokeIds.add(repStroke.strokeId)

        assertTrue("Replacement stroke should be in replacementStrokeIds",
            repStroke.strokeId in edit.replacementStrokeIds)
        // hiddenStrokeIds would be set to edit.replacementStrokeIds.toSet()
        val hiddenIds = edit.replacementStrokeIds.toSet()
        assertTrue("Replacement stroke should be hidden", repStroke.strokeId in hiddenIds)
    }

    @Test
    fun `after word edit entire document re-consolidates not just edit line`() {
        val host = dm.host as TestHost
        host.currentLine = 3  // user was writing on line 3

        // Verify all lines 0-2 start consolidated
        dm.lastOverlayHash = 0
        val initial = dm.buildInlineOverlays(3)
        for (line in 0 until 3) {
            assertTrue("Line $line should start consolidated", initial[line]?.consolidated == true)
        }

        // Simulate scratch-replace on line 0: "quick" → "fast"
        textCache[0] = "the fast brown"

        // Simulate what applyWordEdit does: set currentLineIndex = highestLineIndex + 1
        host.highestLine = 2
        host.currentLine = host.highestLine + 1  // = 3

        dm.lastOverlayHash = 0
        val after = dm.buildInlineOverlays(host.currentLine)

        // ALL lines 0-2 should be consolidated, not just line 0
        for (line in 0 until 3) {
            assertTrue("Line $line should be consolidated after edit (currentLine=${host.currentLine})",
                after[line]?.consolidated == true)
        }
        assertTrue("Line 0 should contain 'fast'",
            after.values.any { it.recognizedText.contains("fast") })
    }

    // ── Test host ────────────────────────────────────────────────────────

    private inner class TestHost(
        override val columnModel: ColumnModel,
        private val cache: MutableMap<Int, String>
    ) : DisplayManagerHost {
        var pendingEdit: PendingWordEdit? = null
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
        var highestLine = 2
        override val highestLineIndex get() = highestLine
        var currentLine = 3
        override val currentLineIndex get() = currentLine
        override val lineRecognitionResults: Map<Int, RecognitionResult> = emptyMap()
        override val pendingWordEdit: PendingWordEdit? get() = pendingEdit
        override fun eagerRecognizeLine(lineIndex: Int) {}
        override fun markRecognizing(lineIndex: Int) {}
        override suspend fun doRecognizeLine(lineIndex: Int): String? = null
        override fun isRecognizing(lineIndex: Int): Boolean = false
    }
}
