package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.TextBlock
import com.writer.model.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomergeAdapterTest {

    @Test
    fun `roundTrip emptyDocument`() {
        val data = DocumentData(main = ColumnData())
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip withStrokes`() {
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke(
                        strokeId = "s1",
                        points = listOf(
                            StrokePoint(10f, 20f, 0.5f, 1000L),
                            StrokePoint(30f, 40f, 0.8f, 2000L),
                        ),
                        strokeWidth = 3.5f,
                        isGeometric = true,
                        strokeType = StrokeType.LINE,
                    )
                )
            )
        )
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip withTextBlocks`() {
        val data = DocumentData(
            main = ColumnData(
                textBlocks = listOf(
                    TextBlock(
                        id = "tb1",
                        startLineIndex = 2,
                        heightInLines = 3,
                        text = "Hello world",
                        audioFile = "rec-001.opus",
                        audioStartMs = 100,
                        audioEndMs = 5000,
                        words = listOf(
                            WordInfo("Hello", 0.95f, 100, 300),
                            WordInfo("world", 0.88f, 400, 600),
                        ),
                    )
                )
            )
        )
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip withDiagramAreas`() {
        val data = DocumentData(
            main = ColumnData(
                diagramAreas = listOf(
                    DiagramArea(id = "d1", startLineIndex = 5, heightInLines = 10)
                )
            )
        )
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip withAudioRecordings`() {
        val data = DocumentData(
            main = ColumnData(),
            audioRecordings = listOf(
                AudioRecording("rec-001.opus", 1000L, 5000L),
                AudioRecording("rec-002.opus", 8000L, 3000L),
            )
        )
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip fullDocument`() {
        val data = sampleData()
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip preservesStrokePointPrecision`() {
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke(
                        strokeId = "precise",
                        points = listOf(
                            StrokePoint(1.23456789f, 9.87654321f, 0.123456f, 999999999L),
                        ),
                        strokeWidth = 0.1f,
                    )
                )
            )
        )
        val result = roundTrip(data)
        val originalPoint = data.main.strokes[0].points[0]
        val resultPoint = result.main.strokes[0].points[0]
        // Automerge stores doubles, so float->double->float may lose precision.
        // We verify they survive round-trip through double conversion.
        assertEquals(originalPoint.x.toDouble(), resultPoint.x.toDouble(), 1e-6)
        assertEquals(originalPoint.y.toDouble(), resultPoint.y.toDouble(), 1e-6)
        assertEquals(originalPoint.pressure.toDouble(), resultPoint.pressure.toDouble(), 1e-6)
        assertEquals(originalPoint.timestamp, resultPoint.timestamp)
    }

    @Test
    fun `roundTrip withCueColumn`() {
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke("main-s1", listOf(StrokePoint(1f, 2f, 0.5f, 100L)), 2f)
                )
            ),
            cue = ColumnData(
                strokes = listOf(
                    InkStroke("cue-s1", listOf(StrokePoint(5f, 6f, 0.7f, 200L)), 4f)
                ),
                textBlocks = listOf(
                    TextBlock(id = "cue-tb1", startLineIndex = 0, heightInLines = 1, text = "cue text")
                )
            )
        )
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip allStrokeTypes`() {
        val strokes = StrokeType.entries.mapIndexed { i, type ->
            InkStroke(
                strokeId = "stroke-$i",
                points = listOf(StrokePoint(i.toFloat(), 0f, 0.5f, i.toLong())),
                strokeType = type,
            )
        }
        val data = DocumentData(main = ColumnData(strokes = strokes))
        val result = roundTrip(data)
        assertEquals(StrokeType.entries.size, result.main.strokes.size)
        for (i in StrokeType.entries.indices) {
            assertEquals(StrokeType.entries[i], result.main.strokes[i].strokeType)
        }
    }

    @Test
    fun `incrementalEdit addsStroke`() {
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke("s1", listOf(StrokePoint(1f, 2f, 0.5f, 100L)), 2f)
                )
            )
        )
        val doc = AutomergeAdapter.toAutomerge(data)

        // Add another stroke by converting updated data
        val updated = data.copy(
            main = data.main.copy(
                strokes = data.main.strokes + InkStroke(
                    "s2", listOf(StrokePoint(3f, 4f, 0.6f, 200L)), 3f
                )
            )
        )
        val doc2 = AutomergeAdapter.toAutomerge(updated)
        val result = AutomergeAdapter.fromAutomerge(doc2)

        assertEquals(2, result.main.strokes.size)
        assertEquals("s1", result.main.strokes[0].strokeId)
        assertEquals("s2", result.main.strokes[1].strokeId)

        doc.free()
        doc2.free()
    }

    // --- Helpers ---

    private fun sampleData() = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke(
                    strokeId = "s1",
                    points = listOf(
                        StrokePoint(10f, 20f, 0.5f, 1000L),
                        StrokePoint(30f, 40f, 0.8f, 2000L),
                    ),
                    strokeWidth = 3f,
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND,
                ),
                InkStroke(
                    strokeId = "s2",
                    points = listOf(
                        StrokePoint(50f, 60f, 0.3f, 3000L),
                    ),
                    strokeWidth = 5f,
                    isGeometric = true,
                    strokeType = StrokeType.RECTANGLE,
                ),
            ),
            textBlocks = listOf(
                TextBlock(
                    id = "tb1",
                    startLineIndex = 2,
                    heightInLines = 1,
                    text = "memo text",
                    audioFile = "rec-001.opus",
                    audioStartMs = 100,
                    audioEndMs = 5000,
                    words = listOf(
                        WordInfo("memo", 0.9f, 100, 300),
                        WordInfo("text", 0.85f, 400, 600),
                    ),
                )
            ),
            diagramAreas = listOf(
                DiagramArea(id = "d1", startLineIndex = 5, heightInLines = 3)
            ),
        ),
        cue = ColumnData(
            strokes = listOf(
                InkStroke("cue-s1", listOf(StrokePoint(1f, 2f, 0.5f, 100L)), 2f)
            )
        ),
        audioRecordings = listOf(
            AudioRecording("rec-001.opus", 1000L, 5000L)
        ),
    )

    private fun roundTrip(data: DocumentData): DocumentData {
        val doc = AutomergeAdapter.toAutomerge(data)
        val result = AutomergeAdapter.fromAutomerge(doc)
        doc.free()
        return result
    }

    private fun assertDocumentEquals(expected: DocumentData, actual: DocumentData) {
        assertColumnEquals("main", expected.main, actual.main)
        assertColumnEquals("cue", expected.cue, actual.cue)
        assertEquals("audioRecordings", expected.audioRecordings, actual.audioRecordings)
    }

    private fun assertColumnEquals(label: String, expected: ColumnData, actual: ColumnData) {
        assertEquals("$label strokes size", expected.strokes.size, actual.strokes.size)
        for (i in expected.strokes.indices) {
            val e = expected.strokes[i]
            val a = actual.strokes[i]
            assertEquals("$label stroke[$i].strokeId", e.strokeId, a.strokeId)
            assertEquals("$label stroke[$i].strokeWidth", e.strokeWidth, a.strokeWidth, 1e-6f)
            assertEquals("$label stroke[$i].strokeType", e.strokeType, a.strokeType)
            assertEquals("$label stroke[$i].isGeometric", e.isGeometric, a.isGeometric)
            assertEquals("$label stroke[$i].points.size", e.points.size, a.points.size)
            for (j in e.points.indices) {
                assertEquals("$label stroke[$i].point[$j].x", e.points[j].x, a.points[j].x, 1e-6f)
                assertEquals("$label stroke[$i].point[$j].y", e.points[j].y, a.points[j].y, 1e-6f)
                assertEquals("$label stroke[$i].point[$j].pressure", e.points[j].pressure, a.points[j].pressure, 1e-6f)
                assertEquals("$label stroke[$i].point[$j].timestamp", e.points[j].timestamp, a.points[j].timestamp)
            }
        }
        assertEquals("$label textBlocks", expected.textBlocks, actual.textBlocks)
        assertEquals("$label diagramAreas", expected.diagramAreas, actual.diagramAreas)
    }
}
