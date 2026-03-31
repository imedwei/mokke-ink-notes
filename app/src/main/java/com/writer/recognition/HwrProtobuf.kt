package com.writer.recognition

import android.util.Log
import com.writer.model.InkLine
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Hand-rolled protobuf encoding for the Boox MyScript HWR service.
 * No protobuf library dependency — encodes directly to the wire format
 * expected by `com.onyx.android.ksync.service.KHwrService.batchRecognize()`.
 *
 * Extracted from the recognition engine for testability.
 */
object HwrProtobuf {

    /**
     * Build the top-level HWRInputProto protobuf bytes from an [InkLine].
     *
     * Field numbers (from HWRInputDataProto.HWRInputProto):
     *   1: lang (string), 2: contentType (string), 4: recognizerType (string),
     *   5: viewWidth (float), 6: viewHeight (float),
     *   10: recognizeText (bool), 15: repeated pointerEvents
     */
    fun buildProtobuf(
        line: InkLine, viewWidth: Float, viewHeight: Float, lang: String = "en_US"
    ): ByteArray {
        // Pre-compute expected size: ~48 bytes per pointer event (tag + length-delimited
        // sub-message with 4 fixed32 fields + 2 varints), plus ~60 bytes of header fields.
        val totalPoints = line.strokes.sumOf { it.points.size }
        val estimatedSize = 60 + totalPoints * 48
        val out = ByteArrayOutputStream(estimatedSize)

        writeTag(out, 1, 2); writeString(out, lang)
        writeTag(out, 2, 2); writeString(out, "Text")
        writeTag(out, 4, 2); writeString(out, "MS_ON_SCREEN")
        writeTag(out, 5, 5); writeFixed32(out, viewWidth)
        writeTag(out, 6, 5); writeFixed32(out, viewHeight)
        writeTag(out, 10, 0); writeVarint(out, 1)  // recognizeText = true

        val pointerBuf = ByteArrayOutputStream(64)
        for (stroke in line.strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue

            for ((i, point) in points.withIndex()) {
                val isFirst = i == 0
                val isLast = i == points.size - 1
                val eventTypes = when {
                    isFirst && isLast -> listOf(0, 2)  // single-point: DOWN then UP
                    isFirst           -> listOf(0)     // DOWN
                    isLast            -> listOf(2)     // UP
                    else              -> listOf(1)     // MOVE
                }
                for (eventType in eventTypes) {
                    val pointerBytes = encodePointerProto(
                        point.x, point.y, point.timestamp, point.pressure,
                        pointerId = 0, eventType = eventType, pointerType = 0,
                        reuse = pointerBuf
                    )
                    writeTag(out, 15, 2)
                    writeBytes(out, pointerBytes)
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Encode a single HWRPointerProto message.
     * Fields: float x(1), float y(2), sint64 t(3), float f(4),
     *         sint32 pointerId(5), enum eventType(6), enum pointerType(7)
     */
    internal fun encodePointerProto(
        x: Float, y: Float, t: Long, f: Float,
        pointerId: Int, eventType: Int, pointerType: Int,
        reuse: ByteArrayOutputStream? = null
    ): ByteArray {
        val out = reuse?.apply { reset() } ?: ByteArrayOutputStream(64)
        writeTag(out, 1, 5); writeFixed32(out, x)
        writeTag(out, 2, 5); writeFixed32(out, y)
        writeTag(out, 3, 0); writeVarint(out, (t shl 1) xor (t shr 63))
        writeTag(out, 4, 5); writeFixed32(out, f)
        writeTag(out, 5, 0); writeVarint(out, ((pointerId shl 1) xor (pointerId shr 31)).toLong())
        writeTag(out, 6, 0); writeVarint(out, eventType.toLong())
        writeTag(out, 7, 0); writeVarint(out, pointerType.toLong())
        return out.toByteArray()
    }

    // --- Protobuf primitives ---

    internal fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    internal fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    internal fun writeFixed32(out: ByteArrayOutputStream, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits shr 8) and 0xFF)
        out.write((bits shr 16) and 0xFF)
        out.write((bits shr 24) and 0xFF)
    }

    internal fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    internal fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    // --- Result parsing ---

    /**
     * Parse the JSON result from the HWR service.
     * Success: `{"result":{"label":"recognized text"}}`
     * Error: `{"exception":{"cause":{"message":"..."}}}` → returns empty string
     */
    fun parseHwrResult(json: String): String {
        return parseHwrResultWithCandidates(json).text
    }

    /**
     * Parse the JSON result with full candidate extraction.
     * Attempts to find candidates in the MyScript JSON response.
     * Falls back to single candidate from the "label" field.
     */
    fun parseHwrResultWithCandidates(json: String): RecognitionResult {
        return try {
            val obj = JSONObject(json)
            if (obj.has("exception")) return RecognitionResult(emptyList())

            val result = obj.optJSONObject("result")
            val label = result?.optString("label", "") ?: obj.optString("label", "")

            // Try to extract candidates array from MyScript response
            val candidatesArray = result?.optJSONArray("candidates")
                ?: obj.optJSONArray("candidates")
            if (candidatesArray != null) {
                val candidates = (0 until candidatesArray.length()).map { i ->
                    val c = candidatesArray.getJSONObject(i)
                    RecognitionCandidate(
                        text = c.optString("label", "").trim(),
                        score = if (c.has("score")) c.getDouble("score").toFloat() else null
                    )
                }.filter { it.text.isNotEmpty() }
                if (candidates.isNotEmpty()) return RecognitionResult(candidates)
            }

            // Fallback: single candidate from label
            if (label.isNotEmpty()) {
                RecognitionResult(listOf(RecognitionCandidate(label.trim(), null)))
            } else {
                RecognitionResult(emptyList())
            }
        } catch (e: Exception) {
            Log.w("HwrProtobuf", "Failed to parse HWR result: ${e.message}")
            RecognitionResult(emptyList())
        }
    }
}
