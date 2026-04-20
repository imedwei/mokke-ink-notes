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
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutomergeAdapterTest {

    private val ls get() = ScreenMetrics.lineSpacing
    private val tm get() = ScreenMetrics.topMargin
    private fun lineY(line: Int, offset: Float = 0f) = tm + line * ls + offset

    @Before
    fun setUp() {
        ScreenMetrics.init(density = 1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

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
                            StrokePoint(100f, lineY(2, 10f), 0.5f, 1000L),
                            StrokePoint(130f, lineY(2, 40f), 0.8f, 2000L),
                        ),
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
        // Post-v6: TextBlocks are owned by the transcript column.
        val data = DocumentData(
            main = ColumnData(),
            transcript = ColumnData(
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
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke(
                        strokeId = "precise",
                        points = listOf(
                            StrokePoint(123.456f, tm + 2 * ls + 45.678f, 0.123f, 999999999L),
                        ),
                    )
                )
            )
        )
        val result = roundTrip(data)
        val originalPoint = data.main.strokes[0].points[0]
        val resultPoint = result.main.strokes[0].points[0]
        // Packed format has 0.01 line-height precision (~1px)
        val coordTol = ls * 0.015f
        assertEquals(originalPoint.x.toDouble(), resultPoint.x.toDouble(), coordTol.toDouble())
        assertEquals(originalPoint.y.toDouble(), resultPoint.y.toDouble(), coordTol.toDouble())
        assertEquals(originalPoint.pressure.toDouble(), resultPoint.pressure.toDouble(), 0.01)
        assertEquals(originalPoint.timestamp, resultPoint.timestamp)
    }

    @Test
    fun `roundTrip withCueColumn`() {
        // Cue-anchored TextBlocks live in transcript with anchorTarget = CUE.
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke("main-s1", listOf(StrokePoint(100f, lineY(1, 10f), 0.5f, 100L)))
                )
            ),
            cue = ColumnData(
                strokes = listOf(
                    InkStroke("cue-s1", listOf(StrokePoint(150f, lineY(2, 15f), 0.7f, 200L)))
                ),
            ),
            transcript = ColumnData(
                textBlocks = listOf(
                    TextBlock(
                        id = "cue-tb1",
                        startLineIndex = 0,
                        heightInLines = 1,
                        text = "cue text",
                        anchorTarget = com.writer.model.AnchorTarget.CUE,
                        anchorLineIndex = 0,
                    )
                ),
            ),
        )
        val result = roundTrip(data)
        assertDocumentEquals(data, result)
    }

    @Test
    fun `roundTrip allStrokeTypes`() {
        val strokes = StrokeType.entries.mapIndexed { i, type ->
            InkStroke(
                strokeId = "stroke-$i",
                points = listOf(StrokePoint(100f + i * 10f, lineY(i, 10f), 0.5f, i * 1000L)),
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
                    InkStroke("s1", listOf(StrokePoint(100f, lineY(1, 10f), 0.5f, 100L)))
                )
            )
        )
        val doc = AutomergeAdapter.toAutomerge(data)

        // Add another stroke by converting updated data
        val updated = data.copy(
            main = data.main.copy(
                strokes = data.main.strokes + InkStroke(
                    "s2", listOf(StrokePoint(200f, lineY(3, 20f), 0.6f, 200L))
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
                        StrokePoint(100f, lineY(2, 10f), 0.5f, 1000L),
                        StrokePoint(130f, lineY(2, 40f), 0.8f, 2000L),
                    ),
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND,
                ),
                InkStroke(
                    strokeId = "s2",
                    points = listOf(
                        StrokePoint(200f, lineY(4, 20f), 0.3f, 3000L),
                    ),
                    isGeometric = true,
                    strokeType = StrokeType.RECTANGLE,
                ),
            ),
            diagramAreas = listOf(
                DiagramArea(id = "d1", startLineIndex = 5, heightInLines = 3)
            ),
        ),
        cue = ColumnData(
            strokes = listOf(
                InkStroke("cue-s1", listOf(StrokePoint(100f, lineY(1, 10f), 0.5f, 100L)))
            )
        ),
        transcript = ColumnData(
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
                    anchorTarget = com.writer.model.AnchorTarget.MAIN,
                    anchorLineIndex = 2,
                    anchorMode = com.writer.model.AnchorMode.AUTO,
                )
            ),
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
        assertColumnEquals("transcript", expected.transcript, actual.transcript)
        assertEquals("audioRecordings", expected.audioRecordings, actual.audioRecordings)
    }

    private fun assertColumnEquals(label: String, expected: ColumnData, actual: ColumnData) {
        // Coordinate tolerance: 0.01 line-height units × lineSpacing (~94px) ≈ 1px
        val coordTol = ScreenMetrics.lineSpacing * 0.015f
        assertEquals("$label strokes size", expected.strokes.size, actual.strokes.size)
        for (i in expected.strokes.indices) {
            val e = expected.strokes[i]
            val a = actual.strokes[i]
            assertEquals("$label stroke[$i].strokeId", e.strokeId, a.strokeId)
            assertEquals("$label stroke[$i].strokeType", e.strokeType, a.strokeType)
            assertEquals("$label stroke[$i].isGeometric", e.isGeometric, a.isGeometric)
            assertEquals("$label stroke[$i].points.size", e.points.size, a.points.size)
            for (j in e.points.indices) {
                assertEquals("$label stroke[$i].point[$j].x", e.points[j].x, a.points[j].x, coordTol)
                assertEquals("$label stroke[$i].point[$j].y", e.points[j].y, a.points[j].y, coordTol)
                assertEquals("$label stroke[$i].point[$j].pressure", e.points[j].pressure, a.points[j].pressure, 0.01f)
                assertEquals("$label stroke[$i].point[$j].timestamp", e.points[j].timestamp, a.points[j].timestamp)
            }
        }
        assertEquals("$label textBlocks", expected.textBlocks, actual.textBlocks)
        assertEquals("$label diagramAreas", expected.diagramAreas, actual.diagramAreas)
    }
}
