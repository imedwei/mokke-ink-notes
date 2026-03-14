package com.writer.recognition

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeDownsamplerTest {

    private fun pt(x: Float, y: Float, pressure: Float = 100f, timestamp: Long = 0L) =
        StrokePoint(x, y, pressure, timestamp)

    @Test
    fun collapseIdenticalPositions_collapsesDwell() {
        val points = listOf(
            pt(10f, 20f, 100f, 1L),
            pt(10f, 20f, 200f, 2L),
            pt(10f, 20f, 300f, 3L),
            pt(10f, 20f, 400f, 4L),
            pt(15f, 25f, 500f, 5L),
        )
        val result = StrokeDownsampler.collapseIdenticalPositions(points)
        assertEquals(3, result.size)
        // First point of dwell run
        assertEquals(1L, result[0].timestamp)
        // Last point of dwell run
        assertEquals(4L, result[1].timestamp)
        // Next distinct point
        assertEquals(5L, result[2].timestamp)
    }

    @Test
    fun collapseIdenticalPositions_singlePoint() {
        val points = listOf(pt(10f, 20f))
        assertEquals(1, StrokeDownsampler.collapseIdenticalPositions(points).size)
    }

    @Test
    fun collapseIdenticalPositions_noDuplicates() {
        val points = listOf(pt(1f, 1f), pt(2f, 2f), pt(3f, 3f))
        assertEquals(3, StrokeDownsampler.collapseIdenticalPositions(points).size)
    }

    @Test
    fun rdp_epsilonZero_preservesAll() {
        val points = listOf(pt(0f, 0f), pt(1f, 1f), pt(2f, 0f))
        val result = StrokeDownsampler.rdp(points, 0f)
        assertEquals(3, result.size)
    }

    @Test
    fun rdp_largeEpsilon_endpointsOnly() {
        val points = listOf(pt(0f, 0f), pt(1f, 0.1f), pt(2f, 0f))
        val result = StrokeDownsampler.rdp(points, 100f)
        assertEquals(2, result.size)
        assertEquals(0f, result[0].x, 0.001f)
        assertEquals(2f, result[1].x, 0.001f)
    }

    @Test
    fun rdp_twoPoints_passthrough() {
        val points = listOf(pt(0f, 0f), pt(10f, 10f))
        val result = StrokeDownsampler.rdp(points, 1f)
        assertEquals(2, result.size)
    }

    @Test
    fun rdp_singlePoint_passthrough() {
        val points = listOf(pt(5f, 5f))
        assertEquals(1, StrokeDownsampler.rdp(points, 1f).size)
    }

    @Test
    fun downsample_combinesBothPasses() {
        val points = listOf(
            // Dwell at start
            pt(0f, 0f, 100f, 1L),
            pt(0f, 0f, 200f, 2L),
            pt(0f, 0f, 300f, 3L),
            // Meaningful movement
            pt(5f, 5f, 400f, 4L),
            pt(10f, 0f, 500f, 5L),
        )
        val stroke = InkStroke(strokeId = "test", points = points)
        val result = StrokeDownsampler.downsample(stroke, 0.5f)
        // Dwell collapsed to 2 points, then RDP keeps significant points
        assert(result.points.size < points.size)
        assertEquals("test", result.strokeId)
    }
}
