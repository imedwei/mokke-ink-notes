package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.proto.DocumentProto
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
            else -> error("Unknown golden file version: $version")
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
        val domain = buildV1Document()
        // Use v1 data but set coordinate_system = 1.
        // In a real v2 file, the x/y values would be normalized (divided by LINE_SPACING).
        // For the golden file, we store specific normalized values and verify them on load.
        return domain.toProto().copy(coordinate_system = 1)
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
}
