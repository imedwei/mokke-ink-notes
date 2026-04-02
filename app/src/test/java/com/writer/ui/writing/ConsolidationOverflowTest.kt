package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.recognition.RecognitionResult
import com.writer.recognition.StrokeClassifier
import com.writer.storage.toDomain
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests that consolidated Hershey text does not overlap with handwritten
 * strokes when word-wrapping causes the text to take more lines than
 * the original handwriting.
 *
 * When overflow occurs, the canvas shifts non-consolidated strokes down
 * by the overflow amount + 1 gap line, so all consolidated text remains
 * visible and the handwritten strokes are not hidden.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class ConsolidationOverflowTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var column: ColumnModel
    private lateinit var textCache: MutableMap<Int, String>
    private lateinit var lineSegmenter: LineSegmenter
    private lateinit var dm: DisplayManager
    private lateinit var font: HersheyFont
    private lateinit var canvas: HandwritingCanvasView

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
        column = ColumnModel()
        textCache = mutableMapOf()
        lineSegmenter = LineSegmenter()

        val ctx = RuntimeEnvironment.getApplication()
        val rawLines = ctx.assets.open("scripts.jhf").bufferedReader().readLines()
        font = HersheyFont(rawLines)
    }

    private fun createDm(host: DisplayManagerHost, width: Int = 824, height: Int = 1648): DisplayManager {
        canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        // Force a layout so the canvas has width for word-wrapping calculations
        canvas.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        canvas.layout(0, 0, width, height)
        return DisplayManager(
            canvas,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            lineSegmenter, ParagraphBuilder(StrokeClassifier(lineSegmenter)), host, font
        )
    }

    @Test
    fun `overflow shifts raw strokes down and renders all text`() {
        // Two original lines with enough text to wrap to 3+ Hershey lines.
        // User is writing on line 2. Word-wrap overflows to line 2+.
        val longLine0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val longLine1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        textCache[0] = longLine0
        textCache[1] = longLine1

        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 200f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        dm = createDm(host)
        dm.updateInlineOverlays(2)

        // All text should be present in consolidated overlays
        val fullText = "$longLine0 $longLine1"
        val consolidatedText = canvas.inlineTextOverlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(" ") { it.value.recognizedText }
        assertEquals("All text should be present", fullText, consolidatedText)

        // Raw strokes at line 2 should NOT be hidden (not in consolidatedLineIndices)
        assertFalse("Line 2 should not hide raw strokes",
            canvas.consolidatedLineIndices.contains(2))

        // Overflow shift should be positive to push raw strokes below Hershey text
        assertTrue("Shift should be positive: ${canvas.consolidationOverflowShiftPx}",
            canvas.consolidationOverflowShiftPx > 0f)
    }

    @Test
    fun `current writing line is not consolidated even with cached text`() {
        // User wrote on lines 0-2, idle recognized all three, still on line 2.
        // Line 2 must NOT be consolidated — the user is still writing on it.
        textCache[0] = "Hello world"
        textCache[1] = "Goodbye moon"
        textCache[2] = "Still writing"

        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 200f, "s2"))

        val host = TestHost(column, textCache, currentLine = 2)
        dm = createDm(host)

        val overlays = dm.buildInlineOverlays(2)

        assertTrue("Line 0 should be consolidated", overlays[0]?.consolidated == true)
        assertTrue("Line 1 should be consolidated", overlays[1]?.consolidated == true)
        assertFalse("Line 2 (currentLine) must NOT be consolidated",
            overlays[2]?.consolidated == true && overlays[2]?.unConsolidated != true)
    }

    @Test
    fun `pen down advances currentLineIndex and consolidates previous line`() {
        // User finished writing on line 2 (cached). On pen-down, currentLineIndex
        // should advance so line 2 consolidates.
        textCache[0] = "Hello world"
        textCache[1] = "Goodbye moon"
        textCache[2] = "Done writing"

        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 200f, "s2"))

        // Simulate pen-down: advance currentLineIndex because line 2 has cached text
        val advancedLine = 3  // highestLineIndex (2) + 1
        val host = TestHost(column, textCache, currentLine = advancedLine)
        dm = createDm(host)

        val overlays = dm.buildInlineOverlays(advancedLine)

        assertTrue("Line 0 should be consolidated", overlays[0]?.consolidated == true)
        assertTrue("Line 1 should be consolidated", overlays[1]?.consolidated == true)
        assertTrue("Line 2 should now be consolidated after pen-down advancement",
            overlays[2]?.consolidated == true)
    }

    @Test
    fun `short text has no overflow shift`() {
        textCache[0] = "Hello world"
        textCache[1] = "Goodbye moon"

        column.activeStrokes.add(stroke(0, 10f, 200f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 200f, "s1"))
        column.activeStrokes.add(stroke(2, 10f, 200f, "s2_writing"))

        val host = TestHost(column, textCache, currentLine = 2)
        dm = createDm(host)
        dm.updateInlineOverlays(2)

        assertEquals("No overflow shift for short text",
            0f, canvas.consolidationOverflowShiftPx, 0.01f)
    }

    @Test
    fun `no overflow shift when plenty of gap before current line`() {
        val longLine0 = "The gilded monster went to the magnificent grand ball and danced all night long with great joy"
        val longLine1 = "and the elegant princess joined in until the ancient clock tower struck twelve midnight"
        textCache[0] = longLine0
        textCache[1] = longLine1

        column.activeStrokes.add(stroke(0, 10f, 700f, "s0"))
        column.activeStrokes.add(stroke(1, 10f, 700f, "s1"))
        column.activeStrokes.add(stroke(20, 10f, 200f, "s20_writing"))

        val host = TestHost(column, textCache, currentLine = 20)
        dm = createDm(host)
        dm.updateInlineOverlays(20)

        assertEquals("No overflow shift when gap is large",
            0f, canvas.consolidationOverflowShiftPx, 0.01f)
    }

    // ── Real document regression test ─────────────────────────────────────

    @Test
    fun `real document - consolidated text does not hide last line`() {
        // Load the actual document that triggered the bug
        val bytes = javaClass.classLoader!!.getResourceAsStream("the_gilded_monster_overflow.inkup")!!.readBytes()
        val proto = com.writer.model.proto.DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        column = ColumnModel()
        column.activeStrokes.addAll(data.main.strokes)
        textCache = data.main.lineTextCache.toMutableMap()

        // Simulate pen-down advancement: if current line has cached text,
        // advance past it (same logic as WritingCoordinator.onPenDown).
        val advancedCurrentLine = if (textCache.containsKey(data.currentLineIndex))
            data.highestLineIndex + 1 else data.currentLineIndex
        val host = TestHost(column, textCache, currentLine = advancedCurrentLine)
        dm = createDm(host)

        // Log what we loaded for debugging
        val fullText = textCache.entries.sortedBy { it.key }.joinToString(" | ") { "${it.key}:'${it.value}'" }

        // Build overlays — consolidated mode (after pen-down advancement)
        dm.updateInlineOverlays(advancedCurrentLine)
        val overlays = canvas.inlineTextOverlays
        val consolidatedKeys = overlays.filter { it.value.consolidated && !it.value.unConsolidated }.keys.sorted()

        // All original text should appear in consolidated overlays
        val allOriginalText = textCache.entries
            .filter { it.key < advancedCurrentLine }
            .sortedBy { it.key }
            .joinToString(" ") { it.value }
        val allConsolidatedText = overlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(" ") { it.value.recognizedText }

        assertEquals(
            "All original text should be present in consolidated overlays.\n" +
            "Original lines: $fullText\nConsolidated keys: $consolidatedKeys",
            allOriginalText, allConsolidatedText
        )

        // Verify overflow behavior
        val maxConsolidated = consolidatedKeys.maxOrNull() ?: -1
        // Log diagnostic info
        println("Document: ${data.main.strokes.size} strokes, currentLineIndex=${data.currentLineIndex}")
        println("lineTextCache: $fullText")
        println("Consolidated keys: $consolidatedKeys, max=$maxConsolidated")
        println("Overflow shift: ${canvas.consolidationOverflowShiftPx}px")
        println("All consolidated text: '$allConsolidatedText'")

        // With advancement, all lines consolidate and word-wrap freely
        assertTrue("All lines should be consolidated",
            consolidatedKeys.containsAll(textCache.keys.filter { it < advancedCurrentLine }))
    }

    @Test
    fun `real document - door to master all text consolidated after advancement`() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("door_to_the_master.inkup")!!.readBytes()
        val proto = com.writer.model.proto.DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        column = ColumnModel()
        column.activeStrokes.addAll(data.main.strokes)
        textCache = data.main.lineTextCache.toMutableMap()

        // Simulate restoreState advancement: if currentLineIndex has cached text,
        // advance to highestLineIndex + 1
        val advancedLine = if (textCache.containsKey(data.currentLineIndex))
            data.highestLineIndex + 1 else data.currentLineIndex
        val host = TestHost(column, textCache, currentLine = advancedLine)
        dm = createDm(host)
        dm.updateInlineOverlays(advancedLine)

        // All text should be present in consolidated overlays
        val allOriginalText = textCache.entries
            .sortedBy { it.key }
            .joinToString(" ") { it.value }
        val consolidatedText = canvas.inlineTextOverlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(" ") { it.value.recognizedText }
        assertEquals("All text should be present after advancement", allOriginalText, consolidatedText)
    }

    @Test
    fun `real document - line order preserved after scratch and replace`() {
        // Load the document where lines got reordered after a split
        val bytes = javaClass.classLoader!!.getResourceAsStream("self_service_reorder.inkup")!!.readBytes()
        val proto = com.writer.model.proto.DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        column = ColumnModel()
        column.activeStrokes.addAll(data.main.strokes)
        textCache = data.main.lineTextCache.toMutableMap()

        val advancedCurrentLine = if (textCache.containsKey(data.currentLineIndex))
            data.highestLineIndex + 1 else data.currentLineIndex
        val host = TestHost(column, textCache, currentLine = advancedCurrentLine)
        dm = createDm(host)
        dm.updateInlineOverlays(advancedCurrentLine)

        val overlays = canvas.inlineTextOverlays
        val consolidatedText = overlays.entries
            .filter { it.value.consolidated && it.value.recognizedText.isNotBlank() }
            .sortedBy { it.key }
            .map { "${it.key}:'${it.value.recognizedText}'" }

        println("Reorder doc lines: $consolidatedText")

        // Verify numbered items maintain order (if present)
        val numberedLines = consolidatedText.filter { it.contains(Regex("\\d+\\.")) }
        if (numberedLines.size >= 2) {
            val numbers = numberedLines.mapNotNull {
                Regex("(\\d+)\\.").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            val sorted = numbers.sorted()
            assertEquals(
                "Numbered lines should be in ascending order but got $numbers",
                sorted, numbers
            )
        }
    }

    // ── Test host ───────────────────────────────────────────────────────────

    private class TestHost(
        override val columnModel: ColumnModel,
        val cache: MutableMap<Int, String>,
        var currentLine: Int = 3
    ) : DisplayManagerHost {
        override val diagramManager: DiagramManager by lazy {
            val stubRecognizer = object : com.writer.recognition.TextRecognizer {
                override suspend fun initialize(languageTag: String) {}
                override suspend fun recognizeLine(line: com.writer.model.InkLine, preContext: String) = ""
                override fun close() {}
            }
            val stubCanvas = object : DiagramCanvas {
                override var diagramAreas: List<DiagramArea>
                    get() = columnModel.diagramAreas
                    set(_) {}
                override var scrollOffsetY = 0f
                override fun loadStrokes(strokes: List<InkStroke>) {}
                override fun pauseAndRedraw() {}
            }
            val stubHost = object : DiagramManagerHost {
                override fun onDiagramAreasChanged() {}
                override fun getLineTextCache() = cache
            }
            DiagramManager(columnModel, LineSegmenter(), stubRecognizer, stubCanvas,
                stubHost, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        }
        override val lineTextCache: Map<Int, String> get() = cache
        override val highestLineIndex get() = currentLine
        override val currentLineIndex get() = currentLine
        override val lineRecognitionResults: Map<Int, RecognitionResult> = emptyMap()
        override val pendingWordEdit: PendingWordEdit? = null
        override fun eagerRecognizeLine(lineIndex: Int) {}
    }
}
