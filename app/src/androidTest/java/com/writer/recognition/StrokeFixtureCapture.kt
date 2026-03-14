package com.writer.recognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.writer.model.StrokePoint
import com.writer.storage.DocumentStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test that captures handwriting from a document on device
 * and writes a downsampled JSON fixture to /sdcard/Download/inkup-fixtures/.
 *
 * Run via the captureFixture Gradle task, or directly:
 * adb shell am instrument -w \
 *   -e class com.writer.recognition.StrokeFixtureCapture \
 *   -e fixtureName hello_test \
 *   -e expectedText "hello test" \
 *   com.writer.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class StrokeFixtureCapture {

    @Test
    fun captureFixture() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureName = args.getString("fixtureName")
        val expectedText = args.getString("expectedText")
        assumeTrue("Skipped — run via captureFixture Gradle task", fixtureName != null && expectedText != null)
        fixtureName!!
        expectedText!!
        val language = args.getString("language") ?: "en-US"
        val lineIndex = args.getString("lineIndex")?.toIntOrNull() ?: 0
        val documentName = args.getString("documentName")

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Load the most recent document, or a named one
        val docName = documentName ?: run {
            val docs = DocumentStorage.listDocuments(context)
            require(docs.isNotEmpty()) { "No documents found on device" }
            docs.first().name
        }
        val data = requireNotNull(DocumentStorage.load(context, docName)) {
            "Failed to load document: $docName"
        }
        require(data.strokes.isNotEmpty()) { "Document has no strokes" }

        // Segment strokes by line
        val segmenter = LineSegmenter()
        val lineStrokes = segmenter.getStrokesForLine(data.strokes, lineIndex)
        require(lineStrokes.isNotEmpty()) {
            "No strokes found on line $lineIndex. " +
                    "Available lines: ${segmenter.groupByLine(data.strokes).keys.sorted()}"
        }

        // Build fixture JSON with downsampled strokes
        val json = JSONObject().apply {
            put("expectedText", expectedText)
            put("language", language)
            put("strokes", JSONArray().apply {
                for ((i, stroke) in lineStrokes.sortedBy { it.points.first().x }.withIndex()) {
                    val downsampled = StrokeDownsampler.downsample(stroke)
                    put(JSONObject().apply {
                        put("strokeId", "s$i")
                        put("points", JSONArray().apply {
                            for (pt in downsampled.points) {
                                put(JSONObject().apply {
                                    put("x", pt.x.toDouble())
                                    put("y", pt.y.toDouble())
                                    put("pressure", pt.pressure.toDouble())
                                    put("timestamp", pt.timestamp)
                                })
                            }
                        })
                    })
                }
            })
        }

        // Write to /sdcard/Download/inkup-fixtures/
        val outDir = File("/sdcard/Download/inkup-fixtures")
        outDir.mkdirs()
        val outFile = File(outDir, "$fixtureName.json")
        outFile.writeText(json.toString(2))
    }
}
