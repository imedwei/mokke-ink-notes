package com.writer.view

import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ArrowDwellDetection].
 *
 * Uses a fixed dwell radius of 15 px and dwell duration of 300 ms.
 */
class ArrowDwellDetectionTest {

    companion object {
        private const val RADIUS_PX = 15f
        private const val DWELL_MS = 300L
    }

    // ── hasDwellAtEnd ─────────────────────────────────────────────────────────

    @Test fun hasDwellAtEnd_clusteringNearEndpointForSufficientTime_returnsTrue() {
        // Last 4 points cluster within 15 px of (100, 100) for 300 ms total
        val pts = listOf(
            StrokePoint(0f, 0f, 1f, 0L),
            StrokePoint(50f, 50f, 1f, 100L),
            StrokePoint(98f, 100f, 1f, 500L),
            StrokePoint(99f, 101f, 1f, 600L),
            StrokePoint(100f, 100f, 1f, 700L),
            StrokePoint(101f, 99f, 1f, 800L),
        )
        val result = ArrowDwellDetection.hasDwellAtEnd(pts, 100f, 100f, RADIUS_PX, DWELL_MS)
        assertTrue("Should detect dwell when clustering >= 300ms near endpoint", result)
    }

    @Test fun hasDwellAtEnd_clusteringForLessThanDwellMs_returnsFalse() {
        // Last points cluster near (100, 100) but only for 200 ms (< 300 ms)
        val pts = listOf(
            StrokePoint(0f, 0f, 1f, 0L),
            StrokePoint(50f, 50f, 1f, 100L),
            StrokePoint(98f, 100f, 1f, 500L),
            StrokePoint(100f, 100f, 1f, 600L),
            StrokePoint(101f, 99f, 1f, 700L),  // only 200ms span in radius
            StrokePoint(100f, 100f, 1f, 700L),
        )
        // Start is at 500L, end is at 700L = 200ms, below 300ms threshold
        val result = ArrowDwellDetection.hasDwellAtEnd(pts, 100f, 100f, RADIUS_PX, DWELL_MS)
        assertFalse("Should NOT detect dwell when clustering < 300ms", result)
    }

    @Test fun hasDwellAtEnd_lastPointsFarFromEndpoint_returnsFalse() {
        // Points end far from (100, 100)
        val pts = listOf(
            StrokePoint(0f, 0f, 1f, 0L),
            StrokePoint(100f, 100f, 1f, 100L),
            StrokePoint(200f, 200f, 1f, 500L),
            StrokePoint(250f, 250f, 1f, 800L),
        )
        val result = ArrowDwellDetection.hasDwellAtEnd(pts, 100f, 100f, RADIUS_PX, DWELL_MS)
        assertFalse("Should NOT detect dwell when last points are far from endpoint", result)
    }

    // ── hasDwellAtStart ───────────────────────────────────────────────────────

    @Test fun hasDwellAtStart_firstPointsClusterNearStartForSufficientTime_returnsTrue() {
        // First points stay near (50, 50) for 400 ms before moving away
        val pts = listOf(
            StrokePoint(50f, 50f, 1f, 0L),
            StrokePoint(52f, 49f, 1f, 100L),
            StrokePoint(51f, 51f, 1f, 200L),
            StrokePoint(50f, 50f, 1f, 400L),  // 400 ms in radius
            StrokePoint(100f, 100f, 1f, 500L), // moves away
        )
        val result = ArrowDwellDetection.hasDwellAtStart(pts, RADIUS_PX, DWELL_MS)
        assertTrue("Should detect dwell at start when pen pauses >= 300ms", result)
    }

    @Test fun hasDwellAtStart_penMovesAwayQuickly_returnsFalse() {
        // Pen moves away from start right away — only 50ms within radius
        val pts = listOf(
            StrokePoint(50f, 50f, 1f, 0L),
            StrokePoint(51f, 50f, 1f, 50L),   // still near
            StrokePoint(100f, 100f, 1f, 200L), // moves away at 50ms
            StrokePoint(200f, 200f, 1f, 400L),
        )
        val result = ArrowDwellDetection.hasDwellAtStart(pts, RADIUS_PX, DWELL_MS)
        assertFalse("Should NOT detect dwell when pen moves away quickly (only 50ms in radius)", result)
    }

    // ── classifyArrow ─────────────────────────────────────────────────────────

    @Test fun classifyArrow_tipDwellOnly_returnsArrowHead() {
        val result = ArrowDwellDetection.classifyArrow(tipDwell = true, tailDwell = false)
        assertEquals(StrokeType.ARROW_HEAD, result)
    }

    @Test fun classifyArrow_tailDwellOnly_returnsArrowTail() {
        val result = ArrowDwellDetection.classifyArrow(tipDwell = false, tailDwell = true)
        assertEquals(StrokeType.ARROW_TAIL, result)
    }

    @Test fun classifyArrow_bothDwells_returnsArrowBoth() {
        val result = ArrowDwellDetection.classifyArrow(tipDwell = true, tailDwell = true)
        assertEquals(StrokeType.ARROW_BOTH, result)
    }

    @Test fun classifyArrow_noDwells_returnsLine() {
        val result = ArrowDwellDetection.classifyArrow(tipDwell = false, tailDwell = false)
        assertEquals(StrokeType.LINE, result)
    }
}
