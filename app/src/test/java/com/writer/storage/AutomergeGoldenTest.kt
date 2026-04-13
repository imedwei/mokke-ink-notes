package com.writer.storage

import com.writer.model.StrokeType
import org.automerge.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden file tests for Automerge document compatibility.
 *
 * Two categories:
 * 1. Automerge-native golden files — load binary, verify all fields
 * 2. Protobuf → Automerge migration — every historical .inkup golden round-trips
 */
class AutomergeGoldenTest {

    // --- Automerge-native golden tests ---

    @Test
    fun `goldenV1 loads correctly`() {
        val bytes = loadGoldenAutomerge("document_v1.automerge")
        val doc = Document.load(bytes)
        val data = AutomergeAdapter.fromAutomerge(doc)
        doc.free()

        // Main column
        assertEquals(2, data.main.strokes.size)

        val s1 = data.main.strokes[0]
        assertEquals("golden-s1", s1.strokeId)
        assertEquals(3, s1.points.size)
        assertEquals(3f, s1.strokeWidth, 1e-6f)
        assertEquals(StrokeType.FREEHAND, s1.strokeType)
        assertEquals(false, s1.isGeometric)

        val s2 = data.main.strokes[1]
        assertEquals("golden-s2", s2.strokeId)
        assertEquals(2, s2.points.size)
        assertEquals(5f, s2.strokeWidth, 1e-6f)
        assertEquals(StrokeType.RECTANGLE, s2.strokeType)
        assertEquals(true, s2.isGeometric)

        assertEquals(1, data.main.textBlocks.size)
        val tb = data.main.textBlocks[0]
        assertEquals("golden-tb1", tb.id)
        assertEquals("Golden test memo", tb.text)
        assertEquals(3, tb.words.size)
        assertEquals("Golden", tb.words[0].text)

        assertEquals(1, data.main.diagramAreas.size)
        assertEquals("golden-d1", data.main.diagramAreas[0].id)

        // Cue column
        assertEquals(1, data.cue.strokes.size)
        assertEquals("golden-cue-s1", data.cue.strokes[0].strokeId)

        // Audio recordings
        assertEquals(1, data.audioRecordings.size)
        assertEquals("rec-golden.opus", data.audioRecordings[0].audioFile)
    }

    @Test
    fun `goldenV1 round-trips through adapter`() {
        val bytes = loadGoldenAutomerge("document_v1.automerge")
        val doc = Document.load(bytes)
        val data = AutomergeAdapter.fromAutomerge(doc)
        doc.free()

        // Re-encode and decode
        val doc2 = AutomergeAdapter.toAutomerge(data)
        val data2 = AutomergeAdapter.fromAutomerge(doc2)
        doc2.free()

        assertEquals(data.main.strokes.size, data2.main.strokes.size)
        assertEquals(data.main.textBlocks, data2.main.textBlocks)
        assertEquals(data.main.diagramAreas, data2.main.diagramAreas)
        assertEquals(data.cue.strokes.size, data2.cue.strokes.size)
        assertEquals(data.audioRecordings, data2.audioRecordings)
    }

    // --- Protobuf → Automerge migration golden tests ---

    @Test
    fun `allProtobufGoldens roundTrip through Automerge`() {
        val goldenFiles = listOf(
            "document_v1.inkup",
            "document_v2.inkup",
            "document_v3.inkup",
            "document_v4.inkup",
            "document_v5.inkup",
        )
        for (filename in goldenFiles) {
            val bytes = loadGoldenProtobuf(filename)
            assertNotNull("$filename should exist", bytes)

            val bundle = DocumentBundle.read(bytes)
            val original = bundle.data

            // Convert to Automerge and back
            val doc = AutomergeAdapter.toAutomerge(original)
            val recovered = AutomergeAdapter.fromAutomerge(doc)
            doc.free()

            // Synced content should match (local-only fields excluded by adapter)
            assertEquals(
                "$filename: main strokes count",
                original.main.strokes.size, recovered.main.strokes.size
            )
            for (i in original.main.strokes.indices) {
                assertEquals(
                    "$filename: stroke[$i] id",
                    original.main.strokes[i].strokeId, recovered.main.strokes[i].strokeId
                )
                assertEquals(
                    "$filename: stroke[$i] points count",
                    original.main.strokes[i].points.size, recovered.main.strokes[i].points.size
                )
            }
            assertEquals(
                "$filename: main textBlocks",
                original.main.textBlocks, recovered.main.textBlocks
            )
            assertEquals(
                "$filename: main diagramAreas",
                original.main.diagramAreas, recovered.main.diagramAreas
            )
            assertEquals(
                "$filename: cue strokes count",
                original.cue.strokes.size, recovered.cue.strokes.size
            )
            assertEquals(
                "$filename: audioRecordings",
                original.audioRecordings, recovered.audioRecordings
            )
        }
    }

    // --- Helpers ---

    private fun loadGoldenAutomerge(filename: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("golden/automerge/$filename")
        assertNotNull("Golden file golden/automerge/$filename not found", stream)
        return stream!!.readBytes()
    }

    private fun loadGoldenProtobuf(filename: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$filename")
        assertNotNull("Golden file golden/$filename not found", stream)
        return stream!!.readBytes()
    }
}
