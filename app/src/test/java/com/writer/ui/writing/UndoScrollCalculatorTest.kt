package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UndoScrollCalculator] — scroll-to-changed-strokes logic
 * after undo/redo.
 */
class UndoScrollCalculatorTest {

    private fun stroke(id: String, y: Float, height: Float = 20f) = InkStroke(
        strokeId = id,
        points = listOf(
            StrokePoint(50f, y, 0.5f, 0L),
            StrokePoint(100f, y + height, 0.5f, 1L)
        )
    )

    @Test
    fun `no changed strokes returns no scroll`() {
        val strokes = listOf(stroke("a", 100f))
        val result = UndoScrollCalculator.computeScroll(
            oldStrokeIds = setOf("a"),
            newStrokes = strokes,
            currentScrollY = 0f,
            viewportHeight = 500f
        )
        assertFalse(result.shouldScroll)
    }

    @Test
    fun `changed strokes within viewport returns no scroll`() {
        val strokes = listOf(stroke("a", 100f), stroke("b", 200f))
        val result = UndoScrollCalculator.computeScroll(
            oldStrokeIds = setOf("a"),  // "b" is new
            newStrokes = strokes,
            currentScrollY = 0f,
            viewportHeight = 500f
        )
        assertFalse(result.shouldScroll)
    }

    @Test
    fun `changed strokes above viewport scrolls to center`() {
        val strokes = listOf(stroke("a", 50f, 20f), stroke("b", 800f))
        val result = UndoScrollCalculator.computeScroll(
            oldStrokeIds = setOf("b"),  // "a" is new, at y=50-70
            newStrokes = strokes,
            currentScrollY = 500f,      // viewport shows 500-1000
            viewportHeight = 500f
        )
        assertTrue(result.shouldScroll)
        // Center of changed region: (50+70)/2 = 60, scroll = 60 - 250 = -190 → clamped to 0
        assertEquals(0f, result.newScrollOffsetY)
    }

    @Test
    fun `changed strokes below viewport scrolls to center`() {
        val strokes = listOf(stroke("a", 100f), stroke("b", 1200f, 30f))
        val result = UndoScrollCalculator.computeScroll(
            oldStrokeIds = setOf("a"),  // "b" is new, at y=1200-1230
            newStrokes = strokes,
            currentScrollY = 0f,        // viewport shows 0-500
            viewportHeight = 500f
        )
        assertTrue(result.shouldScroll)
        // Center of changed region: (1200+1230)/2 = 1215, scroll = 1215 - 250 = 965
        assertEquals(965f, result.newScrollOffsetY)
    }

    @Test
    fun `scroll offset clamped to zero`() {
        val strokes = listOf(stroke("new", 10f, 5f))
        val result = UndoScrollCalculator.computeScroll(
            oldStrokeIds = emptySet(),
            newStrokes = strokes,
            currentScrollY = 800f,
            viewportHeight = 500f
        )
        assertTrue(result.shouldScroll)
        // Center: (10+15)/2 = 12.5, scroll = 12.5 - 250 = -237.5 → clamped to 0
        assertEquals(0f, result.newScrollOffsetY)
    }

    @Test
    fun `deleted strokes only - no new strokes to scroll to`() {
        // Old had "a" and "b", new only has "a" — "b" was deleted
        val strokes = listOf(stroke("a", 100f))
        val result = UndoScrollCalculator.computeScroll(
            oldStrokeIds = setOf("a", "b"),
            newStrokes = strokes,
            currentScrollY = 0f,
            viewportHeight = 500f
        )
        // "b" is in changedIds but not in newStrokes, so changedStrokes is empty
        assertFalse(result.shouldScroll)
    }
}
