package com.writer.storage

import com.writer.model.proto.NumericRunProto
import kotlin.math.roundToInt

object NumericRunEncoder {

    private const val DEFAULT_COORDINATE_SCALE = 0.01f
    private const val DEFAULT_PRESSURE_SCALE = 0.01f
    private const val DEFAULT_TIME_SCALE = 1f

    /**
     * Encode a float array as a delta-encoded NumericRunProto.
     * Scale is chosen per-run: the caller can supply a specific scale,
     * or pass null to use a sensible default based on the data.
     */
    fun encode(values: FloatArray, scale: Float? = null): NumericRunProto {
        require(values.isNotEmpty()) { "Cannot encode empty array" }

        val s = scale ?: DEFAULT_COORDINATE_SCALE
        val offset = values[0]

        val deltas = IntArray(values.size)
        var prev = 0 // quantized accumulator
        for (i in values.indices) {
            val quantized = ((values[i] - offset) / s).roundToInt()
            deltas[i] = quantized - prev
            prev = quantized
        }

        return NumericRunProto(
            deltas = deltas.toList(),
            scale = s,
            offset = offset
        )
    }

    /**
     * Decode a NumericRunProto back to float values.
     */
    fun decode(run: NumericRunProto): FloatArray {
        val s = run.scale ?: 1f
        val offset = run.offset ?: 0f
        val deltas = run.deltas

        val values = FloatArray(deltas.size)
        var acc = 0
        for (i in deltas.indices) {
            acc += deltas[i]
            values[i] = offset + s * acc
        }
        return values
    }

    fun encodeCoordinates(values: FloatArray): NumericRunProto =
        encode(values, DEFAULT_COORDINATE_SCALE)

    fun encodePressure(values: FloatArray): NumericRunProto =
        encode(values, DEFAULT_PRESSURE_SCALE)

    fun encodeTimestamps(timestamps: LongArray): NumericRunProto {
        require(timestamps.isNotEmpty()) { "Cannot encode empty array" }
        // Store base as seconds (fits losslessly in Float up to ~2^24 seconds ≈ year 2531).
        // Absorb the ms residual into deltas[0] so absolute precision is preserved.
        val baseMs = timestamps[0]
        val baseSec = baseMs / 1000L
        val residualMs = (baseMs - baseSec * 1000L).toInt()

        val deltas = IntArray(timestamps.size)
        deltas[0] = residualMs
        var prev = residualMs
        for (i in 1 until timestamps.size) {
            val msFromBase = (timestamps[i] - baseSec * 1000L).toInt()
            deltas[i] = msFromBase - prev
            prev = msFromBase
        }
        return NumericRunProto(
            deltas = deltas.toList(),
            scale = DEFAULT_TIME_SCALE,
            offset = baseSec.toFloat()
        )
    }

    fun decodeTimestamps(run: NumericRunProto): LongArray {
        val baseSec = (run.offset ?: 0f).toLong()
        val s = (run.scale ?: 1f)
        val deltas = run.deltas
        val values = LongArray(deltas.size)
        var acc = 0
        for (i in deltas.indices) {
            acc += deltas[i]
            values[i] = baseSec * 1000L + (s * acc).toLong()
        }
        return values
    }

    /** Returns true if all pressures are 1.0 (default), meaning pressure_run can be omitted. */
    fun allDefaultPressure(pressures: FloatArray): Boolean =
        pressures.all { it == 1f }

    /** Returns true if all timestamps are 0 (not captured), meaning time_run can be omitted. */
    fun allZeroTimestamps(timestamps: LongArray): Boolean =
        timestamps.all { it == 0L }
}
