package com.writer.storage

import com.writer.model.AnchorMode
import com.writer.model.AnchorTarget
import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.TextBlock
import com.writer.model.proto.DocumentProto
import com.writer.view.ScreenMetrics
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Generates protobuf golden files for backward-compatibility testing.
 *
 * Run via: ./gradlew generateGoldenFile -PgoldenVersion=v1
 *
 * When the proto schema changes, add a new version here and run
 * the task to generate the corresponding .inkup golden file.
 */
class GoldenFileGeneratorRunner {

    @Test
    fun generate() {
        // Only runs when invoked with -PgoldenVersion=v1, skipped during normal test runs
        Assume.assumeTrue("Skipped: pass -PgoldenVersion to generate", System.getProperty("goldenVersion") != null)
        val version = System.getProperty("goldenVersion")!!
        val outputDir = System.getProperty("goldenOutputDir", "app/src/test/resources/golden")

        val bytes = when (version) {
            "v1" -> GoldenFileGenerator.buildV1Document().toProto().encode()
            "v2" -> GoldenFileGenerator.buildV2Proto().encode()
            "v5" -> GoldenFileGenerator.buildV5Proto().encode()
            "v6" -> GoldenFileGenerator.buildV6Proto().encode()
            // v3 and v4 golden files are fixed on disk — do not regenerate.
            // The else branch uses the latest schema (currently v6).
            else -> GoldenFileGenerator.buildCurrentProto().encode()
        }
        val file = File(outputDir, "document_$version.inkup")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        println("Generated golden file: ${file.absolutePath} (${bytes.size} bytes)")
    }
}

object GoldenFileGenerator {

    /**
     * v2: coordinate_system = 1 (normalized line-units).
     * Coordinates are stored as multiples of line spacing.
     * Built directly as proto since the mapper normalization is added separately.
     */
    fun buildV2Proto(): DocumentProto {
        // Init ScreenMetrics so toProto() can normalize coordinates.
        // Use Go 7 as reference device (small screen, 41dp = 77px line spacing).
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        val domain = buildV1Document()
        // toProto() normalizes coordinates and sets coordinate_system = 1
        return domain.toProto()
    }

    // v3 golden file generated at commit e9ba540. Do not regenerate — the on-disk
    // document_v3.inkup is the backward-compatibility contract.

    /**
     * v5: adds TextBlocks and AudioRecordings to the document.
     */
    fun buildV5Proto(): DocumentProto {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        return buildV5Document().toProto()
    }

    /**
     * v6: adds the transcript column + per-TextBlock anchor metadata
     * (anchor_line_index / anchor_target / anchor_mode).
     */
    fun buildV6Proto(): DocumentProto {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        return buildV6Document().toProto()
    }

    /**
     * Build a golden proto using the current schema. When adding a new schema
     * version, just update the proto definition — this generator always uses
     * the latest toProto() serialization.
     */
    fun buildCurrentProto(): DocumentProto {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        return buildV6Document().toProto()
    }

    fun buildV1Document() = DocumentData(
        main = ColumnData(
            strokes = listOf(
                InkStroke(
                    strokeId = "proto-stroke-1",
                    strokeWidth = 3f,
                    points = listOf(
                        StrokePoint(10f, 20f, 0.5f, 1000L),
                        StrokePoint(30f, 40f, 0.8f, 2000L)
                    ),
                    strokeType = StrokeType.FREEHAND
                ),
                InkStroke(
                    strokeId = "proto-stroke-2",
                    strokeWidth = 4f,
                    points = listOf(
                        StrokePoint(50f, 50f, 1f, 3000L),
                        StrokePoint(150f, 150f, 1f, 4000L)
                    ),
                    strokeType = StrokeType.ELLIPSE,
                    isGeometric = true
                ),
                InkStroke(
                    strokeId = "proto-stroke-3",
                    strokeWidth = 2f,
                    points = listOf(
                        StrokePoint(0f, 0f, 1f, 5000L),
                        StrokePoint(100f, 0f, 1f, 6000L)
                    ),
                    strokeType = StrokeType.ELBOW_ARROW_HEAD,
                    isGeometric = true
                )
            ),
            lineTextCache = mapOf(0 to "proto hello", 1 to "proto world"),
            everHiddenLines = setOf(0, 2),
            diagramAreas = listOf(
                DiagramArea(id = "proto-area", startLineIndex = 3, heightInLines = 4)
            )
        ),
        cue = ColumnData(
            strokes = listOf(
                InkStroke(
                    strokeId = "cue-proto-1",
                    strokeWidth = 3f,
                    points = listOf(StrokePoint(1f, 2f, 0.5f, 7000L))
                )
            ),
            lineTextCache = mapOf(0 to "cue entry")
        ),
        scrollOffsetY = 75.5f,
        highestLineIndex = 7,
        currentLineIndex = 5,
        userRenamed = true
    )

    fun buildV5Document(): DocumentData {
        val v1 = buildV1Document()
        return v1.copy(
            main = v1.main.copy(
                textBlocks = listOf(
                    TextBlock(
                        id = "proto-text-block-1",
                        startLineIndex = 10,
                        heightInLines = 2,
                        text = "transcribed lecture text",
                        audioFile = "rec-001.opus",
                        audioStartMs = 5000L,
                        audioEndMs = 15000L
                    ),
                    TextBlock(
                        id = "proto-text-block-2",
                        startLineIndex = 15,
                        heightInLines = 1,
                        text = "quick voice memo"
                    )
                )
            ),
            audioRecordings = listOf(
                AudioRecording(
                    audioFile = "rec-001.opus",
                    startTimeMs = 1700000000000L,
                    durationMs = 60000L
                )
            )
        )
    }

    /**
     * v6: TextBlocks live in the transcript column with anchor metadata stamped.
     * The main/cue columns carry strokes only. Written natively in the v6 shape
     * (not via the legacy migration path) so the golden encodes the steady-state
     * contract a fresh v6 save produces.
     */
    fun buildV6Document(): DocumentData {
        val v1 = buildV1Document()
        return v1.copy(
            transcript = ColumnData(
                textBlocks = listOf(
                    TextBlock(
                        id = "proto-text-block-1",
                        startLineIndex = 10,
                        heightInLines = 2,
                        text = "transcribed lecture text",
                        audioFile = "rec-001.opus",
                        audioStartMs = 5000L,
                        audioEndMs = 15000L,
                        anchorTarget = AnchorTarget.MAIN,
                        anchorLineIndex = 10,
                        anchorMode = AnchorMode.AUTO,
                    ),
                    TextBlock(
                        id = "proto-text-block-2",
                        startLineIndex = 15,
                        heightInLines = 1,
                        text = "quick voice memo",
                        anchorTarget = AnchorTarget.CUE,
                        anchorLineIndex = 15,
                        anchorMode = AnchorMode.MANUAL,
                    ),
                )
            ),
            audioRecordings = listOf(
                AudioRecording(
                    audioFile = "rec-001.opus",
                    startTimeMs = 1700000000000L,
                    durationMs = 60000L
                )
            )
        )
    }
}
