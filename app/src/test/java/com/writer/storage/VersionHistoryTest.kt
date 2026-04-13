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
    private lateinit var storage: AutomergeStorage
    private lateinit var history: VersionHistory

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "version-test-${System.nanoTime()}")
        tempDir.mkdirs()
        storage = AutomergeStorage(tempDir)
        history = VersionHistory()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createAndRestore returns original state`() {
        val data = sampleData(1)
        val doc = AutomergeAdapter.toAutomerge(data)
        val checkpoint = history.createCheckpoint(doc, "initial")

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
        val cp1 = history.createCheckpoint(doc, "one stroke")

        // Add second stroke
        val twoStrokes = sampleData(2)
        val doc2 = AutomergeAdapter.toAutomerge(twoStrokes)
        val cp2 = history.createCheckpoint(doc2, "two strokes")

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
        // Create document with 1 stroke, checkpoint it
        val doc = AutomergeAdapter.toAutomerge(sampleData(1))
        val cpBefore = history.createCheckpoint(doc, "before add")

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

        // Verify we now have 2 strokes
        assertEquals(2, AutomergeAdapter.fromAutomerge(doc).main.strokes.size)

        // Restore to before-add checkpoint
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
        history.createCheckpoint(doc, "first")
        history.createCheckpoint(doc, "second")
        doc.free()

        val list = history.listCheckpoints()
        assertEquals(2, list.size)
        assertEquals("first", list[0].label)
        assertEquals("second", list[1].label)
        assertTrue(list[0].timestamp <= list[1].timestamp)
    }

    private fun sampleData(strokeCount: Int) = DocumentData(
        main = ColumnData(
            strokes = (0 until strokeCount).map { i ->
                InkStroke("s-$i", listOf(StrokePoint(i.toFloat(), 0f, 0.5f, i.toLong())), 2f)
            }
        )
    )
}
