package com.writer.storage

import com.writer.model.proto.NumericRunProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericRunEncoderTest {

    // ── Coordinate encoding ────────────────────────────────────────────────

    @Test
    fun encodeCoordinates_singleValue() {
        val run = NumericRunEncoder.encodeCoordinates(floatArrayOf(5.0f))
        assertEquals(1, run.deltas.size)
        assertEquals(0, run.deltas[0]) // delta from offset is 0
        assertEquals(5.0f, run.offset!!, 0.001f)
    }

    @Test
    fun encodeCoordinates_roundTrip_preservesPrecision() {
        val values = floatArrayOf(1.0f, 1.05f, 1.10f, 1.20f, 1.35f)
        val run = NumericRunEncoder.encodeCoordinates(values)
        val decoded = NumericRunEncoder.decode(run)
        assertEquals(values.size, decoded.size)
        for (i in values.indices) {
            assertEquals("Index $i", values[i], decoded[i], 0.01f)
        }
    }

    @Test
    fun encodeCoordinates_negativeValues() {
        val values = floatArrayOf(-2.5f, -2.0f, -1.0f, 0.0f, 1.0f)
        val run = NumericRunEncoder.encodeCoordinates(values)
        val decoded = NumericRunEncoder.decode(run)
        for (i in values.indices) {
            assertEquals("Index $i", values[i], decoded[i], 0.01f)
        }
    }

    @Test
    fun encodeCoordinates_largeRange() {
        // Simulate a stroke spanning 20 line-units (a long vertical stroke)
        val values = FloatArray(100) { it * 0.2f }
        val run = NumericRunEncoder.encodeCoordinates(values)
        val decoded = NumericRunEncoder.decode(run)
        for (i in values.indices) {
            assertEquals("Index $i", values[i], decoded[i], 0.01f)
        }
    }

    @Test
    fun encodeCoordinates_constantValue_allDeltasZero() {
        val values = floatArrayOf(3.0f, 3.0f, 3.0f, 3.0f)
        val run = NumericRunEncoder.encodeCoordinates(values)
        // All deltas should be 0 (highly compressible)
        assertTrue(run.deltas.all { it == 0 })
    }

    @Test
    fun encodeCoordinates_monotonic_allDeltasPositive() {
        val values = floatArrayOf(0.0f, 0.1f, 0.2f, 0.3f)
        val run = NumericRunEncoder.encodeCoordinates(values)
        // First delta is 0 (from offset), rest should be positive
        assertEquals(0, run.deltas[0])
        for (i in 1 until run.deltas.size) {
            assertTrue("Delta $i should be positive", run.deltas[i] > 0)
        }
    }

    // ── Pressure encoding ──────────────────────────────────────────────────

    @Test
    fun encodePressure_roundTrip() {
        val values = floatArrayOf(0.0f, 0.3f, 0.7f, 1.0f, 0.5f)
        val run = NumericRunEncoder.encodePressure(values)
        val decoded = NumericRunEncoder.decode(run)
        for (i in values.indices) {
            assertEquals("Index $i", values[i], decoded[i], 0.01f)
        }
    }

    @Test
    fun encodePressure_allDefault_detectedByHelper() {
        assertTrue(NumericRunEncoder.allDefaultPressure(floatArrayOf(1f, 1f, 1f)))
        assertFalse(NumericRunEncoder.allDefaultPressure(floatArrayOf(1f, 0.5f, 1f)))
    }

    // ── Timestamp encoding ─────────────────────────────────────────────────

    @Test
    fun encodeTimestamps_roundTrip() {
        val timestamps = longArrayOf(1000L, 1005L, 1010L, 1015L, 1020L)
        val base = timestamps[0]
        val run = NumericRunEncoder.encodeTimestamps(timestamps, base)
        val decoded = NumericRunEncoder.decodeTimestamps(run, base)
        assertArrayEquals(timestamps, decoded)
    }

    @Test
    fun encodeTimestamps_irregularIntervals() {
        val timestamps = longArrayOf(1000L, 1003L, 1008L, 1020L, 1021L)
        val base = timestamps[0]
        val run = NumericRunEncoder.encodeTimestamps(timestamps, base)
        val decoded = NumericRunEncoder.decodeTimestamps(run, base)
        assertArrayEquals(timestamps, decoded)
    }

    @Test
    fun encodeTimestamps_allZero_detectedByHelper() {
        assertTrue(NumericRunEncoder.allZeroTimestamps(longArrayOf(0L, 0L, 0L)))
        assertFalse(NumericRunEncoder.allZeroTimestamps(longArrayOf(0L, 1000L, 2000L)))
    }

    @Test
    fun encodeTimestamps_singleValue() {
        val base = 5000L
        val run = NumericRunEncoder.encodeTimestamps(longArrayOf(5000L), base)
        val decoded = NumericRunEncoder.decodeTimestamps(run, base)
        assertEquals(1, decoded.size)
        assertEquals(5000L, decoded[0])
    }

    @Test
    fun encodeTimestamps_epochMillis_preservesPrecision() {
        // 2026-era epoch ms — int64 base avoids any Float precision issues
        val base = 1774000000000L // ~March 2026
        val timestamps = longArrayOf(base, base + 5, base + 10, base + 18, base + 23)
        val run = NumericRunEncoder.encodeTimestamps(timestamps, base)
        val decoded = NumericRunEncoder.decodeTimestamps(run, base)
        assertArrayEquals(timestamps, decoded)
    }

    @Test
    fun encodeTimestamps_farFuture_preservesPrecision() {
        // 2050-era epoch ms — would fail with Float offset, works with int64 base
        val base = 2524608000000L // ~year 2050
        val timestamps = longArrayOf(base, base + 5, base + 10)
        val run = NumericRunEncoder.encodeTimestamps(timestamps, base)
        val decoded = NumericRunEncoder.decodeTimestamps(run, base)
        assertArrayEquals(timestamps, decoded)
    }

    // ── Legacy v3 timestamp decode ──────────────────────────────────────────

    @Test
    fun legacyTimestampBaseMs_reconstructsFromFloatOffset() {
        // v3 stored base time as float seconds in the run's offset field
        val baseSec = 1774000000f // ~March 2026
        val run = NumericRunProto(deltas = listOf(0, 5, 10), offset = baseSec)
        val baseMs = NumericRunEncoder.legacyTimestampBaseMs(run)
        // Float loses precision — expect up to ~128s drift for 2026-era values
        val expectedMs = baseSec.toLong() * 1000L
        assertEquals(expectedMs, baseMs)
        // Verify the decoded timestamps — deltas are accumulated (0, 0+5, 0+5+10)
        val decoded = NumericRunEncoder.decodeTimestamps(run, baseMs)
        assertEquals(3, decoded.size)
        assertEquals(baseMs, decoded[0])
        assertEquals(baseMs + 5, decoded[1])
        assertEquals(baseMs + 15, decoded[2])
    }

    @Test
    fun legacyTimestampBaseMs_nullOffset_returnsZero() {
        val run = NumericRunProto(deltas = listOf(0, 5))
        assertEquals(0L, NumericRunEncoder.legacyTimestampBaseMs(run))
    }

    // ── Decode from raw proto ──────────────────────────────────────────────

    @Test
    fun decode_withDefaultScaleAndOffset() {
        // Simulate a proto with defaults (scale=1, offset=0)
        val run = NumericRunProto(deltas = listOf(10, 5, -3, 2))
        val decoded = NumericRunEncoder.decode(run)
        assertEquals(10f, decoded[0], 0.001f)
        assertEquals(15f, decoded[1], 0.001f)
        assertEquals(12f, decoded[2], 0.001f)
        assertEquals(14f, decoded[3], 0.001f)
    }

    @Test
    fun decode_withCustomScaleAndOffset() {
        val run = NumericRunProto(deltas = listOf(0, 5, 5), scale = 0.01f, offset = 2.0f)
        val decoded = NumericRunEncoder.decode(run)
        assertEquals(2.0f, decoded[0], 0.001f)
        assertEquals(2.05f, decoded[1], 0.001f)
        assertEquals(2.10f, decoded[2], 0.001f)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun encode_emptyArray_throws() {
        NumericRunEncoder.encode(floatArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeTimestamps_emptyArray_throws() {
        NumericRunEncoder.encodeTimestamps(longArrayOf(), 0L)
    }

    @Test
    fun encode_twoPoints_roundTrip() {
        val values = floatArrayOf(1.23f, 4.56f)
        val decoded = NumericRunEncoder.decode(NumericRunEncoder.encodeCoordinates(values))
        assertEquals(2, decoded.size)
        assertEquals(1.23f, decoded[0], 0.01f)
        assertEquals(4.56f, decoded[1], 0.01f)
    }

    // ── Custom scale ───────────────────────────────────────────────────────

    @Test
    fun encode_customScale_roundTrip() {
        val values = floatArrayOf(100f, 105f, 110f, 108f)
        val run = NumericRunEncoder.encode(values, scale = 0.1f)
        assertEquals(0.1f, run.scale!!, 0.001f)
        val decoded = NumericRunEncoder.decode(run)
        for (i in values.indices) {
            assertEquals("Index $i", values[i], decoded[i], 0.1f)
        }
    }
}
