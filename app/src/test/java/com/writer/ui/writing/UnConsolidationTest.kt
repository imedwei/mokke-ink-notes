package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.recognition.RecognitionResult
import com.writer.recognition.StrokeClassifier
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for double-tap un-consolidation and re-consolidation of inline text overlays.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class UnConsolidationTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var column: ColumnModel
    private lateinit var textCache: MutableMap<Int, String>
    private lateinit var dm: DisplayManager

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        column = ColumnModel()
        textCache = mutableMapOf()

        for (line in 0 until 10) {
            val y = tm + line * ls + ls / 2
            column.activeStrokes.add(InkStroke(
                strokeId = "s$line",
                points = listOf(
                    StrokePoint(10f, y, 0.5f, (line * 1000).toLong()),
                    StrokePoint(200f, y, 0.5f, (line * 1000 + 500).toLong())
                )
            ))
        }
        for (line in 0 until 9) {
            textCache[line] = "Text for line $line"
        }

        val canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        // Note: Robolectric SurfaceView has 0 height. DisplayManager handles this
        // with Int.MAX_VALUE fallback for maxVisibleLine.
        val lineSegmenter = LineSegmenter()
        val host = TestHost(column, textCache)
        dm = DisplayManager(canvas,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            lineSegmenter, ParagraphBuilder(StrokeClassifier(lineSegmenter)), host, null)
    }

    @Test
    fun `initial overlays are all consolidated except current line`() {
        val overlays = dm.buildInlineOverlays(9)
        assertTrue("Expected overlays for lines 0-8, got keys=${overlays.keys.sorted()}", overlays.size >= 9)
        for (line in 0 until 9) {
            assertTrue("Line $line should be consolidated", overlays[line]?.consolidated == true)
            assertFalse("Line $line should not be un-consolidated", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `toggleUnConsolidate marks entire paragraph as un-consolidated`() {
        dm.toggleUnConsolidate(4)
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 9) {
            assertTrue("Line $line should be un-consolidated", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `toggleUnConsolidate twice re-consolidates the paragraph`() {
        dm.toggleUnConsolidate(4)
        dm.toggleUnConsolidate(4)
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 9) {
            assertFalse("Line $line should be re-consolidated", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `reConsolidateAll clears all un-consolidated lines`() {
        dm.toggleUnConsolidate(4)
        assertTrue(dm.buildInlineOverlays(9)[4]?.unConsolidated == true)
        dm.reConsolidateAll()
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 9) {
            assertFalse("Line $line should be re-consolidated", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `un-consolidation respects diagram boundaries`() {
        column.diagramAreas.add(DiagramArea(startLineIndex = 4, heightInLines = 2))
        dm.toggleUnConsolidate(2)
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 4) {
            assertTrue("Line $line should be un-consolidated (before diagram)", overlays[line]?.unConsolidated == true)
        }
        for (line in 6 until 9) {
            assertFalse("Line $line should not be un-consolidated (after diagram)", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `un-consolidated lines excluded from consolidated set`() {
        dm.toggleUnConsolidate(4)
        val overlays = dm.buildInlineOverlays(9)
        val consolidated = overlays.filter { it.value.consolidated && !it.value.unConsolidated }.keys
        assertTrue("No lines should be consolidated", consolidated.isEmpty())
    }

    @Test
    fun `tapping first line un-consolidates full paragraph`() {
        dm.toggleUnConsolidate(0)
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 9) {
            assertTrue("Line $line should be un-consolidated", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `tapping last consolidated line un-consolidates full paragraph`() {
        dm.toggleUnConsolidate(8)
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 9) {
            assertTrue("Line $line should be un-consolidated", overlays[line]?.unConsolidated == true)
        }
    }

    @Test
    fun `two separate paragraphs un-consolidate independently`() {
        textCache.remove(4)
        dm.toggleUnConsolidate(2)
        val overlays = dm.buildInlineOverlays(9)
        for (line in 0 until 4) {
            assertTrue("Line $line should be un-consolidated (first paragraph)", overlays[line]?.unConsolidated == true)
        }
        for (line in 5 until 9) {
            assertFalse("Line $line should not be un-consolidated (second paragraph)", overlays[line]?.unConsolidated == true)
        }
    }

    /**
     * Minimal DisplayManagerHost that stubs DiagramManager via a fake that
     * only implements isDiagramLine() using the column's diagram areas.
     */
    private inner class TestHost(
        override val columnModel: ColumnModel,
        private val cache: MutableMap<Int, String>
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
        override val highestLineIndex get() = 9
        override val currentLineIndex get() = 9
        override val lineRecognitionResults: Map<Int, RecognitionResult> = emptyMap()
        override fun eagerRecognizeLine(lineIndex: Int) {}
        override fun markRecognizing(lineIndex: Int) {}
        override suspend fun doRecognizeLine(lineIndex: Int): String? = null
        override fun isRecognizing(lineIndex: Int): Boolean = false
    }
}
