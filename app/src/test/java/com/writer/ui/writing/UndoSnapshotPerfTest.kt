package com.writer.ui.writing

import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Layer 1 performance tests: verify snapshot operations stay within budget
 * on documents of increasing size. No device needed — runs as unit tests.
 */
class UndoSnapshotPerfTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun makeStroke(lineIndex: Int): InkStroke {
        val y = lineIndex * 80f + 100f
        val points = (0 until 20).map { i ->
            StrokePoint(i * 5f, y + (i % 3) * 2f, 0.5f, i.toLong())
        }
        return InkStroke(points = points, strokeType = StrokeType.FREEHAND)
    }

    private fun buildDocument(strokeCount: Int): DocumentModel {
        val doc = DocumentModel()
        for (i in 0 until strokeCount) {
            doc.activeStrokes.add(makeStroke(i / 5))
        }
        return doc
    }

    @Test
    fun `snapshot save under 10ms for 100-stroke document`() {
        val doc = buildDocument(100)
        val undoManager = UndoManager()
        val coalescer = UndoCoalescer(undoManager)

        // Warm up
        val warmup = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        undoManager.saveSnapshot(warmup)
        undoManager.clear()

        // Measure
        val t0 = System.nanoTime()
        val snapshot = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, 0, snapshot)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(
            "Snapshot save took ${elapsedMs}ms, budget is 10ms for 100 strokes",
            elapsedMs < 10.0
        )
    }

    @Test
    fun `snapshot save under 30ms for 500-stroke document`() {
        val doc = buildDocument(500)
        val undoManager = UndoManager()
        val coalescer = UndoCoalescer(undoManager)

        val t0 = System.nanoTime()
        val snapshot = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, 0, snapshot)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(
            "Snapshot save took ${elapsedMs}ms, budget is 30ms for 500 strokes",
            elapsedMs < 30.0
        )
    }

    @Test
    fun `snapshot save under 60ms for 1000-stroke document`() {
        val doc = buildDocument(1000)
        val undoManager = UndoManager()
        val coalescer = UndoCoalescer(undoManager)

        val t0 = System.nanoTime()
        val snapshot = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, 0, snapshot)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(
            "Snapshot save took ${elapsedMs}ms, budget is 60ms for 1000 strokes",
            elapsedMs < 60.0
        )
    }

    @Test
    fun `undo apply under 30ms for 500-stroke document`() {
        val doc = buildDocument(500)
        val undoManager = UndoManager()

        // Save a snapshot, then add a stroke
        val snapshot = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        undoManager.saveSnapshot(snapshot)
        doc.activeStrokes.add(makeStroke(100))

        // Measure undo (pop from stack + create current snapshot)
        val current = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        val t0 = System.nanoTime()
        val restored = undoManager.undo(current)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(restored != null)
        assertTrue(
            "Undo took ${elapsedMs}ms, budget is 30ms for 500 strokes",
            elapsedMs < 30.0
        )
    }

    @Test
    fun `coalescing skips snapshot for rapid strokes`() {
        val doc = buildDocument(500)
        val undoManager = UndoManager()
        val coalescer = UndoCoalescer(undoManager)

        // First stroke creates snapshot
        val snap1 = UndoManager.Snapshot(
            strokes = doc.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap()
        )
        coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, 0, snap1)
        assertTrue(undoManager.canUndo())

        // Rapid follow-up strokes should be near-zero cost (no snapshot copy)
        val t0 = System.nanoTime()
        for (i in 1..10) {
            val snap = UndoManager.Snapshot(
                strokes = doc.activeStrokes.toList(),
                scrollOffsetY = 0f,
                lineTextCache = emptyMap()
            )
            coalescer.maybeSave(UndoCoalescer.ActionType.STROKE_ADDED, 0, snap)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        // Note: the snapshot object is still created by the caller (that's the
        // real cost), but the coalescer avoids pushing it to the undo stack.
        // This test verifies the coalescer path itself is fast.
        assertTrue(
            "10 coalesced saves took ${elapsedMs}ms, should be < 5ms",
            elapsedMs < 5.0
        )
    }
}
