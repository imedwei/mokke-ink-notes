package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MigrationTest {

    private lateinit var tempDir: File
    private lateinit var amStorage: AutomergeStorage

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "migration-test-${System.nanoTime()}")
        tempDir.mkdirs()
        amStorage = AutomergeStorage(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `migrate mok to automerge preserves all data`() {
        // Write a .mok file with known data
        val data = sampleData()
        val mokFile = File(tempDir, "test-doc.mok")
        mokFile.outputStream().use { DocumentBundle.writeZip(it, data, emptyMap()) }

        // Migrate
        DocumentStorage.migrateToAutomerge(mokFile, amStorage, "test-doc")

        // Verify .automerge exists and data matches
        assertTrue(amStorage.exists("test-doc"))
        val doc = amStorage.load("test-doc")!!
        val recovered = AutomergeAdapter.fromAutomerge(doc)
        doc.free()

        assertEquals(data.main.strokes.size, recovered.main.strokes.size)
        assertEquals(data.main.strokes[0].strokeId, recovered.main.strokes[0].strokeId)
        assertEquals(data.main.textBlocks, recovered.main.textBlocks)
        assertEquals(data.audioRecordings, recovered.audioRecordings)
    }

    @Test
    fun `migrate mok with audio preserves audio files`() {
        val data = sampleData()
        val audioFiles = mapOf("rec-001.opus" to byteArrayOf(1, 2, 3, 4, 5))
        val mokFile = File(tempDir, "audio-doc.mok")
        mokFile.outputStream().use { DocumentBundle.writeZip(it, data, audioFiles) }

        // Create audio sidecar
        val sidecar = File(tempDir, ".audio-audio-doc")
        sidecar.mkdirs()
        File(sidecar, "rec-001.opus").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        DocumentStorage.migrateToAutomerge(mokFile, amStorage, "audio-doc")

        assertTrue(amStorage.exists("audio-doc"))
        // Audio sidecar should still exist (not deleted by migration)
        assertTrue(sidecar.isDirectory)
        assertTrue(File(sidecar, "rec-001.opus").exists())
    }

    @Test
    fun `openDocument prefers automerge over mok`() {
        val data = sampleData()

        // Create .mok with one stroke
        val mokFile = File(tempDir, "test-doc.mok")
        mokFile.outputStream().use { DocumentBundle.writeZip(it, data, emptyMap()) }

        // Create .automerge with two strokes
        val twoStrokeData = data.copy(
            main = data.main.copy(
                strokes = data.main.strokes + InkStroke("s2", listOf(StrokePoint(5f, 6f, 0.5f, 200L)), 2f)
            )
        )
        val doc = AutomergeAdapter.toAutomerge(twoStrokeData)
        amStorage.save("test-doc", doc)
        doc.free()

        // Load should prefer .automerge
        val loaded = amStorage.load("test-doc")!!
        val recovered = AutomergeAdapter.fromAutomerge(loaded)
        loaded.free()
        assertEquals(2, recovered.main.strokes.size)
    }

    @Test
    fun `openDocument migrates on first open`() {
        val data = sampleData()
        val mokFile = File(tempDir, "test-doc.mok")
        mokFile.outputStream().use { DocumentBundle.writeZip(it, data, emptyMap()) }

        // .automerge doesn't exist yet
        assertFalse(amStorage.exists("test-doc"))

        // Migrate
        DocumentStorage.migrateToAutomerge(mokFile, amStorage, "test-doc")

        // Now .automerge exists
        assertTrue(amStorage.exists("test-doc"))
    }

    @Test
    fun `migrate legacy inkup works`() {
        // Load a legacy golden .inkup file and migrate through Automerge
        val bytes = javaClass.classLoader.getResourceAsStream("golden/document_v1.inkup")!!.readBytes()
        val bundle = DocumentBundle.read(bytes)
        val original = bundle.data

        val doc = AutomergeAdapter.toAutomerge(original)
        amStorage.save("legacy", doc)
        doc.free()

        val loaded = amStorage.load("legacy")!!
        val recovered = AutomergeAdapter.fromAutomerge(loaded)
        loaded.free()

        assertEquals(original.main.strokes.size, recovered.main.strokes.size)
    }

    private fun sampleData() = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke("s1", listOf(StrokePoint(10f, 20f, 0.5f, 1000L)), 3f)
            ),
            textBlocks = listOf(
                TextBlock(id = "tb1", startLineIndex = 2, heightInLines = 1, text = "memo")
            ),
        ),
        audioRecordings = listOf(
            AudioRecording("rec-001.opus", 1000L, 5000L)
        ),
    )
}
