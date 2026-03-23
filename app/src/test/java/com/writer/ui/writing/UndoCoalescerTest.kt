package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [UndoCoalescer]: snapshot coalescing so that rapid writing strokes
 * group into a single undo step, while destructive/structural actions always
 * create their own snapshot.
 */
class UndoCoalescerTest {

    private lateinit var undoManager: UndoManager
    private lateinit var coalescer: UndoCoalescer

    private fun makeSnapshot(strokeCount: Int = 0) = UndoManager.Snapshot(
        strokes = (0 until strokeCount).map {
            InkStroke(
                points = listOf(StrokePoint(0f, 0f, 0.5f, 0L)),
                strokeType = StrokeType.FREEHAND
            )
        },
        scrollOffsetY = 0f,
        lineTextCache = emptyMap()
    )

    @Before
    fun setUp() {
        undoManager = UndoManager()
        coalescer = UndoCoalescer(undoManager, coalesceWindowMs = 2000L)
    }

    // --- STROKE_ADDED coalescing ---

    @Test
    fun `first stroke always creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        assertTrue(undoManager.canUndo())
    }

    @Test
    fun `rapid strokes on same line coalesce into one snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        // Simulate rapid follow-up (within window)
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(1))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(2))

        // Only 1 snapshot should have been saved (the first one)
        val s1 = undoManager.undo(makeSnapshot(3))
        assertTrue("Should have at least one undo", s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertEquals("Should have only one undo entry", null, s2)
    }

    @Test
    fun `strokes on adjacent lines coalesce`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 3, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 4, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertEquals(null, s2)
    }

    @Test
    fun `strokes on distant lines create separate snapshots`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 5, makeSnapshot(1))

        // Should have 2 snapshots
        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("Distant lines should create separate snapshot", s2 != null)
    }

    @Test
    fun `time gap creates new snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))

        // Simulate time passing beyond the coalesce window
        coalescer.advanceTimeForTesting(2100L)

        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("Time gap should create separate snapshot", s2 != null)
    }

    // --- Non-coalescing action types ---

    @Test
    fun `scratch out always creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.SCRATCH_OUT, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("SCRATCH_OUT should always create snapshot", s2 != null)
    }

    @Test
    fun `stroke replaced always creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_REPLACED, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("STROKE_REPLACED should always create snapshot", s2 != null)
    }

    @Test
    fun `gesture consumed always creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.GESTURE_CONSUMED, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("GESTURE_CONSUMED should always create snapshot", s2 != null)
    }

    @Test
    fun `diagram created always creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.DIAGRAM_CREATED, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("DIAGRAM_CREATED should always create snapshot", s2 != null)
    }

    @Test
    fun `zone expanded always creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.ZONE_EXPANDED, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("ZONE_EXPANDED should always create snapshot", s2 != null)
    }

    // --- Action type transitions ---

    @Test
    fun `switching from non-stroke to stroke creates snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.SCRATCH_OUT, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(1))

        val s1 = undoManager.undo(makeSnapshot(2))
        assertTrue(s1 != null)
        val s2 = undoManager.undo(makeSnapshot(0))
        assertTrue("Action type change should create snapshot", s2 != null)
    }

    // --- Shape snap two-phase interaction ---

    @Test
    fun `shape snap after coalesced strokes produces correct undo sequence`() {
        // Write 3 rapid strokes (coalesce into one snapshot)
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(1))
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(2))

        // Shape snap (always creates snapshot)
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_REPLACED, lineIndex = 0, makeSnapshot(3))

        // Undo 1: undo the snap → state with all 3 strokes (raw version of stroke 3)
        val s1 = undoManager.undo(makeSnapshot(4))
        assertTrue(s1 != null)
        assertEquals(3, s1!!.strokes.size) // state before snap had 3 strokes

        // Undo 2: undo the coalesced writing burst → empty
        val s2 = undoManager.undo(makeSnapshot(3))
        assertTrue(s2 != null)
        assertEquals(0, s2!!.strokes.size) // empty state before writing

        // No more undos
        val s3 = undoManager.undo(makeSnapshot(0))
        assertEquals(null, s3)
    }

    // --- Edge cases ---

    @Test
    fun `consecutive non-coalescing actions each create snapshot`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.SCRATCH_OUT, lineIndex = 0, makeSnapshot(3))
        coalescer.maybeSave(UndoCoalescer.ActionType.SCRATCH_OUT, lineIndex = 0, makeSnapshot(2))
        coalescer.maybeSave(UndoCoalescer.ActionType.SCRATCH_OUT, lineIndex = 0, makeSnapshot(1))

        // Should have 3 snapshots
        assertTrue(undoManager.undo(makeSnapshot(0)) != null)
        assertTrue(undoManager.undo(makeSnapshot(0)) != null)
        assertTrue(undoManager.undo(makeSnapshot(0)) != null)
        assertEquals(null, undoManager.undo(makeSnapshot(0)))
    }

    @Test
    fun `reset clears coalescer state`() {
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(0))
        coalescer.reset()

        // Next stroke should create a new snapshot regardless of timing
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex = 0, makeSnapshot(1))
        assertTrue(undoManager.canUndo())
    }
}
