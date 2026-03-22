package com.writer.ui.writing

import com.writer.model.StrokePoint
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StrokeEventLog] ring buffer and event recording.
 */
class StrokeEventLogTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun makePoints(n: Int = 10): List<StrokePoint> =
        (0 until n).map { i -> StrokePoint(i * 10f, 100f, 0.5f, i * 10L) }

    @Test
    fun `recordStroke returns incrementing indices`() {
        val log = StrokeEventLog(maxStrokes = 10)
        assertEquals(0, log.recordStroke(makePoints()))
        assertEquals(1, log.recordStroke(makePoints()))
        assertEquals(2, log.recordStroke(makePoints()))
    }

    @Test
    fun `strokeCount tracks buffer size`() {
        val log = StrokeEventLog(maxStrokes = 10)
        assertEquals(0, log.strokeCount)
        log.recordStroke(makePoints())
        assertEquals(1, log.strokeCount)
        log.recordStroke(makePoints())
        assertEquals(2, log.strokeCount)
    }

    @Test
    fun `ring buffer evicts oldest when full`() {
        val log = StrokeEventLog(maxStrokes = 3)
        log.recordStroke(makePoints()) // index 0
        log.recordStroke(makePoints()) // index 1
        log.recordStroke(makePoints()) // index 2
        assertEquals(3, log.strokeCount)

        log.recordStroke(makePoints()) // index 3, evicts index 0
        assertEquals(3, log.strokeCount)

        val snapshot = log.snapshot()
        assertEquals(3, snapshot.strokes.size)
        assertEquals(1, snapshot.strokes[0].index) // oldest is now index 1
        assertEquals(3, snapshot.strokes[2].index) // newest is index 3
    }

    @Test
    fun `events are recorded with correct stroke index`() {
        val log = StrokeEventLog()
        val idx = log.recordStroke(makePoints())
        log.recordEvent(idx, StrokeEventLog.EventType.ADDED, "line=3")

        val snapshot = log.snapshot()
        assertEquals(1, snapshot.events.size)
        assertEquals(idx, snapshot.events[0].strokeIndex)
        assertEquals(StrokeEventLog.EventType.ADDED, snapshot.events[0].type)
        assertEquals("line=3", snapshot.events[0].detail)
    }

    @Test
    fun `events for evicted strokes are removed`() {
        val log = StrokeEventLog(maxStrokes = 2)
        val idx0 = log.recordStroke(makePoints())
        log.recordEvent(idx0, StrokeEventLog.EventType.ADDED)
        val idx1 = log.recordStroke(makePoints())
        log.recordEvent(idx1, StrokeEventLog.EventType.ADDED)

        // Evict idx0
        log.recordStroke(makePoints())

        val snapshot = log.snapshot()
        // Event for idx0 should be gone
        assertTrue(snapshot.events.none { it.strokeIndex == idx0 })
    }

    @Test
    fun `multiple events per stroke`() {
        val log = StrokeEventLog()
        val idx = log.recordStroke(makePoints())
        log.recordEvent(idx, StrokeEventLog.EventType.ADDED)
        log.recordEvent(idx, StrokeEventLog.EventType.SHAPE_SNAPPED, "RECTANGLE")
        log.recordEvent(idx, StrokeEventLog.EventType.REPLACED, "RECTANGLE")

        val snapshot = log.snapshot()
        assertEquals(3, snapshot.events.size)
    }

    @Test
    fun `snapshot is a copy not a reference`() {
        val log = StrokeEventLog()
        log.recordStroke(makePoints())
        val snap1 = log.snapshot()

        log.recordStroke(makePoints())
        val snap2 = log.snapshot()

        assertEquals(1, snap1.strokes.size)
        assertEquals(2, snap2.strokes.size)
    }

    @Test
    fun `clear resets everything`() {
        val log = StrokeEventLog()
        log.recordStroke(makePoints())
        log.recordEvent(0, StrokeEventLog.EventType.ADDED)
        log.clear()

        assertEquals(0, log.strokeCount)
        assertEquals(0, log.eventCount)
        val snapshot = log.snapshot()
        assertTrue(snapshot.strokes.isEmpty())
        assertTrue(snapshot.events.isEmpty())
    }

    @Test
    fun `event cap prevents unbounded growth`() {
        val log = StrokeEventLog(maxStrokes = 5)
        val idx = log.recordStroke(makePoints())
        // Add 20 events (cap = 5 * 3 = 15)
        for (i in 0 until 20) {
            log.recordEvent(idx, StrokeEventLog.EventType.RECOGNIZED, "text $i")
        }
        assertTrue("Events should be capped", log.eventCount <= 15)
    }

    @Test
    fun `downsampling reduces point count`() {
        // Create a stroke with many redundant points (dwell + straight line)
        val points = mutableListOf<StrokePoint>()
        // Dwell: 20 points at same position
        for (i in 0 until 20) points.add(StrokePoint(100f, 100f, 0.5f, i.toLong()))
        // Straight line: 50 points
        for (i in 0 until 50) points.add(StrokePoint(100f + i * 2f, 100f, 0.5f, (20 + i).toLong()))

        val log = StrokeEventLog()
        log.recordStroke(points)

        val snapshot = log.snapshot()
        assertTrue(
            "Downsampled points (${snapshot.strokes[0].points.size}) should be less than raw (${points.size})",
            snapshot.strokes[0].points.size < points.size
        )
    }
}
