package com.writer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3c contract: DocumentModel gains a transcript ColumnModel and owns the
 * document-level audioRecordings list (moved up from WritingCoordinator, which
 * was per-column and therefore couldn't be the source of truth for a new
 * transcript-column-only audio model).
 */
class DocumentModelTest {

    @Test
    fun transcriptColumnModelDefault_isEmpty() {
        val doc = DocumentModel()
        assertNotNull("DocumentModel.transcript must exist", doc.transcript)
        assertTrue("default transcript has no strokes", doc.transcript.activeStrokes.isEmpty())
        assertTrue("default transcript has no text blocks", doc.transcript.textBlocks.isEmpty())
        assertTrue("default transcript has no diagram areas", doc.transcript.diagramAreas.isEmpty())
    }

    @Test
    fun audioRecordingsDefault_isEmpty() {
        val doc = DocumentModel()
        assertTrue(doc.audioRecordings.isEmpty())
    }

    @Test
    fun hasAnyRecording_reflectsList() {
        val doc = DocumentModel()
        assertFalse(doc.hasAnyRecording())

        doc.addAudioRecording(AudioRecording("rec-1.opus", 1000L, 5000L))
        assertTrue(doc.hasAnyRecording())
    }

    @Test
    fun addAudioRecording_appends() {
        val doc = DocumentModel()
        doc.addAudioRecording(AudioRecording("rec-1.opus", 1000L, 5000L))
        doc.addAudioRecording(AudioRecording("rec-2.opus", 7000L, 3000L))
        assertEquals(2, doc.audioRecordings.size)
        assertEquals("rec-1.opus", doc.audioRecordings[0].audioFile)
        assertEquals("rec-2.opus", doc.audioRecordings[1].audioFile)
    }

    @Test
    fun restoreAudioRecordings_replacesList() {
        val doc = DocumentModel()
        doc.addAudioRecording(AudioRecording("stale.opus", 0L, 0L))

        doc.restoreAudioRecordings(listOf(
            AudioRecording("fresh-1.opus", 1000L, 5000L),
            AudioRecording("fresh-2.opus", 7000L, 3000L),
        ))

        assertEquals(2, doc.audioRecordings.size)
        assertEquals("fresh-1.opus", doc.audioRecordings[0].audioFile)
    }

}
