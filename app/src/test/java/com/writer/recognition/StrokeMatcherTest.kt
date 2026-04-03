package com.writer.recognition

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minX
import com.writer.model.maxX
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [StrokeMatcher.buildWordStrokeMapping] which matches
 * recognizer bounding boxes against input strokes.
 */
class StrokeMatcherTest {

    private fun stroke(id: String, startX: Float, endX: Float): InkStroke {
        return InkStroke(
            strokeId = id,
            points = listOf(
                StrokePoint(startX, 50f, 0.5f, 0L),
                StrokePoint(endX, 50f, 0.5f, 100L)
            )
        )
    }

    private fun wc(word: String, idx: Int, x: Float, width: Float) =
        WordConfidence(word, 1.0f, idx, WordBoundingBox(x, 40f, width, 20f))

    // ── Basic matching ──────────────────────────────────────────────────

    @Test
    fun `two words with clear separation`() {
        val strokes = listOf(
            stroke("s0", 10f, 30f), stroke("s1", 35f, 55f),   // "hello"
            stroke("s2", 100f, 120f), stroke("s3", 125f, 145f) // "world"
        )
        val words = listOf(
            wc("hello", 0, 10f, 50f),
            wc("world", 1, 100f, 50f)
        )

        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)

        assertEquals(setOf("s0", "s1"), mapping[0])
        assertEquals(setOf("s2", "s3"), mapping[1])
    }

    @Test
    fun `three words assigns each stroke to correct word`() {
        val strokes = listOf(
            stroke("a0", 10f, 25f), stroke("a1", 28f, 43f), stroke("a2", 46f, 70f),
            stroke("b0", 100f, 115f), stroke("b1", 118f, 133f), stroke("b2", 136f, 160f),
            stroke("c0", 190f, 210f), stroke("c1", 215f, 240f)
        )
        val words = listOf(
            wc("See", 0, 10f, 65f),
            wc("the", 1, 100f, 65f),
            wc("dog", 2, 190f, 55f)
        )

        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)

        assertEquals(setOf("a0", "a1", "a2"), mapping[0])
        assertEquals(setOf("b0", "b1", "b2"), mapping[1])
        assertEquals(setOf("c0", "c1"), mapping[2])
    }

    // ── Unmatched strokes go to nearest word ────────────────────────────

    @Test
    fun `dot on i outside bounding box assigned to nearest word`() {
        val strokes = listOf(
            stroke("s0", 10f, 30f), stroke("s1", 35f, 55f),
            stroke("dot", 42f, 44f),  // dot slightly above, within X range but possibly outside tight box
            stroke("s2", 100f, 120f)
        )
        val words = listOf(
            wc("hi", 0, 10f, 50f),
            wc("go", 1, 100f, 25f)
        )

        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)

        assertTrue("dot should be assigned to 'hi' (nearest)", "dot" in mapping[0]!!)
        assertEquals(setOf("s2"), mapping[1])
    }

    @Test
    fun `stroke between two words assigned to nearest`() {
        val strokes = listOf(
            stroke("s0", 10f, 30f),
            stroke("mid", 70f, 72f),  // between the two words
            stroke("s1", 100f, 120f)
        )
        val words = listOf(
            wc("a", 0, 10f, 25f),
            wc("b", 1, 100f, 25f)
        )

        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)

        // mid at center X=71 is closer to word "a" (center 22.5) than word "b" (center 112.5)
        // Actually 71-22.5=48.5 vs 112.5-71=41.5, so closer to "b"
        assertTrue("mid should be assigned to 'b' (nearest)", "mid" in mapping[1]!!)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `empty strokes returns empty mapping`() {
        val words = listOf(wc("hello", 0, 10f, 50f))
        val mapping = StrokeMatcher.buildWordStrokeMapping(words, emptyList())
        assertTrue(mapping.isEmpty())
    }

    @Test
    fun `no bounding boxes returns empty mapping`() {
        val strokes = listOf(stroke("s0", 10f, 30f))
        val words = listOf(WordConfidence("hello", 1.0f, 0, null))
        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)
        assertTrue(mapping.isEmpty())
    }

    @Test
    fun `single word gets all strokes`() {
        val strokes = listOf(
            stroke("s0", 10f, 30f), stroke("s1", 35f, 55f), stroke("s2", 60f, 80f)
        )
        val words = listOf(wc("hello", 0, 10f, 75f))

        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)

        assertEquals(setOf("s0", "s1", "s2"), mapping[0])
    }

    @Test
    fun `tolerance allows slight overshoot`() {
        // Stroke center at 62, word box ends at 60. Tolerance = max(50*0.1, 4) = 5.
        // 62 <= 60+5 = 65, so it should match.
        val strokes = listOf(stroke("s0", 58f, 66f))  // center = 62
        val words = listOf(wc("hi", 0, 10f, 50f))      // box: 10-60

        val mapping = StrokeMatcher.buildWordStrokeMapping(words, strokes)

        assertEquals(setOf("s0"), mapping[0])
    }
}
