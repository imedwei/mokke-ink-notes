package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import com.writer.model.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomergeSyncTest {

    @Test
    fun `initial sync creates full document`() {
        val data = sampleData(2)
        val sync = AutomergeSync()
        sync.sync(data)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(2, result.main.strokes.size)
        assertEquals("s-0", result.main.strokes[0].strokeId)
        assertEquals("s-1", result.main.strokes[1].strokeId)
    }

    @Test
    fun `adding a stroke produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(sampleData(2))
        val headsBefore = sync.document.heads.clone()

        // Add one stroke
        sync.sync(sampleData(3))

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("delta should be small (was ${delta.size} bytes)", delta.size < 500)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(3, result.main.strokes.size)
    }

    @Test
    fun `removing a stroke produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(sampleData(3))
        val headsBefore = sync.document.heads.clone()

        // Remove middle stroke
        val data = sampleData(3)
        val trimmed = data.copy(
            main = data.main.copy(
                strokes = listOf(data.main.strokes[0], data.main.strokes[2])
            )
        )
        sync.sync(trimmed)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("delta should be small (was ${delta.size} bytes)", delta.size < 300)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(2, result.main.strokes.size)
        assertEquals("s-0", result.main.strokes[0].strokeId)
        assertEquals("s-2", result.main.strokes[1].strokeId)
    }

    @Test
    fun `no change produces no delta`() {
        val sync = AutomergeSync()
        val data = sampleData(2)
        sync.sync(data)
        val headsBefore = sync.document.heads.clone()

        // Sync same data
        sync.sync(data)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertEquals("no changes should produce empty delta", 0, delta.size)
    }

    @Test
    fun `adding text block produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(sampleData(1))
        val headsBefore = sync.document.heads.clone()

        val data = sampleData(1).let {
            it.copy(main = it.main.copy(
                textBlocks = listOf(TextBlock(
                    id = "tb-1", startLineIndex = 2, heightInLines = 1,
                    text = "hello world",
                    words = listOf(WordInfo("hello", 0.9f, 0, 300), WordInfo("world", 0.8f, 400, 700))
                ))
            ))
        }
        sync.sync(data)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("delta should be small (was ${delta.size} bytes)", delta.size < 500)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.main.textBlocks.size)
        assertEquals("hello world", result.main.textBlocks[0].text)
    }

    @Test
    fun `adding diagram area produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(sampleData(1))
        val headsBefore = sync.document.heads.clone()

        val data = sampleData(1).let {
            it.copy(main = it.main.copy(
                diagramAreas = listOf(DiagramArea("d-1", 5, 3))
            ))
        }
        sync.sync(data)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("delta should be small (was ${delta.size} bytes)", delta.size < 300)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.main.diagramAreas.size)
    }

    @Test
    fun `adding audio recording produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(sampleData(1))
        val headsBefore = sync.document.heads.clone()

        val data = sampleData(1).copy(
            audioRecordings = listOf(AudioRecording("rec-001.ogg", 1000L, 5000L))
        )
        sync.sync(data)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("delta should be small (was ${delta.size} bytes)", delta.size < 300)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.audioRecordings.size)
    }

    @Test
    fun `cue column changes produce small delta`() {
        val sync = AutomergeSync()
        sync.sync(sampleData(1))
        val headsBefore = sync.document.heads.clone()

        val data = sampleData(1).copy(
            cue = ColumnData(
                strokes = listOf(InkStroke("cue-s1", listOf(StrokePoint(1f, 2f, 0.5f, 100L)), 2f))
            )
        )
        sync.sync(data)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("delta should be small (was ${delta.size} bytes)", delta.size < 500)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.cue.strokes.size)
    }

    @Test
    fun `full round-trip matches adapter`() {
        val data = DocumentData(
            main = ColumnData(
                strokes = (0 until 5).map { i ->
                    InkStroke("s-$i", listOf(
                        StrokePoint(i.toFloat(), i * 2f, 0.5f, i * 1000L),
                        StrokePoint(i + 1f, i * 2 + 1f, 0.7f, i * 1000L + 500),
                    ), 3f)
                },
                textBlocks = listOf(
                    TextBlock("tb-1", 2, 1, "hello", "rec.ogg", 0, 1000,
                        listOf(WordInfo("hello", 0.9f, 0, 500)))
                ),
                diagramAreas = listOf(DiagramArea("d-1", 5, 3)),
            ),
            cue = ColumnData(
                strokes = listOf(InkStroke("cue-1", listOf(StrokePoint(1f, 2f, 0.5f, 100L)), 2f))
            ),
            audioRecordings = listOf(AudioRecording("rec.ogg", 1000L, 5000L)),
        )

        val sync = AutomergeSync()
        sync.sync(data)
        val result = AutomergeAdapter.fromAutomerge(sync.document)

        assertEquals(data.main.strokes.size, result.main.strokes.size)
        for (i in data.main.strokes.indices) {
            assertEquals(data.main.strokes[i].strokeId, result.main.strokes[i].strokeId)
            assertEquals(data.main.strokes[i].points.size, result.main.strokes[i].points.size)
        }
        assertEquals(data.main.textBlocks, result.main.textBlocks)
        assertEquals(data.main.diagramAreas, result.main.diagramAreas)
        assertEquals(data.cue.strokes.size, result.cue.strokes.size)
        assertEquals(data.audioRecordings, result.audioRecordings)
    }

    @Test
    fun `repeated sync converges to same state as fresh adapter`() {
        val sync = AutomergeSync()

        // Simulate a series of edits
        sync.sync(sampleData(1))
        sync.sync(sampleData(3))
        sync.sync(sampleData(2)) // remove one
        sync.sync(sampleData(5)) // add more

        val incrementalResult = AutomergeAdapter.fromAutomerge(sync.document)

        // Compare with a fresh conversion
        val freshDoc = AutomergeAdapter.toAutomerge(sampleData(5))
        val freshResult = AutomergeAdapter.fromAutomerge(freshDoc)
        freshDoc.free()

        assertEquals(freshResult.main.strokes.size, incrementalResult.main.strokes.size)
        for (i in freshResult.main.strokes.indices) {
            assertEquals(freshResult.main.strokes[i].strokeId, incrementalResult.main.strokes[i].strokeId)
        }
    }

    private fun sampleData(strokeCount: Int) = DocumentData(
        main = ColumnData(
            strokes = (0 until strokeCount).map { i ->
                InkStroke(
                    "s-$i",
                    listOf(
                        StrokePoint(i * 10f, i * 20f, 0.5f, i * 1000L),
                        StrokePoint(i * 10f + 5, i * 20f + 5, 0.7f, i * 1000L + 500),
                    ),
                    3f
                )
            }
        )
    )
}
