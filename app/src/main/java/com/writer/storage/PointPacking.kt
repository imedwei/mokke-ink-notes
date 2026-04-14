package com.writer.storage

import com.writer.model.StrokePoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Delta-encoded packing for stroke points in line-height-normalized coordinates.
 *
 * Points are stored relative to an `originLine`. X and Y are normalized to
 * line-height units (device-independent). Deltas between consecutive points
 * are varint-encoded for compact storage.
 *
 * Format:
 * ```
 * Header:
 *   numPoints:     u16  (2 bytes)
 *   baseTimestamp:  i64  (8 bytes, absolute ms of first point)
 *   x0:            i32  (4 bytes, first X in quantized units)
 *   y0:            i32  (4 bytes, first Y in quantized units, relative to originLine)
 *   p0:            i32  (4 bytes, first pressure in quantized units)
 *   = 22 bytes header
 *
 * Subsequent points (varint-encoded deltas):
 *   dx:         varsint
 *   dy:         varsint
 *   dPressure:  varsint
 *   dTimestamp:  varint (unsigned, always >= 0)
 * ```
 *
 * Scale: 0.01 line-height units (~1px at 94px line spacing). Pressure scale: 0.001.
 */
object PointPacking {

    private const val COORD_SCALE = 0.01f      // 0.01 line-height units
    private const val PRESSURE_SCALE = 0.001f   // 0.001 pressure units

    /**
     * Pack points into a compact delta-encoded byte array.
     * Coordinates are normalized to line-height units relative to [originLine].
     */
    fun pack(
        points: List<StrokePoint>,
        originLine: Int,
        lineSpacing: Float,
        topMargin: Float,
    ): ByteArray {
        if (points.isEmpty()) return ByteArray(2).also { it[0] = 0; it[1] = 0 }

        val out = ByteArrayOutputStream(22 + points.size * 5)

        // Normalize coordinates to line-height units
        val originY = topMargin + originLine * lineSpacing

        fun quantizeX(px: Float): Int = (px / lineSpacing / COORD_SCALE).roundToInt()
        fun quantizeY(px: Float): Int = ((px - originY) / lineSpacing / COORD_SCALE).roundToInt()
        fun quantizeP(p: Float): Int = (p / PRESSURE_SCALE).roundToInt()

        // Header
        writeU16(out, points.size)
        writeI64(out, points[0].timestamp)
        val x0 = quantizeX(points[0].x)
        val y0 = quantizeY(points[0].y)
        val p0 = quantizeP(points[0].pressure)
        writeI32(out, x0)
        writeI32(out, y0)
        writeI32(out, p0)

        // Delta-encoded subsequent points
        var prevX = x0
        var prevY = y0
        var prevP = p0
        var prevT = points[0].timestamp

        for (i in 1 until points.size) {
            val pt = points[i]
            val qx = quantizeX(pt.x)
            val qy = quantizeY(pt.y)
            val qp = quantizeP(pt.pressure)

            writeVarsint(out, qx - prevX)
            writeVarsint(out, qy - prevY)
            writeVarsint(out, qp - prevP)
            writeVarint(out, (pt.timestamp - prevT).coerceAtLeast(0))

            prevX = qx
            prevY = qy
            prevP = qp
            prevT = pt.timestamp
        }

        return out.toByteArray()
    }

    /**
     * Unpack a delta-encoded byte array back to stroke points.
     * Coordinates are denormalized from line-height units using [originLine].
     */
    fun unpack(
        data: ByteArray,
        originLine: Int,
        lineSpacing: Float,
        topMargin: Float,
    ): List<StrokePoint> {
        if (data.size < 2) return emptyList()
        val input = ByteArrayInputStream(data)

        val numPoints = readU16(input)
        if (numPoints == 0) return emptyList()

        val originY = topMargin + originLine * lineSpacing

        fun dequantizeX(q: Int): Float = q * COORD_SCALE * lineSpacing
        fun dequantizeY(q: Int): Float = originY + q * COORD_SCALE * lineSpacing
        fun dequantizeP(q: Int): Float = q * PRESSURE_SCALE

        val baseTimestamp = readI64(input)
        var prevX = readI32(input)
        var prevY = readI32(input)
        var prevP = readI32(input)

        val result = ArrayList<StrokePoint>(numPoints)
        result.add(StrokePoint(dequantizeX(prevX), dequantizeY(prevY), dequantizeP(prevP), baseTimestamp))

        var prevT = baseTimestamp
        for (i in 1 until numPoints) {
            prevX += readVarsint(input)
            prevY += readVarsint(input)
            prevP += readVarsint(input)
            prevT += readVarint(input)
            result.add(StrokePoint(dequantizeX(prevX), dequantizeY(prevY), dequantizeP(prevP), prevT))
        }

        return result
    }

    // --- Varint encoding (same as protobuf) ---

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun readVarint(input: ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b == -1) return result
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    /** Zigzag encode a signed int, then write as varint. */
    private fun writeVarsint(out: ByteArrayOutputStream, value: Int) {
        val zigzag = (value shl 1) xor (value shr 31)
        writeVarint(out, zigzag.toLong())
    }

    /** Read varint, then zigzag decode to signed int. */
    private fun readVarsint(input: ByteArrayInputStream): Int {
        val zigzag = readVarint(input).toInt()
        return (zigzag ushr 1) xor -(zigzag and 1)
    }

    // --- Fixed-width encoding ---

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun readU16(input: ByteArrayInputStream): Int {
        val lo = input.read() and 0xFF
        val hi = input.read() and 0xFF
        return lo or (hi shl 8)
    }

    private fun writeI32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun readI32(input: ByteArrayInputStream): Int {
        val b0 = input.read() and 0xFF
        val b1 = input.read() and 0xFF
        val b2 = input.read() and 0xFF
        val b3 = input.read() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun writeI64(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 8) {
            out.write((value shr (i * 8)).toInt() and 0xFF)
        }
    }

    private fun readI64(input: ByteArrayInputStream): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((input.read().toLong() and 0xFF) shl (i * 8))
        }
        return result
    }
}
