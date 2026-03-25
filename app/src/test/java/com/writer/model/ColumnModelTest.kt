package com.writer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ColumnModel] extraction and [DocumentModel] composition.
 */
class ColumnModelTest {

    private fun samplePoints() = listOf(
        StrokePoint(0f, 0f, 1f, 0L),
        StrokePoint(100f, 100f, 1f, 100L)
    )

    // ── ColumnModel construction ─────────────────────────────────────────

    @Test fun columnModel_defaultsToEmpty() {
        val col = ColumnModel()
        assertTrue(col.activeStrokes.isEmpty())
        assertTrue(col.diagramAreas.isEmpty())
    }

    @Test fun columnModel_holdsStrokesAndDiagramAreas() {
        val col = ColumnModel()
        col.activeStrokes.add(InkStroke(strokeId = "s1", points = samplePoints()))
        col.diagramAreas.add(DiagramArea(id = "d1", startLineIndex = 0, heightInLines = 2))

        assertEquals(1, col.activeStrokes.size)
        assertEquals("s1", col.activeStrokes[0].strokeId)
        assertEquals(1, col.diagramAreas.size)
        assertEquals("d1", col.diagramAreas[0].id)
    }

    // ── DocumentModel composition ────────────────────────────────────────

    @Test fun documentModel_hasTwoColumns() {
        val doc = DocumentModel()
        doc.main.activeStrokes.add(InkStroke(strokeId = "main1", points = samplePoints()))
        doc.cue.activeStrokes.add(InkStroke(strokeId = "cue1", points = samplePoints()))

        assertEquals(1, doc.main.activeStrokes.size)
        assertEquals("main1", doc.main.activeStrokes[0].strokeId)
        assertEquals(1, doc.cue.activeStrokes.size)
        assertEquals("cue1", doc.cue.activeStrokes[0].strokeId)
    }

    @Test fun documentModel_columnsAreIndependent() {
        val doc = DocumentModel()
        doc.main.activeStrokes.add(InkStroke(strokeId = "s1", points = samplePoints()))
        doc.main.diagramAreas.add(DiagramArea(id = "d1", startLineIndex = 0, heightInLines = 1))

        assertTrue("Cue strokes should be empty", doc.cue.activeStrokes.isEmpty())
        assertTrue("Cue diagram areas should be empty", doc.cue.diagramAreas.isEmpty())
    }

    @Test fun documentModel_sharedStateIsIndependentOfColumns() {
        val doc = DocumentModel()
        doc.language = "fr-FR"

        assertEquals("fr-FR", doc.language)
        // Columns don't hold language — it's document-level
        assertTrue(doc.main.activeStrokes.isEmpty())
        assertTrue(doc.cue.activeStrokes.isEmpty())
    }
}
