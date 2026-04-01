package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.recognition.LineSegmenter
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
 * Tests for findStrokesForWord which identifies original handwriting strokes
 * belonging to a specific word using N-1 largest gaps.
 *
 * Tests cover:
 * - Well-separated print handwriting (clear gaps between words)
 * - Cursive handwriting (uniform gaps, fewer strokes per word)
 * - Single-stroke cursive words
 * - Edge cases: first word, last word, single word line
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class FindStrokesForWordTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var coordinator: WritingCoordinator

    /** Create a stroke at a given X range on a line. */
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

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)

        val canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        val documentModel = DocumentModel()
        val lineSegmenter = LineSegmenter()
        val recognizer = object : com.writer.recognition.TextRecognizer {
            override suspend fun initialize(languageTag: String) {}
            override suspend fun recognizeLine(line: com.writer.model.InkLine, preContext: String) = ""
            override fun close() {}
        }
        coordinator = WritingCoordinator(
            documentModel, documentModel.main, recognizer, canvas,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onStatusUpdate = {}
        )
    }

    // ── Well-separated print: clear gaps between words ───────────────────

    @Test
    fun `print handwriting 3 words finds correct strokes for each word`() {
        // "See the panda" — each letter is a separate stroke with clear word gaps
        // "See" at X=10-70 (3 strokes), gap, "the" at X=100-160 (3 strokes), gap, "panda" at X=190-300 (5 strokes)
        val strokes = listOf(
            stroke(0, 10f, 25f, "S"), stroke(0, 28f, 43f, "e1"), stroke(0, 46f, 70f, "e2"),
            // gap of 30
            stroke(0, 100f, 115f, "t"), stroke(0, 118f, 133f, "h"), stroke(0, 136f, 160f, "e3"),
            // gap of 30
            stroke(0, 190f, 205f, "p"), stroke(0, 208f, 223f, "a"), stroke(0, 226f, 241f, "n"),
            stroke(0, 244f, 259f, "d"), stroke(0, 262f, 300f, "a2")
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "See the panda"

        val word0 = coordinator.findStrokesForWord(0, 0)
        val word1 = coordinator.findStrokesForWord(0, 1)
        val word2 = coordinator.findStrokesForWord(0, 2)

        assertEquals("'See' should have 3 strokes", 3, word0.size)
        assertEquals("'the' should have 3 strokes", 3, word1.size)
        assertEquals("'panda' should have 5 strokes", 5, word2.size)

        // Verify correct X ranges
        assertTrue("'See' strokes should be at X<80", word0.all { it.maxX < 80f })
        assertTrue("'the' strokes should be at X=100-160", word1.all { it.minX >= 95f && it.maxX <= 165f })
        assertTrue("'panda' strokes should be at X>185", word2.all { it.minX > 185f })
    }

    @Test
    fun `scratching first word returns only first word strokes`() {
        val strokes = listOf(
            stroke(0, 10f, 50f, "w1_a"), stroke(0, 55f, 90f, "w1_b"),
            // gap
            stroke(0, 130f, 170f, "w2_a"), stroke(0, 175f, 210f, "w2_b"),
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "Hello World"

        val firstWord = coordinator.findStrokesForWord(0, 0)
        assertEquals(2, firstWord.size)
        assertTrue(firstWord.all { it.strokeId.startsWith("w1") })
    }

    @Test
    fun `scratching last word returns only last word strokes`() {
        val strokes = listOf(
            stroke(0, 10f, 50f, "w1_a"), stroke(0, 55f, 90f, "w1_b"),
            // gap
            stroke(0, 130f, 170f, "w2_a"), stroke(0, 175f, 210f, "w2_b"),
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "Hello World"

        val lastWord = coordinator.findStrokesForWord(0, 1)
        assertEquals(2, lastWord.size)
        assertTrue(lastWord.all { it.strokeId.startsWith("w2") })
    }

    // ── Cursive: each word is 1-2 strokes, uniform gaps ──────────────────

    @Test
    fun `cursive handwriting single stroke per word`() {
        // Each cursive word is ONE continuous stroke
        // "make a promise" — gaps between words are similar to stroke width
        val strokes = listOf(
            stroke(0, 10f, 120f, "make"),    // wide cursive stroke
            // gap of 40
            stroke(0, 160f, 190f, "a"),       // short word
            // gap of 40
            stroke(0, 230f, 400f, "promise"), // wide cursive stroke
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "make a promise"

        val word0 = coordinator.findStrokesForWord(0, 0)
        val word1 = coordinator.findStrokesForWord(0, 1)
        val word2 = coordinator.findStrokesForWord(0, 2)

        assertEquals("'make' should be 1 stroke", 1, word0.size)
        assertEquals("'a' should be 1 stroke", 1, word1.size)
        assertEquals("'promise' should be 1 stroke", 1, word2.size)
        assertEquals("make", word0[0].strokeId)
        assertEquals("a", word1[0].strokeId)
        assertEquals("promise", word2[0].strokeId)
    }

    @Test
    fun `cursive with crossbars and dots as extra strokes`() {
        // "the" in cursive: main body + crossbar for 't'
        // Crossbar stroke is WITHIN the word's X range
        val strokes = listOf(
            stroke(0, 10f, 80f, "the_body"), stroke(0, 15f, 35f, "the_cross"),
            // gap
            stroke(0, 120f, 250f, "quick_body"), stroke(0, 230f, 240f, "quick_dot"),
            // gap
            stroke(0, 290f, 400f, "fox_body"),
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "the quick fox"

        val word0 = coordinator.findStrokesForWord(0, 0)
        val word1 = coordinator.findStrokesForWord(0, 1)
        val word2 = coordinator.findStrokesForWord(0, 2)

        assertEquals("'the' should have 2 strokes (body + crossbar)", 2, word0.size)
        assertEquals("'quick' should have 2 strokes (body + dot)", 2, word1.size)
        assertEquals("'fox' should have 1 stroke", 1, word2.size)
    }

    @Test
    fun `uniform gaps still splits correctly with N-1 largest`() {
        // All gaps are nearly identical (50px ± 5px) — but the 2 largest still
        // correctly mark word boundaries when there are 3 words
        val strokes = listOf(
            stroke(0, 10f, 100f, "w1"),
            // gap = 48
            stroke(0, 148f, 250f, "w2"),
            // gap = 52  (slightly larger — this is a word boundary)
            stroke(0, 302f, 400f, "w3"),
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "one two three"

        val word0 = coordinator.findStrokesForWord(0, 0)
        val word1 = coordinator.findStrokesForWord(0, 1)
        val word2 = coordinator.findStrokesForWord(0, 2)

        assertEquals(1, word0.size)
        assertEquals(1, word1.size)
        assertEquals(1, word2.size)
        assertEquals("w1", word0[0].strokeId)
        assertEquals("w2", word1[0].strokeId)
        assertEquals("w3", word2[0].strokeId)
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun `single word line returns all strokes`() {
        val strokes = listOf(
            stroke(0, 10f, 50f, "s1"), stroke(0, 55f, 90f, "s2"),
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "Hello"

        val result = coordinator.findStrokesForWord(0, 0)
        assertEquals("Single word should return all strokes", 2, result.size)
    }

    @Test
    fun `word index out of range returns empty`() {
        val strokes = listOf(stroke(0, 10f, 50f, "s1"))
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "Hello"

        val result = coordinator.findStrokesForWord(0, 5)
        assertTrue("Out of range index should return empty, got ${result.size} strokes", result.isEmpty())
    }

    @Test
    fun `empty line returns empty`() {
        coordinator.lineTextCache[0] = "Hello"
        // No strokes on line 0
        val result = coordinator.findStrokesForWord(0, 0)
        assertTrue("No strokes should return empty", result.isEmpty())
    }

    @Test
    fun `does not include strokes from other lines`() {
        val strokes = listOf(
            stroke(0, 10f, 100f, "line0_w1"),
            stroke(0, 140f, 250f, "line0_w2"),
            stroke(1, 10f, 100f, "line1_w1"),  // different line!
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "Hello World"
        coordinator.lineTextCache[1] = "Goodbye"

        val word0 = coordinator.findStrokesForWord(0, 0)
        val word1 = coordinator.findStrokesForWord(0, 1)

        assertEquals(1, word0.size)
        assertEquals(1, word1.size)
        assertFalse("Should not include line 1 strokes",
            word0.any { it.strokeId.startsWith("line1") } ||
            word1.any { it.strokeId.startsWith("line1") })
    }

    // ── Verify scratched strokes don't include neighbors ─────────────────

    @Test
    fun `scratching middle word preserves surrounding words`() {
        val strokes = listOf(
            stroke(0, 10f, 50f, "the_1"), stroke(0, 53f, 80f, "the_2"),
            // gap
            stroke(0, 120f, 160f, "big_1"), stroke(0, 163f, 200f, "big_2"),
            // gap
            stroke(0, 240f, 280f, "dog_1"), stroke(0, 283f, 320f, "dog_2"),
        )
        coordinator.columnModel.activeStrokes.addAll(strokes)
        coordinator.lineTextCache[0] = "the big dog"

        // Scratch out "big" (word index 1)
        val bigStrokes = coordinator.findStrokesForWord(0, 1)
        val bigIds = bigStrokes.map { it.strokeId }.toSet()

        assertEquals("'big' should have 2 strokes", 2, bigStrokes.size)
        assertTrue("Should include big_1", "big_1" in bigIds)
        assertTrue("Should include big_2", "big_2" in bigIds)

        // Remove "big" strokes
        coordinator.columnModel.activeStrokes.removeAll { it.strokeId in bigIds }

        // Verify surrounding words are untouched
        val remaining = coordinator.columnModel.activeStrokes
        assertTrue("'the' strokes should remain", remaining.any { it.strokeId == "the_1" })
        assertTrue("'the' strokes should remain", remaining.any { it.strokeId == "the_2" })
        assertTrue("'dog' strokes should remain", remaining.any { it.strokeId == "dog_1" })
        assertTrue("'dog' strokes should remain", remaining.any { it.strokeId == "dog_2" })
        assertEquals("Should have 4 remaining strokes", 4, remaining.size)
    }
}
