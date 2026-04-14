package com.writer.storage

import com.writer.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointPackingTest {

    // Simulated device: Go 7 at 1.875x density → lineSpacing=94px, topMargin=56px
    private val ls = 94f
    private val tm = 56f

    private fun pack(points: List<StrokePoint>, originLine: Int) =
        PointPacking.pack(points, originLine, ls, tm)

    private fun unpack(data: ByteArray, originLine: Int) =
        PointPacking.unpack(data, originLine, ls, tm)

    @Test
    fun `pack unpack round trips with precision`() {
        val points = listOf(
            StrokePoint(100f, tm + 2 * ls + 10f, 0.5f, 1000L),
            StrokePoint(105f, tm + 2 * ls + 15f, 0.6f, 1050L),
            StrokePoint(112f, tm + 2 * ls + 8f, 0.4f, 1100L),
        )
        val originLine = 2

        val packed = pack(points, originLine)
        val unpacked = unpack(packed, originLine)

        assertEquals(3, unpacked.size)
        for (i in points.indices) {
            assertEquals("x[$i]", points[i].x, unpacked[i].x, ls * 0.015f)
            assertEquals("y[$i]", points[i].y, unpacked[i].y, ls * 0.015f)
            assertEquals("pressure[$i]", points[i].pressure, unpacked[i].pressure, 0.015f)
            assertEquals("timestamp[$i]", points[i].timestamp, unpacked[i].timestamp)
        }
    }

    @Test
    fun `empty list produces small array`() {
        val packed = pack(emptyList(), 0)
        val unpacked = unpack(packed, 0)
        assertTrue(unpacked.isEmpty())
        assertEquals(2, packed.size) // just the numPoints=0 u16
    }

    @Test
    fun `single point packs to header only`() {
        val points = listOf(StrokePoint(50f, tm + 3 * ls, 0.7f, 5000L))
        val packed = pack(points, 3)
        val unpacked = unpack(packed, 3)

        assertEquals(1, unpacked.size)
        assertEquals(points[0].x, unpacked[0].x, ls * 0.015f)
        assertEquals(points[0].y, unpacked[0].y, ls * 0.015f)
        // Header = 22 bytes, no deltas
        assertEquals(22, packed.size)
    }

    @Test
    fun `packed size is much smaller than raw`() {
        val points = (0 until 20).map { i ->
            StrokePoint(
                100f + i * 5f,
                tm + 4 * ls + i * 2f,
                0.5f + i * 0.01f,
                1000L + i * 50L
            )
        }
        val packed = pack(points, 4)
        val rawSize = 20 * 20 // 20 bytes per point raw

        assertTrue(
            "packed (${packed.size}) should be much smaller than raw ($rawSize)",
            packed.size < rawSize / 2
        )
    }

    @Test
    fun `deltas are small for typical handwriting`() {
        val points = (0 until 10).map { i ->
            StrokePoint(
                200f + i * 2f,
                tm + 5 * ls + i * 1f,
                0.5f,
                2000L + i * 30L
            )
        }
        val packed = pack(points, 5)
        // Header 22 + 9 deltas × ~4 bytes = ~58 bytes
        assertTrue("packed size (${packed.size}) should be compact", packed.size < 100)
    }

    @Test
    fun `normalization works across different line spacings`() {
        val originLine = 3
        // Draw at 2.5 line-widths X, originLine + 0.3 Y
        val points = listOf(
            StrokePoint(ls * 2.5f, tm + originLine * ls + ls * 0.3f, 0.8f, 100L),
        )

        val packed = pack(points, originLine)

        // Unpack on a "different device" with different line spacing
        val newLs = 50f  // smaller device
        val newTm = 30f
        val unpacked = PointPacking.unpack(packed, originLine, newLs, newTm)

        // X should be 2.5 line-widths on new device
        assertEquals(newLs * 2.5f, unpacked[0].x, newLs * 0.015f)
        // Y should be at originLine + 0.3 on new device
        val expectedY = newTm + originLine * newLs + newLs * 0.3f
        assertEquals(expectedY, unpacked[0].y, newLs * 0.015f)
    }

    @Test
    fun `origin line shift produces shifted points`() {
        val originLine = 2
        val points = listOf(
            StrokePoint(100f, tm + 2 * ls + 10f, 0.5f, 1000L),
            StrokePoint(110f, tm + 2 * ls + 20f, 0.6f, 1050L),
        )

        val packed = pack(points, originLine)

        // Unpack at shifted origin (simulating space insert of 3 lines)
        val shiftedOrigin = originLine + 3
        val unpacked = unpack(packed, shiftedOrigin)

        // Points should be shifted down by 3 lines
        assertEquals(points[0].y + 3 * ls, unpacked[0].y, ls * 0.015f)
        assertEquals(points[1].y + 3 * ls, unpacked[1].y, ls * 0.015f)
        // X unchanged
        assertEquals(points[0].x, unpacked[0].x, ls * 0.015f)
    }

    @Test
    fun `large coordinates pack correctly`() {
        // Stroke far down the page (line 50) with large X
        val originLine = 50
        val points = listOf(
            StrokePoint(1200f, tm + 50 * ls + 40f, 0.9f, 999999L),
            StrokePoint(1210f, tm + 50 * ls + 45f, 0.85f, 1000050L),
        )
        val packed = pack(points, originLine)
        val unpacked = unpack(packed, originLine)

        assertEquals(points[0].x, unpacked[0].x, ls * 0.015f)
        assertEquals(points[0].y, unpacked[0].y, ls * 0.015f)
        assertEquals(points[0].timestamp, unpacked[0].timestamp)
    }

    @Test
    fun `negative Y delta packs correctly`() {
        // Stroke that goes upward
        val originLine = 5
        val points = listOf(
            StrokePoint(100f, tm + 5 * ls + 50f, 0.5f, 1000L),
            StrokePoint(105f, tm + 5 * ls + 30f, 0.5f, 1050L), // Y goes up
            StrokePoint(110f, tm + 5 * ls + 10f, 0.5f, 1100L), // Y goes up more
        )
        val packed = pack(points, originLine)
        val unpacked = unpack(packed, originLine)

        for (i in points.indices) {
            assertEquals("y[$i]", points[i].y, unpacked[i].y, ls * 0.015f)
        }
    }
}
