package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class VersionHistoryTest {

    private lateinit var tempDir: File
    private lateinit var history: VersionHistory

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "version-test-${System.nanoTime()}")
        tempDir.mkdirs()
        history = VersionHistory(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createAndRestore returns original state`() {
        val data = sampleData(1)
        val doc = AutomergeAdapter.toAutomerge(data)
        val checkpoint = history.createCheckpoint("test-doc", doc, "initial")

        // Modify the document
        val tx = doc.startTransaction()
        tx.set(org.automerge.ObjectId.ROOT, "_test", "modified")
        tx.commit()

        // Restore to checkpoint
        val restored = history.restoreCheckpoint(doc, checkpoint)
        val restoredData = AutomergeAdapter.fromAutomerge(restored)

        assertEquals(1, restoredData.main.strokes.size)
        assertEquals("s-0", restoredData.main.strokes[0].strokeId)

        doc.free()
        restored.free()
    }

    @Test
    fun `multipleCheckpoints each restores to correct state`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        val cp1 = history.createCheckpoint("test-doc", doc, "one stroke")

        // Add second stroke
        val twoStrokes = sampleData(2)
        val doc2 = AutomergeAdapter.toAutomerge(twoStrokes)
        val cp2 = history.createCheckpoint("test-doc", doc2, "two strokes")

        // Restore cp1 → 1 stroke
        val r1 = history.restoreCheckpoint(doc, cp1)
        assertEquals(1, AutomergeAdapter.fromAutomerge(r1).main.strokes.size)

        // Restore cp2 → 2 strokes
        val r2 = history.restoreCheckpoint(doc2, cp2)
        assertEquals(2, AutomergeAdapter.fromAutomerge(r2).main.strokes.size)

        doc.free()
        doc2.free()
        r1.free()
        r2.free()
    }

    @Test
    fun `checkpoint after stroke add restores without stroke`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        val cpBefore = history.createCheckpoint("test-doc", doc, "before add")

        // Add a second stroke via transaction on the SAME document
        val tx = doc.startTransaction()
        val mainId = (doc.get(org.automerge.ObjectId.ROOT, "main").get() as org.automerge.AmValue.Map).id
        val strokesId = (doc.get(mainId, "strokes").get() as org.automerge.AmValue.List).id
        val newStroke = tx.insert(strokesId, doc.length(strokesId), org.automerge.ObjectType.MAP)
        tx.set(newStroke, "strokeId", "added-stroke")
        tx.set(newStroke, "strokeWidth", 2.0)
        tx.set(newStroke, "strokeType", "FREEHAND")
        tx.set(newStroke, "isGeometric", false)
        tx.set(newStroke, "points", org.automerge.ObjectType.LIST)
        tx.commit()

        assertEquals(2, AutomergeAdapter.fromAutomerge(doc).main.strokes.size)

        val restored = history.restoreCheckpoint(doc, cpBefore)
        val restoredData = AutomergeAdapter.fromAutomerge(restored)
        assertEquals(1, restoredData.main.strokes.size)
        assertEquals("s-0", restoredData.main.strokes[0].strokeId)

        doc.free()
        restored.free()
    }

    @Test
    fun `listCheckpoints returns stored checkpoints`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        history.createCheckpoint("test-doc", doc, "first")
        history.createCheckpoint("test-doc", doc, "second")
        doc.free()

        val list = history.listCheckpoints("test-doc")
        assertEquals(2, list.size)
        assertEquals("first", list[0].label)
        assertEquals("second", list[1].label)
        assertTrue(list[0].timestamp <= list[1].timestamp)
    }

    @Test
    fun `checkpoints persist to disk and reload`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        history.createCheckpoint("test-doc", doc, "saved-cp")
        doc.free()

        // Create a new VersionHistory reading from the same directory
        val history2 = VersionHistory(tempDir)
        val list = history2.listCheckpoints("test-doc")
        assertEquals(1, list.size)
        assertEquals("saved-cp", list[0].label)
    }

    @Test
    fun `persisted checkpoint restores correctly`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        history.createCheckpoint("test-doc", doc, "v1")

        // Modify
        val tx = doc.startTransaction()
        tx.set(org.automerge.ObjectId.ROOT, "_test", "modified")
        tx.commit()

        // Reload history from disk
        val history2 = VersionHistory(tempDir)
        val cp = history2.listCheckpoints("test-doc")[0]
        val restored = history2.restoreCheckpoint(doc, cp)
        val restoredData = AutomergeAdapter.fromAutomerge(restored)
        assertEquals(1, restoredData.main.strokes.size)

        doc.free()
        restored.free()
    }

    @Test
    fun `checkpoints scoped per document`() {
        val doc1 = AutomergeAdapter.toAutomerge(sampleData(1))
        val doc2 = AutomergeAdapter.toAutomerge(sampleData(2))
        history.createCheckpoint("doc-a", doc1, "alpha")
        history.createCheckpoint("doc-b", doc2, "beta")
        doc1.free()
        doc2.free()

        assertEquals(1, history.listCheckpoints("doc-a").size)
        assertEquals("alpha", history.listCheckpoints("doc-a")[0].label)
        assertEquals(1, history.listCheckpoints("doc-b").size)
        assertEquals("beta", history.listCheckpoints("doc-b")[0].label)
        assertEquals(0, history.listCheckpoints("doc-c").size)
    }

    @Test
    fun `deleteCheckpoints clears for document`() {
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        history.createCheckpoint("test-doc", doc, "cp1")
        history.createCheckpoint("test-doc", doc, "cp2")
        doc.free()

        assertEquals(2, history.listCheckpoints("test-doc").size)
        history.deleteCheckpoints("test-doc")
        assertEquals(0, history.listCheckpoints("test-doc").size)

        // Verify persisted to disk
        val history2 = VersionHistory(tempDir)
        assertEquals(0, history2.listCheckpoints("test-doc").size)
    }

    private fun sampleData(strokeCount: Int) = DocumentData(
        main = ColumnData(
            strokes = (0 until strokeCount).map { i ->
                InkStroke("s-$i", listOf(StrokePoint(i.toFloat(), 0f, 0.5f, i.toLong())), 2f)
            }
        )
    )
}
