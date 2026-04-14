package com.writer.storage

import android.app.Application
import android.net.Uri
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.ui.writing.AutoSaver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomergeSinkTest {

    private lateinit var tempDir: File
    private lateinit var storage: AutomergeStorage
    private lateinit var sink: AutomergeSink
    private lateinit var fakeExportSink: FakeExportSink

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "automerge-sink-test-${System.nanoTime()}")
        tempDir.mkdirs()
        storage = AutomergeStorage(tempDir)
        fakeExportSink = FakeExportSink()
        sink = AutomergeSink(storage, fakeExportSink)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save writes incremental file`() {
        val data = sampleData()
        // First save initializes
        assertTrue(sink.save("doc1", data))
        assertTrue(storage.exists("doc1"))

        // Second save with same data should succeed (incremental)
        assertTrue(sink.save("doc1", data))
    }

    @Test
    fun `save multiple times preserves latest state`() {
        var strokes = sampleData().main.strokes
        // Build up strokes cumulatively
        for (i in 1..10) {
            strokes = strokes + InkStroke(
                "s-$i",
                listOf(StrokePoint(i.toFloat(), 0f, 0.5f, i.toLong())),
                2f
            )
            val data = DocumentData(main = ColumnData(strokes = strokes))
            sink.save("doc1", data)
        }

        // Load and verify latest state is preserved
        val loaded = storage.load("doc1")
        assertNotNull(loaded)
        val recovered = AutomergeAdapter.fromAutomerge(loaded!!)
        loaded.free()
        assertEquals(11, recovered.main.strokes.size) // 1 original + 10 added
    }

    @Test
    fun `incremental save after adding one stroke is small`() {
        val data = sampleData()
        sink.save("doc1", data)

        // Full save size
        val fullSize = File(tempDir, "doc1.automerge").length()

        // Add one stroke
        val data2 = data.copy(
            main = data.main.copy(
                strokes = data.main.strokes + InkStroke(
                    "s2", listOf(StrokePoint(30f, 40f, 0.6f, 2000L)), 2f
                )
            )
        )
        sink.save("doc1", data2)

        val incFile = File(tempDir, "doc1.automerge.inc")
        assertTrue("incremental file should exist", incFile.exists())
        assertTrue(
            "incremental (${incFile.length()} bytes) should be smaller than full ($fullSize bytes)",
            incFile.length() < fullSize
        )
    }

    @Test
    fun `export delegates to export sink`() {
        val data = sampleData()
        sink.save("doc1", data)

        val syncUri = Uri.parse("content://test/sync")
        sink.export("doc1", data, "# Test markdown", syncUri)

        assertEquals(1, fakeExportSink.exports.size)
        assertEquals("doc1", fakeExportSink.exports[0])
    }

    @Test
    fun `getDocument returns live document after save`() {
        sink.save("doc1", sampleData())
        val doc = sink.getDocument("doc1")
        assertNotNull(doc)
    }

    @Test
    fun `getDocument returns null before any save`() {
        val doc = sink.getDocument("doc1")
        assertEquals(null, doc)
    }

    private fun sampleData() = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke("s1", listOf(StrokePoint(10f, 20f, 0.5f, 1000L)), 3f)
            )
        )
    )

    private class FakeExportSink : AutoSaver.Sink {
        val exports = mutableListOf<String>()
        override fun save(name: String, state: DocumentData): Boolean = true
        override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
            exports.add(name)
        }
    }
}
