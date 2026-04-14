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
        ScreenMetrics.init(density = 1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        val version = System.getProperty("goldenVersion") ?: return
        val baseDir = System.getProperty("goldenOutputDir")
            ?: "app/src/test/resources/golden"
        val dir = java.io.File(baseDir, "automerge").also { it.mkdirs() }

        when (version) {
            "v2" -> {
                val data = AutomergeGoldenFileGenerator.buildV2Document()
                val doc = AutomergeAdapter.toAutomerge(data)
                java.io.File(dir, "document_v2.automerge").writeBytes(doc.save())
                doc.free()
                println("Generated automerge/document_v2.automerge")
            }
        }
    }
}

/** Builders for each Automerge schema version's golden data. */
object AutomergeGoldenFileGenerator {

    // Use fixed metrics matching Go 7 device for reproducible golden data
    private const val LS = 94f  // lineSpacing at density=1.875
    private const val TM = 56f  // topMargin at density=1.875
    private fun lineY(line: Int, offset: Float = 0f) = TM + line * LS + offset

    fun buildV2Document(): DocumentData = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke(
                    strokeId = "golden-s1",
                    points = listOf(
                        StrokePoint(100f, lineY(2, 10f), 0.5f, 1000L),
                        StrokePoint(130f, lineY(2, 40f), 0.8f, 2000L),
                        StrokePoint(160f, lineY(2, 60f), 0.3f, 3000L),
                    ),
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND,
                ),
                InkStroke(
                    strokeId = "golden-s2",
                    points = listOf(
                        StrokePoint(200f, lineY(4, 20f), 0.7f, 4000L),
                        StrokePoint(350f, lineY(4, 50f), 0.9f, 5000L),
                    ),
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
                    points = listOf(StrokePoint(100f, lineY(1, 10f), 0.5f, 100L)),
                ),
            ),
        ),
        audioRecordings = listOf(
            AudioRecording("rec-golden.opus", 1000L, 5000L),
        ),
    )
}
