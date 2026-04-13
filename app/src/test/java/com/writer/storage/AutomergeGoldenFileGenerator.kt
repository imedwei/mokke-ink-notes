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
import org.junit.Test

/**
 * Generates Automerge golden binary files for backward-compatibility testing.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.AutomergeGoldenFileGeneratorRunner" -PgoldenVersion=v1
 *
 * NEVER modify or delete existing golden files — they are the permanent
 * backward-compatibility contract.
 */
class AutomergeGoldenFileGeneratorRunner {

    @Test
    fun generate() {
        val version = System.getProperty("goldenVersion") ?: return
        val baseDir = System.getProperty("goldenOutputDir")
            ?: "app/src/test/resources/golden"
        val dir = java.io.File(baseDir, "automerge").also { it.mkdirs() }

        when (version) {
            "v1" -> {
                val data = AutomergeGoldenFileGenerator.buildV1Document()
                val doc = AutomergeAdapter.toAutomerge(data)
                java.io.File(dir, "document_v1.automerge").writeBytes(doc.save())
                doc.free()
                println("Generated automerge-golden/document_v1.automerge")
            }
        }
    }
}

/** Builders for each Automerge schema version's golden data. */
object AutomergeGoldenFileGenerator {

    fun buildV1Document(): DocumentData = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke(
                    strokeId = "golden-s1",
                    points = listOf(
                        StrokePoint(10f, 20f, 0.5f, 1000L),
                        StrokePoint(30f, 40f, 0.8f, 2000L),
                        StrokePoint(50f, 60f, 0.3f, 3000L),
                    ),
                    strokeWidth = 3f,
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND,
                ),
                InkStroke(
                    strokeId = "golden-s2",
                    points = listOf(
                        StrokePoint(100f, 200f, 0.7f, 4000L),
                        StrokePoint(300f, 400f, 0.9f, 5000L),
                    ),
                    strokeWidth = 5f,
                    isGeometric = true,
                    strokeType = StrokeType.RECTANGLE,
                ),
            ),
            textBlocks = listOf(
                TextBlock(
                    id = "golden-tb1",
                    startLineIndex = 2,
                    heightInLines = 1,
                    text = "Golden test memo",
                    audioFile = "rec-golden.opus",
                    audioStartMs = 100,
                    audioEndMs = 5000,
                    words = listOf(
                        WordInfo("Golden", 0.95f, 100, 300),
                        WordInfo("test", 0.88f, 400, 600),
                        WordInfo("memo", 0.92f, 700, 900),
                    ),
                ),
            ),
            diagramAreas = listOf(
                DiagramArea(id = "golden-d1", startLineIndex = 5, heightInLines = 3),
            ),
        ),
        cue = ColumnData(
            strokes = listOf(
                InkStroke(
                    strokeId = "golden-cue-s1",
                    points = listOf(StrokePoint(1f, 2f, 0.5f, 100L)),
                    strokeWidth = 2f,
                ),
            ),
        ),
        audioRecordings = listOf(
            AudioRecording("rec-golden.opus", 1000L, 5000L),
        ),
    )
}
