package com.writer.recognition

import android.graphics.RectF
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [HwrProtobuf] — protobuf encoding for the Boox MyScript HWR service
 * and JSON result parsing.
 */
class HwrProtobufTest {

    // ── Protobuf encoding ────────────────────────────────────────────────────

    @Test fun protobuf_containsLanguageField() {
        val line = singlePointLine(100f, 200f)
        val bytes = HwrProtobuf.buildProtobuf(line, 1000f, 500f)
        val fields = parseProtobuf(bytes)
        // Field 1 (lang) should be "en_US"
        val lang = fields.firstOrNull { it.fieldNumber == 1 }
        assertEquals("en_US", lang?.stringValue)
    }

    @Test fun protobuf_containsContentType() {
        val line = singlePointLine(100f, 200f)
        val bytes = HwrProtobuf.buildProtobuf(line, 1000f, 500f)
        val fields = parseProtobuf(bytes)
        val contentType = fields.firstOrNull { it.fieldNumber == 2 }
        assertEquals("Text", contentType?.stringValue)
    }

    @Test fun protobuf_containsRecognizerType() {
        val line = singlePointLine(100f, 200f)
        val bytes = HwrProtobuf.buildProtobuf(line, 1000f, 500f)
        val fields = parseProtobuf(bytes)
        val recognizerType = fields.firstOrNull { it.fieldNumber == 4 }
        assertEquals("MS_ON_SCREEN", recognizerType?.stringValue)
    }

    @Test fun protobuf_containsViewDimensions() {
        val line = singlePointLine(100f, 200f)
        val bytes = HwrProtobuf.buildProtobuf(line, 1000f, 500f)
        val fields = parseProtobuf(bytes)
        val width = fields.firstOrNull { it.fieldNumber == 5 }
        val height = fields.firstOrNull { it.fieldNumber == 6 }
        assertEquals(1000f, width?.floatValue)
        assertEquals(500f, height?.floatValue)
    }

    @Test fun protobuf_singleStroke_producesDownAndUpEvents() {
        // Single stroke with 2 points → DOWN then UP
        val stroke = InkStroke(
            strokeId = "s1",
            points = listOf(
                StrokePoint(10f, 20f, 0.5f, 1000L),
                StrokePoint(30f, 40f, 0.6f, 1010L)
            )
        )
        val line = InkLine(listOf(stroke), RectF(10f, 20f, 30f, 40f))
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f)
        val fields = parseProtobuf(bytes)

        // Field 15 = pointer events (length-delimited)
        val pointerEvents = fields.filter { it.fieldNumber == 15 }
        assertEquals("2 points → 2 pointer events", 2, pointerEvents.size)

        // Verify event types: first=DOWN(0), last=UP(2)
        val firstEvent = parseProtobuf(pointerEvents[0].bytesValue!!)
        val lastEvent = parseProtobuf(pointerEvents[1].bytesValue!!)
        assertEquals("First point should be DOWN", 0L, firstEvent.first { it.fieldNumber == 6 }.varintValue)
        assertEquals("Last point should be UP", 2L, lastEvent.first { it.fieldNumber == 6 }.varintValue)
    }

    @Test fun protobuf_multiPointStroke_producesDownMoveUpEvents() {
        // Stroke with 4 points → DOWN, MOVE, MOVE, UP
        val stroke = InkStroke(
            strokeId = "s1",
            points = listOf(
                StrokePoint(0f, 0f, 0.5f, 100L),
                StrokePoint(10f, 10f, 0.5f, 110L),
                StrokePoint(20f, 20f, 0.5f, 120L),
                StrokePoint(30f, 30f, 0.5f, 130L)
            )
        )
        val line = InkLine(listOf(stroke), RectF(0f, 0f, 30f, 30f))
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f)
        val fields = parseProtobuf(bytes)
        val pointerEvents = fields.filter { it.fieldNumber == 15 }
        assertEquals("4 points → 4 pointer events", 4, pointerEvents.size)
    }

    @Test fun protobuf_emptyStroke_skipped() {
        val stroke = InkStroke(strokeId = "s1", points = emptyList())
        val line = InkLine(listOf(stroke), RectF())
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f)
        val fields = parseProtobuf(bytes)
        val pointerEvents = fields.filter { it.fieldNumber == 15 }
        assertEquals("Empty stroke should produce no pointer events", 0, pointerEvents.size)
    }

    @Test fun protobuf_multipleStrokes_allPointsEncoded() {
        val stroke1 = InkStroke(
            strokeId = "s1",
            points = listOf(
                StrokePoint(0f, 0f, 0.5f, 100L),
                StrokePoint(10f, 10f, 0.5f, 110L)
            )
        )
        val stroke2 = InkStroke(
            strokeId = "s2",
            points = listOf(
                StrokePoint(20f, 0f, 0.5f, 200L),
                StrokePoint(30f, 10f, 0.5f, 210L),
                StrokePoint(40f, 20f, 0.5f, 220L)
            )
        )
        val line = InkLine(listOf(stroke1, stroke2), RectF(0f, 0f, 40f, 20f))
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f)
        val fields = parseProtobuf(bytes)
        val pointerEvents = fields.filter { it.fieldNumber == 15 }
        assertEquals("2+3 points → 5 pointer events", 5, pointerEvents.size)
    }

    @Test fun protobuf_singlePointStroke_producesDownAndUpEvents() {
        // Single-point stroke → must emit both DOWN and UP (regression: previously only DOWN)
        val line = singlePointLine(50f, 75f)
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f)
        val fields = parseProtobuf(bytes)
        val pointerEvents = fields.filter { it.fieldNumber == 15 }
        assertEquals("1-point stroke → 2 pointer events (DOWN+UP)", 2, pointerEvents.size)

        val downEvent = parseProtobuf(pointerEvents[0].bytesValue!!)
        val upEvent = parseProtobuf(pointerEvents[1].bytesValue!!)
        assertEquals("First event should be DOWN", 0L, downEvent.first { it.fieldNumber == 6 }.varintValue)
        assertEquals("Second event should be UP", 2L, upEvent.first { it.fieldNumber == 6 }.varintValue)

        // Both events should have the same coordinates
        assertEquals(50f, downEvent.first { it.fieldNumber == 1 }.floatValue)
        assertEquals(75f, downEvent.first { it.fieldNumber == 2 }.floatValue)
        assertEquals(50f, upEvent.first { it.fieldNumber == 1 }.floatValue)
        assertEquals(75f, upEvent.first { it.fieldNumber == 2 }.floatValue)
    }

    @Test fun protobuf_customLanguage_isEncoded() {
        val line = singlePointLine(0f, 0f)
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f, lang = "zh_CN")
        val fields = parseProtobuf(bytes)
        val lang = fields.firstOrNull { it.fieldNumber == 1 }
        assertEquals("zh_CN", lang?.stringValue)
    }

    @Test fun protobuf_multiPointStroke_eventTypeSequence() {
        // 4 points → DOWN, MOVE, MOVE, UP
        val stroke = InkStroke(
            strokeId = "s1",
            points = listOf(
                StrokePoint(0f, 0f, 0.5f, 100L),
                StrokePoint(10f, 10f, 0.5f, 110L),
                StrokePoint(20f, 20f, 0.5f, 120L),
                StrokePoint(30f, 30f, 0.5f, 130L)
            )
        )
        val line = InkLine(listOf(stroke), RectF(0f, 0f, 30f, 30f))
        val bytes = HwrProtobuf.buildProtobuf(line, 100f, 100f)
        val fields = parseProtobuf(bytes)
        val pointerEvents = fields.filter { it.fieldNumber == 15 }

        val eventTypes = pointerEvents.map { event ->
            parseProtobuf(event.bytesValue!!).first { it.fieldNumber == 6 }.varintValue
        }
        assertEquals("DOWN, MOVE, MOVE, UP", listOf(0L, 1L, 1L, 2L), eventTypes)
    }

    @Test fun protobuf_pointerEvent_coordinatesAndPressureRoundTrip() {
        val bytes = HwrProtobuf.encodePointerProto(
            x = 123.456f, y = 789.012f, t = 999L, f = 0.42f,
            pointerId = 0, eventType = 1, pointerType = 0
        )
        val fields = parseProtobuf(bytes)
        assertEquals(123.456f, fields.first { it.fieldNumber == 1 }.floatValue)
        assertEquals(789.012f, fields.first { it.fieldNumber == 2 }.floatValue)
        assertEquals(0.42f, fields.first { it.fieldNumber == 4 }.floatValue)
    }

    @Test fun protobuf_outputIsNonEmpty() {
        val line = singlePointLine(100f, 200f)
        val bytes = HwrProtobuf.buildProtobuf(line, 1000f, 500f)
        assertTrue("Protobuf output should be non-empty", bytes.isNotEmpty())
    }

    // ── Pointer event encoding ───────────────────────────────────────────────

    @Test fun pointerProto_containsCoordinates() {
        val bytes = HwrProtobuf.encodePointerProto(
            x = 42.5f, y = 99.0f, t = 12345L, f = 0.7f,
            pointerId = 0, eventType = 1, pointerType = 0
        )
        val fields = parseProtobuf(bytes)
        assertEquals(42.5f, fields.first { it.fieldNumber == 1 }.floatValue)
        assertEquals(99.0f, fields.first { it.fieldNumber == 2 }.floatValue)
    }

    @Test fun pointerProto_containsPressure() {
        val bytes = HwrProtobuf.encodePointerProto(
            x = 0f, y = 0f, t = 0L, f = 0.85f,
            pointerId = 0, eventType = 0, pointerType = 0
        )
        val fields = parseProtobuf(bytes)
        assertEquals(0.85f, fields.first { it.fieldNumber == 4 }.floatValue)
    }

    @Test fun pointerProto_containsEventType() {
        for (eventType in listOf(0, 1, 2)) {
            val bytes = HwrProtobuf.encodePointerProto(
                x = 0f, y = 0f, t = 0L, f = 0.5f,
                pointerId = 0, eventType = eventType, pointerType = 0
            )
            val fields = parseProtobuf(bytes)
            assertEquals(
                "Event type $eventType should be encoded",
                eventType.toLong(), fields.first { it.fieldNumber == 6 }.varintValue
            )
        }
    }

    // ── JSON result parsing ──────────────────────────────────────────────────

    @Test fun parseResult_successWithLabel() {
        val json = """{"result":{"label":"hello world"}}"""
        assertEquals("hello world", HwrProtobuf.parseHwrResult(json))
    }

    @Test fun parseResult_topLevelLabel() {
        val json = """{"label":"fallback text"}"""
        assertEquals("fallback text", HwrProtobuf.parseHwrResult(json))
    }

    @Test fun parseResult_emptyResult() {
        val json = """{"result":{"label":""}}"""
        assertEquals("", HwrProtobuf.parseHwrResult(json))
    }

    @Test fun parseResult_exception_returnsEmpty() {
        val json = """{"exception":{"cause":{"message":"recognition failed"}}}"""
        assertEquals("", HwrProtobuf.parseHwrResult(json))
    }

    @Test fun parseResult_invalidJson_returnsEmpty() {
        assertEquals("", HwrProtobuf.parseHwrResult("not json"))
    }

    @Test fun parseResult_emptyString_returnsEmpty() {
        assertEquals("", HwrProtobuf.parseHwrResult(""))
    }

    @Test fun parseResult_noLabelField_returnsEmpty() {
        val json = """{"result":{"other":"data"}}"""
        assertEquals("", HwrProtobuf.parseHwrResult(json))
    }

    // ── Protobuf primitives ──────────────────────────────────────────────────

    @Test fun writeVarint_smallValue() {
        val out = java.io.ByteArrayOutputStream()
        HwrProtobuf.writeVarint(out, 1L)
        assertEquals(1, out.size())
        assertEquals(1, out.toByteArray()[0].toInt())
    }

    @Test fun writeVarint_multiByteValue() {
        val out = java.io.ByteArrayOutputStream()
        HwrProtobuf.writeVarint(out, 300L)
        // 300 = 0b100101100 → varint: 0xAC 0x02
        assertEquals(2, out.size())
    }

    @Test fun writeFixed32_encodesFloatCorrectly() {
        val out = java.io.ByteArrayOutputStream()
        HwrProtobuf.writeFixed32(out, 1.0f)
        val expected = java.lang.Float.floatToIntBits(1.0f)
        val actual = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(expected, actual)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun singlePointLine(x: Float, y: Float): InkLine {
        val stroke = InkStroke(
            strokeId = "test",
            points = listOf(StrokePoint(x, y, 0.5f, 1000L))
        )
        return InkLine(listOf(stroke), RectF(x, y, x, y))
    }

    /** Minimal protobuf field parser for test assertions. */
    private data class ProtoField(
        val fieldNumber: Int,
        val wireType: Int,
        val varintValue: Long = 0,
        val floatValue: Float? = null,
        val stringValue: String? = null,
        val bytesValue: ByteArray? = null
    )

    private fun parseProtobuf(data: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        val stream = ByteArrayInputStream(data)

        while (stream.available() > 0) {
            val tag = readVarint(stream) ?: break
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when (wireType) {
                0 -> { // varint
                    val value = readVarint(stream) ?: break
                    fields.add(ProtoField(fieldNumber, wireType, varintValue = value))
                }
                2 -> { // length-delimited
                    val len = readVarint(stream)?.toInt() ?: break
                    val bytes = ByteArray(len)
                    stream.read(bytes)
                    val str = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { null }
                    fields.add(ProtoField(fieldNumber, wireType, stringValue = str, bytesValue = bytes))
                }
                5 -> { // fixed32
                    val bytes = ByteArray(4)
                    stream.read(bytes)
                    val float = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
                    fields.add(ProtoField(fieldNumber, wireType, floatValue = float))
                }
            }
        }
        return fields
    }

    private fun readVarint(stream: ByteArrayInputStream): Long? {
        var result = 0L
        var shift = 0
        while (true) {
            val b = stream.read()
            if (b == -1) return null
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}
