package com.writer.storage

import com.writer.model.proto.NumericRunProto
import kotlin.math.roundToInt

object NumericRunEncoder {

    private const val DEFAULT_COORDINATE_SCALE = 0.01f
    private const val DEFAULT_PRESSURE_SCALE = 0.01f

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

    /**
     * Encode timestamps as ms deltas from a base. The base (epoch ms of the
     * first point) is stored separately in InkStrokeProto.stroke_timestamp.
     * Returns just the delta run (offset=0, scale=1).
     */
    fun encodeTimestamps(timestamps: LongArray, baseMs: Long): NumericRunProto {
        require(timestamps.isNotEmpty()) { "Cannot encode empty array" }
        val deltas = IntArray(timestamps.size)
        var prev = 0
        for (i in timestamps.indices) {
            val msFromBase = timestamps[i] - baseMs
            // Guard: sint32 limits a single stroke's duration to ~24.8 days,
            // which is well beyond any realistic pen-down duration.
            require(msFromBase in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "Timestamp delta $msFromBase ms exceeds sint32 range at index $i"
            }
            deltas[i] = msFromBase.toInt() - prev
            prev = msFromBase.toInt()
        }
        return NumericRunProto(deltas = deltas.toList())
    }

    /**
     * Decode timestamps from a delta run and an absolute base.
     * For v4 files, baseMs comes from InkStrokeProto.stroke_timestamp.
     * For v3 files (legacy), baseMs is reconstructed from the run's float offset.
     */
    fun decodeTimestamps(run: NumericRunProto, baseMs: Long): LongArray {
        val s = (run.scale ?: 1f)
        val values = LongArray(run.deltas.size)
        var acc = 0
        for (i in run.deltas.indices) {
            acc += run.deltas[i]
            values[i] = baseMs + (s * acc).toLong()
        }
        return values
    }

    /**
     * Reconstruct base ms from a v3 legacy time_run that stored the base
     * as a float offset in seconds. Float32 only has ~7 significant digits,
     * so for 2026-era epoch seconds (~1.77e9) this loses up to ~128s of
     * precision. v4's int64 stroke_timestamp eliminates this issue.
     */
    fun legacyTimestampBaseMs(run: NumericRunProto): Long {
        val offset = run.offset ?: 0f
        return (offset.toLong()) * 1000L
    }

    /** Returns true if all pressures are 1.0 (default), meaning pressure_run can be omitted. */
    fun allDefaultPressure(pressures: FloatArray): Boolean =
        pressures.all { it == 1f }

    /** Returns true if all timestamps are 0 (not captured), meaning time_run can be omitted. */
    fun allZeroTimestamps(timestamps: LongArray): Boolean =
        timestamps.all { it == 0L }
}
