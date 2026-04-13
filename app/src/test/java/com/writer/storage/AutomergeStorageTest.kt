package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AutomergeStorageTest {

    private lateinit var tempDir: File
    private lateinit var storage: AutomergeStorage

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "automerge-test-${System.nanoTime()}")
        tempDir.mkdirs()
        storage = AutomergeStorage(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save and load round trips`() {
        val data = sampleData()
        val doc = AutomergeAdapter.toAutomerge(data)
        storage.save("test-doc", doc)
        doc.free()

        val loaded = storage.load("test-doc")
        assertNotNull(loaded)
        val recovered = AutomergeAdapter.fromAutomerge(loaded!!)
        loaded.free()

        assertEquals(data.main.strokes.size, recovered.main.strokes.size)
        assertEquals(data.main.strokes[0].strokeId, recovered.main.strokes[0].strokeId)
    }

    @Test
    fun `saveIncremental accumulates changes`() {
        val data = sampleData()
        val doc = AutomergeAdapter.toAutomerge(data)
        storage.save("test-doc", doc)

        // Make an edit and save incrementally
        val tx = doc.startTransaction()
        val mainId = (doc.get(org.automerge.ObjectId.ROOT, "main").get() as org.automerge.AmValue.Map).id
        val strokesId = (doc.get(mainId, "strokes").get() as org.automerge.AmValue.List).id
        val newStroke = tx.insert(strokesId, doc.length(strokesId), org.automerge.ObjectType.MAP)
        tx.set(newStroke, "strokeId", "incremental-s1")
        tx.set(newStroke, "strokeWidth", 2.0)
        tx.set(newStroke, "strokeType", "FREEHAND")
        tx.set(newStroke, "isGeometric", false)
        tx.set(newStroke, "points", org.automerge.ObjectType.LIST)
        tx.commit()

        storage.saveIncremental("test-doc", doc)
        doc.free()

        // Load and verify both original and incremental data present
        val loaded = storage.load("test-doc")
        assertNotNull(loaded)
        val items = loaded!!.listItems(
            (loaded.get(
                (loaded.get(org.automerge.ObjectId.ROOT, "main").get() as org.automerge.AmValue.Map).id,
                "strokes"
            ).get() as org.automerge.AmValue.List).id
        ).get()
        assertTrue("should have more strokes after incremental save", items.size > 1)
        loaded.free()
    }

    @Test
    fun `saveIncremental after full save produces small file`() {
        val data = sampleData()
        val doc = AutomergeAdapter.toAutomerge(data)
        storage.save("test-doc", doc)

        val fullSize = File(tempDir, "test-doc.amok").length()

        // Make a small edit
        val tx = doc.startTransaction()
        tx.set(org.automerge.ObjectId.ROOT, "_schemaVersion", 2)
        tx.commit()

        storage.saveIncremental("test-doc", doc)
        doc.free()

        val incrementalFile = File(tempDir, "test-doc.amok.inc")
        assertTrue("incremental file should exist", incrementalFile.exists())
        assertTrue(
            "incremental should be smaller than full save ($fullSize vs ${incrementalFile.length()})",
            incrementalFile.length() < fullSize
        )
    }

    @Test
    fun `delete removes file`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData())
        storage.save("test-doc", doc)
        doc.free()

        assertTrue(storage.exists("test-doc"))
        storage.delete("test-doc")
        assertFalse(storage.exists("test-doc"))
        assertNull(storage.load("test-doc"))
    }

    @Test
    fun `list returns document names`() {
        val doc1 = AutomergeAdapter.toAutomerge(sampleData())
        storage.save("doc-alpha", doc1)
        doc1.free()

        val doc2 = AutomergeAdapter.toAutomerge(sampleData())
        storage.save("doc-beta", doc2)
        doc2.free()

        val names = storage.list()
        assertTrue("should contain doc-alpha", names.contains("doc-alpha"))
        assertTrue("should contain doc-beta", names.contains("doc-beta"))
        assertEquals(2, names.size)
    }

    private fun sampleData() = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke(
                    "s1",
                    listOf(StrokePoint(10f, 20f, 0.5f, 1000L)),
                    3f
                )
            )
        )
    )
}
