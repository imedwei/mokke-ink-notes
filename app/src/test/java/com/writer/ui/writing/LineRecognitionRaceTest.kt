package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.recognition.TextRecognizer
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the race condition where eagerRecognizeLine fires while
 * a previous recognition is still in-flight, leading to stale partial results.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class LineRecognitionRaceTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var columnModel: ColumnModel
    private lateinit var lineSegmenter: LineSegmenter
    private lateinit var host: RecognitionManagerHost
    private val lineTextCache = mutableMapOf<Int, String>()

    private fun stroke(lineIndex: Int, startX: Float, endX: Float, id: String): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.5f
        return InkStroke(
            strokeId = id,
            points = listOf(
                StrokePoint(startX, y - 3f, 0.5f, 0L),
                StrokePoint((startX + endX) / 2f, y + 3f, 0.5f, 50L),
                StrokePoint(endX, y - 1f, 0.5f, 100L)
            )
        )
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        columnModel = ColumnModel()
        lineSegmenter = LineSegmenter()
        lineTextCache.clear()

        host = object : RecognitionManagerHost {
            override val lineTextCache: MutableMap<Int, String> get() = this@LineRecognitionRaceTest.lineTextCache
            override val userRenamed: Boolean = false
            override val everHiddenLines: Set<Int> = emptySet()
            override fun onHeadingDetected(heading: String) {}
            override fun isDiagramLine(lineIndex: Int) = false
            override fun onRecognitionComplete(lineIndex: Int) {}
        }
    }

    @Test
    fun `eagerRecognize queues re-recognition when recognition is in-flight`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var callCount = 0
        val recognizer = object : TextRecognizer {
            override suspend fun initialize(languageTag: String) {}
            override suspend fun recognizeLine(line: InkLine, preContext: String): String {
                callCount++
                if (callCount == 1) {
                    gate.await()
                    return "Th gilded"
                }
                return "The gilded monster is coming"
            }
            override fun close() {}
        }

        val manager = LineRecognitionManager(
            columnModel, recognizer, lineSegmenter,
            StrokeClassifier(lineSegmenter),
            CoroutineScope(Dispatchers.Unconfined),
            host, canvasWidthProvider = { 1264f }
        )

        // Add partial strokes
        columnModel.activeStrokes.addAll(listOf(
            stroke(0, 10f, 80f, "s1"),
            stroke(0, 100f, 200f, "s2"),
        ))

        // First eagerRecognize — starts recognition, blocks at gate
        manager.eagerRecognizeLine(0)
        assertTrue("Line 0 should be in recognizingLines", manager.recognizingLines.contains(0))

        // User writes more strokes
        columnModel.activeStrokes.addAll(listOf(
            stroke(0, 220f, 350f, "s3"),
            stroke(0, 370f, 500f, "s4"),
        ))
        lineTextCache.remove(0)

        // Second eagerRecognize (line change) — should queue, not skip silently
        manager.eagerRecognizeLine(0)
        assertTrue("Line 0 should be queued for re-recognition",
            manager.pendingRerecognize.contains(0))

        // Release first recognition — should trigger re-recognition
        gate.complete(Unit)

        // Give coroutines time to complete (Unconfined resumes inline)
        kotlinx.coroutines.delay(100)

        assertEquals("The gilded monster is coming", lineTextCache[0])
        assertEquals(2, callCount)
    }

    @Test
    fun `eagerRecognize without race returns result`() = runBlocking {
        val recognizer = object : TextRecognizer {
            override suspend fun initialize(languageTag: String) {}
            override suspend fun recognizeLine(line: InkLine, preContext: String) = "Hello World"
            override fun close() {}
        }

        val manager = LineRecognitionManager(
            columnModel, recognizer, lineSegmenter,
            StrokeClassifier(lineSegmenter),
            CoroutineScope(Dispatchers.Unconfined),
            host, canvasWidthProvider = { 1264f }
        )

        columnModel.activeStrokes.add(stroke(0, 10f, 100f, "s1"))
        manager.eagerRecognizeLine(0)
        kotlinx.coroutines.delay(100)

        assertEquals("Hello World", lineTextCache[0])
        assertTrue(manager.pendingRerecognize.isEmpty())
    }

    @Test
    fun `cached result prevents re-recognition`() = runBlocking {
        var callCount = 0
        val recognizer = object : TextRecognizer {
            override suspend fun initialize(languageTag: String) {}
            override suspend fun recognizeLine(line: InkLine, preContext: String): String {
                callCount++
                return "Hello"
            }
            override fun close() {}
        }

        val manager = LineRecognitionManager(
            columnModel, recognizer, lineSegmenter,
            StrokeClassifier(lineSegmenter),
            CoroutineScope(Dispatchers.Unconfined),
            host, canvasWidthProvider = { 1264f }
        )

        columnModel.activeStrokes.add(stroke(0, 10f, 100f, "s1"))

        manager.eagerRecognizeLine(0)
        kotlinx.coroutines.delay(100)
        assertEquals(1, callCount)

        // Second call — skips because cached
        manager.eagerRecognizeLine(0)
        kotlinx.coroutines.delay(100)
        assertEquals("Should not re-recognize when cached", 1, callCount)
    }

    @Test
    fun `multiple queued re-recognitions collapse to one`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var callCount = 0
        val recognizer = object : TextRecognizer {
            override suspend fun initialize(languageTag: String) {}
            override suspend fun recognizeLine(line: InkLine, preContext: String): String {
                callCount++
                if (callCount == 1) {
                    gate.await()
                    return "partial"
                }
                return "full text here"
            }
            override fun close() {}
        }

        val manager = LineRecognitionManager(
            columnModel, recognizer, lineSegmenter,
            StrokeClassifier(lineSegmenter),
            CoroutineScope(Dispatchers.Unconfined),
            host, canvasWidthProvider = { 1264f }
        )

        columnModel.activeStrokes.add(stroke(0, 10f, 100f, "s1"))

        // Start first recognition (blocks at gate)
        manager.eagerRecognizeLine(0)

        // Queue multiple re-recognitions (user writing rapidly)
        lineTextCache.remove(0)
        manager.eagerRecognizeLine(0)
        lineTextCache.remove(0)
        manager.eagerRecognizeLine(0)
        lineTextCache.remove(0)
        manager.eagerRecognizeLine(0)

        assertEquals("Pending set should deduplicate", 1, manager.pendingRerecognize.size)

        gate.complete(Unit)
        kotlinx.coroutines.delay(100)

        assertEquals("full text here", lineTextCache[0])
        assertEquals("1 initial + 1 re-recognition", 2, callCount)
    }
}
