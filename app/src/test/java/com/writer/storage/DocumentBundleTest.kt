package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import com.writer.model.proto.DocumentProto
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DocumentBundleTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun sampleData() = DocumentData(
        main = ColumnData(
            strokes = listOf(InkStroke("s1", listOf(
                StrokePoint(10f, 20f, 0.5f, 1000L),
                StrokePoint(30f, 40f, 0.8f, 2000L)
            ), 3f)),
            lineTextCache = mapOf(0 to "hello"),
            textBlocks = listOf(TextBlock(id = "tb1", startLineIndex = 2, heightInLines = 1, text = "memo"))
        ),
        audioRecordings = listOf(AudioRecording("rec-001.opus", 1000L, 5000L))
    )

    // ── ZIP format detection ────────────────────────────────────────────

    @Test
    fun isZipFormat_withPkMagicBytes_true() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
        assertTrue(DocumentBundle.isZipFormat(bytes))
    }

    @Test
    fun isZipFormat_withProtobufBytes_false() {
        val proto = sampleData().toProto().encode()
        assertFalse(DocumentBundle.isZipFormat(proto))
    }

    @Test
    fun isZipFormat_emptyBytes_false() {
        assertFalse(DocumentBundle.isZipFormat(byteArrayOf()))
    }

    @Test
    fun isZipFormat_singleByte_false() {
        assertFalse(DocumentBundle.isZipFormat(byteArrayOf(0x50)))
    }

    // ── ZIP write/read round-trip ───────────────────────────────────────

    @Test
    fun writeAndRead_documentOnly_roundTrips() {
        val data = sampleData()
        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, data)

        val bytes = out.toByteArray()
        assertTrue(DocumentBundle.isZipFormat(bytes))

        val result = DocumentBundle.readZip(ByteArrayInputStream(bytes))
        assertEquals(1, result.data.main.strokes.size)
        assertEquals("hello", result.data.main.lineTextCache[0])
        assertEquals(1, result.data.main.textBlocks.size)
        assertEquals("memo", result.data.main.textBlocks[0].text)
        assertEquals(1, result.data.audioRecordings.size)
    }

    @Test
    fun writeAndRead_withAudioFiles_roundTrips() {
        val data = sampleData()
        val audioFiles = mapOf("rec-001.opus" to "fake audio data".toByteArray())
        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, data, audioFiles)

        val result = DocumentBundle.readZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, result.data.main.strokes.size)
        assertEquals(1, result.audioFiles.size)
        assertArrayEquals("fake audio data".toByteArray(), result.audioFiles["rec-001.opus"])
    }

    @Test
    fun writeAndRead_noAudioFiles_emptyMap() {
        val data = sampleData()
        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, data)

        val result = DocumentBundle.readZip(ByteArrayInputStream(out.toByteArray()))
        assertTrue(result.audioFiles.isEmpty())
    }

    @Test
    fun writeAndRead_multipleAudioFiles() {
        val data = sampleData()
        val audioFiles = mapOf(
            "rec-001.opus" to "audio one".toByteArray(),
            "rec-002.opus" to "audio two".toByteArray()
        )
        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, data, audioFiles)

        val result = DocumentBundle.readZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, result.audioFiles.size)
        assertArrayEquals("audio one".toByteArray(), result.audioFiles["rec-001.opus"])
        assertArrayEquals("audio two".toByteArray(), result.audioFiles["rec-002.opus"])
    }

    // ── Legacy raw protobuf loading ─────────────────────────────────────

    @Test
    fun readLegacyProtobuf_decodesCorrectly() {
        val rawBytes = sampleData().toProto().encode()
        assertFalse(DocumentBundle.isZipFormat(rawBytes))

        val result = DocumentBundle.readLegacyProtobuf(rawBytes)
        assertEquals(1, result.data.main.strokes.size)
        assertEquals("hello", result.data.main.lineTextCache[0])
        assertTrue(result.audioFiles.isEmpty())
    }

    // ── Unified read (auto-detect) ──────────────────────────────────────

    @Test
    fun read_detectsZipFormat() {
        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, sampleData())

        val result = DocumentBundle.read(out.toByteArray())
        assertEquals(1, result.data.main.strokes.size)
    }

    @Test
    fun read_detectsLegacyProtobuf() {
        val rawBytes = sampleData().toProto().encode()

        val result = DocumentBundle.read(rawBytes)
        assertEquals(1, result.data.main.strokes.size)
    }
}
