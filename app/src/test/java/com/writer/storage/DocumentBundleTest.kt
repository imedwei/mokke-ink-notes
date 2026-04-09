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

    // ── Audio preservation regression tests ─────────────────────────────
    // Bug: auto-save (no audioFiles param) was overwriting the bundle,
    // wiping previously recorded audio.

    @Test
    fun writeWithoutAudio_thenReadBack_audioFilesEmpty() {
        // Simulates auto-save: only document data, no audio
        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, sampleData())
        val result = DocumentBundle.readZip(ByteArrayInputStream(out.toByteArray()))
        assertTrue("Auto-save without audio should produce empty audioFiles",
            result.audioFiles.isEmpty())
    }

    @Test
    fun writeWithAudio_readBack_thenRewriteWithoutAudio_audioLostInBundle() {
        // This test documents the bundle-level behavior:
        // writeZip without audio does NOT preserve previous audio.
        // Preservation must happen at the DocumentStorage layer.
        val audioFiles = mapOf("rec-123.webm" to "audio data".toByteArray())
        val out1 = ByteArrayOutputStream()
        DocumentBundle.writeZip(out1, sampleData(), audioFiles)

        // Verify audio is in the first bundle
        val result1 = DocumentBundle.readZip(ByteArrayInputStream(out1.toByteArray()))
        assertEquals(1, result1.audioFiles.size)

        // Rewrite without audio (simulates raw auto-save)
        val out2 = ByteArrayOutputStream()
        DocumentBundle.writeZip(out2, sampleData())

        // Audio is gone at the bundle level
        val result2 = DocumentBundle.readZip(ByteArrayInputStream(out2.toByteArray()))
        assertTrue("Raw writeZip without audio wipes audio entries",
            result2.audioFiles.isEmpty())
    }

    @Test
    fun mergeAudioFiles_preservesExistingAndAddsNew() {
        // Simulates DocumentStorage.save() merge behavior
        val existing = mapOf("rec-100.webm" to "first recording".toByteArray())
        val newAudio = mapOf("rec-200.webm" to "second recording".toByteArray())
        val merged = existing + newAudio

        val out = ByteArrayOutputStream()
        DocumentBundle.writeZip(out, sampleData(), merged)

        val result = DocumentBundle.readZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, result.audioFiles.size)
        assertArrayEquals("first recording".toByteArray(), result.audioFiles["rec-100.webm"])
        assertArrayEquals("second recording".toByteArray(), result.audioFiles["rec-200.webm"])
    }

    // ── DocumentStorage.saveToFile audio preservation ─────────────────
    // Regression: auto-save (no audioFiles param) was overwriting the .mok
    // bundle, wiping previously recorded audio. These tests exercise the
    // production code path in DocumentStorage.saveToFile/resolveAudioFiles.

    @Test
    fun saveToFile_autoSave_preservesExistingAudio() {
        val tmpFile = java.io.File.createTempFile("test-doc", ".mok")
        try {
            // Step 1: Save WITH audio (simulates lecture stop)
            val audio = mapOf("rec-001.webm" to "lecture audio".toByteArray())
            DocumentStorage.saveToFile(tmpFile, sampleData(), audio)

            // Step 2: Auto-save WITHOUT audio (production auto-save path)
            DocumentStorage.saveToFile(tmpFile, sampleData())

            // Audio must survive
            val result = tmpFile.inputStream().use { DocumentBundle.readZip(it) }
            assertEquals("Audio must survive auto-save", 1, result.audioFiles.size)
            assertArrayEquals("lecture audio".toByteArray(), result.audioFiles["rec-001.webm"])
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun saveToFile_secondRecording_mergesWithFirst() {
        val tmpFile = java.io.File.createTempFile("test-doc", ".mok")
        try {
            // First recording
            DocumentStorage.saveToFile(tmpFile, sampleData(), mapOf("rec-001.webm" to "first".toByteArray()))

            // Second recording (new audio provided)
            DocumentStorage.saveToFile(tmpFile, sampleData(), mapOf("rec-002.webm" to "second".toByteArray()))

            // Both present
            val result = tmpFile.inputStream().use { DocumentBundle.readZip(it) }
            assertEquals(2, result.audioFiles.size)
            assertArrayEquals("first".toByteArray(), result.audioFiles["rec-001.webm"])
            assertArrayEquals("second".toByteArray(), result.audioFiles["rec-002.webm"])
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun saveToFile_newFile_noExistingAudio() {
        val tmpFile = java.io.File.createTempFile("test-doc", ".mok")
        tmpFile.delete() // ensure it doesn't exist
        try {
            DocumentStorage.saveToFile(tmpFile, sampleData())
            val result = tmpFile.inputStream().use { DocumentBundle.readZip(it) }
            assertTrue("New file should have no audio", result.audioFiles.isEmpty())
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun resolveAudioFiles_noFileExists_returnsProvided() {
        val tmpFile = java.io.File.createTempFile("test-doc", ".mok")
        tmpFile.delete()
        val audio = mapOf("rec.webm" to "data".toByteArray())
        val resolved = DocumentStorage.resolveAudioFiles(tmpFile, audio)
        assertEquals(1, resolved.size)
    }

    @Test
    fun resolveAudioFiles_fileExists_emptyNew_returnsExisting() {
        val tmpFile = java.io.File.createTempFile("test-doc", ".mok")
        try {
            val audio = mapOf("rec.webm" to "existing".toByteArray())
            tmpFile.outputStream().use { DocumentBundle.writeZip(it, sampleData(), audio) }

            val resolved = DocumentStorage.resolveAudioFiles(tmpFile, emptyMap())
            assertEquals(1, resolved.size)
            assertArrayEquals("existing".toByteArray(), resolved["rec.webm"])
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun resolveAudioFiles_fileExists_newProvided_mergesBoth() {
        val tmpFile = java.io.File.createTempFile("test-doc", ".mok")
        try {
            val existing = mapOf("rec-1.webm" to "first".toByteArray())
            tmpFile.outputStream().use { DocumentBundle.writeZip(it, sampleData(), existing) }

            val newAudio = mapOf("rec-2.webm" to "second".toByteArray())
            val resolved = DocumentStorage.resolveAudioFiles(tmpFile, newAudio)
            assertEquals(2, resolved.size)
            assertArrayEquals("first".toByteArray(), resolved["rec-1.webm"])
            assertArrayEquals("second".toByteArray(), resolved["rec-2.webm"])
        } finally {
            tmpFile.delete()
        }
    }
}
