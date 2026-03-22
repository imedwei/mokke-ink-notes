package com.writer.recognition

import android.util.Log
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
 * Two capture modes:
 *
 * **Single line** (original) — captures one line for recognition testing:
 * ```
 * adb shell "am instrument -w -e class com.writer.recognition.StrokeFixtureCapture#captureFixture \
 *   -e fixtureName hello_test -e expectedText 'hello test' \
 *   com.writer.dev.test/androidx.test.runner.AndroidJUnitRunner"
 * ```
 *
 * **Full document** — captures all strokes, types, and diagram areas for integration testing:
 * ```
 * adb shell "am instrument -w -e class com.writer.recognition.StrokeFixtureCapture#captureDocument \
 *   -e fixtureName my_diagram \
 *   com.writer.dev.test/androidx.test.runner.AndroidJUnitRunner"
 * ```
 */
@RunWith(AndroidJUnit4::class)
@DevTool
class StrokeFixtureCapture {

    @Test
    fun captureFixture() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureName = args.getString("fixtureName")
        val expectedText = args.getString("expectedText")
        assumeTrue("Skipped — run via captureFixture Gradle task", fixtureName != null && expectedText != null)
        fixtureName!!
        expectedText!!
        require(!fixtureName.contains("..") && !fixtureName.contains('/') && !fixtureName.contains('\\')) {
            "fixtureName must not contain path separators or '..': $fixtureName"
        }
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
        if (!outDir.mkdirs() && !outDir.isDirectory) {
            Log.w("StrokeFixtureCapture", "Failed to create fixture output dir: $outDir")
        }
        val outFile = File(outDir, "$fixtureName.json")
        outFile.writeText(json.toString(2))
    }

    /**
     * Capture the full document: all strokes with types, diagram areas,
     * and recognized text cache. Suitable for integration tests that
     * exercise line segmentation, scratch-out, and recognition flows.
     */
    @Test
    fun captureDocument() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureName = args.getString("fixtureName")
        assumeTrue("Skipped — run via adb instrument with fixtureName", fixtureName != null)
        fixtureName!!
        require(!fixtureName.contains("..") && !fixtureName.contains('/') && !fixtureName.contains('\\')) {
            "fixtureName must not contain path separators or '..': $fixtureName"
        }
        val documentName = args.getString("documentName")
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val docName = documentName ?: run {
            val docs = DocumentStorage.listDocuments(context)
            require(docs.isNotEmpty()) { "No documents found on device" }
            docs.first().name
        }
        val data = requireNotNull(DocumentStorage.load(context, docName)) {
            "Failed to load document: $docName"
        }
        require(data.strokes.isNotEmpty()) { "Document has no strokes" }

        val segmenter = LineSegmenter()

        val lineGroups = segmenter.groupByLine(data.strokes)

        val json = JSONObject().apply {
            put("documentName", docName)
            put("lineSpacing", com.writer.view.ScreenMetrics.lineSpacing.toDouble())
            put("topMargin", com.writer.view.ScreenMetrics.topMargin.toDouble())
            put("density", com.writer.view.ScreenMetrics.density.toDouble())

            // All strokes with full metadata
            put("strokes", JSONArray().apply {
                for (stroke in data.strokes) {
                    val downsampled = StrokeDownsampler.downsample(stroke)
                    put(JSONObject().apply {
                        put("strokeId", stroke.strokeId)
                        put("strokeType", stroke.strokeType.name)
                        put("isGeometric", stroke.isGeometric)
                        put("lineIndex", segmenter.getStrokeLineIndex(stroke))
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

            // Diagram areas
            put("diagramAreas", JSONArray().apply {
                for (area in data.diagramAreas) {
                    put(JSONObject().apply {
                        put("startLineIndex", area.startLineIndex)
                        put("heightInLines", area.heightInLines)
                    })
                }
            })

            // Recognized text cache
            put("lineTextCache", JSONObject().apply {
                for ((lineIdx, text) in data.lineTextCache) {
                    put(lineIdx.toString(), text)
                }
            })

            // Line grouping summary
            put("lineGroups", JSONObject().apply {
                for ((lineIdx, strokes) in lineGroups.toSortedMap()) {
                    put(lineIdx.toString(), JSONArray().apply {
                        for (s in strokes) put(s.strokeId)
                    })
                }
            })
        }

        val outDir = File("/sdcard/Download/inkup-fixtures")
        if (!outDir.mkdirs() && !outDir.isDirectory) {
            Log.w("StrokeFixtureCapture", "Failed to create fixture output dir: $outDir")
        }
        val outFile = File(outDir, "$fixtureName.json")
        outFile.writeText(json.toString(2))
        Log.i("StrokeFixtureCapture", "Captured ${data.strokes.size} strokes, " +
            "${data.diagramAreas.size} diagram areas, " +
            "${lineGroups.size} lines → $outFile")
    }
}
