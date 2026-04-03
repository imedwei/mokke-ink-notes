package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.recognition.*
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * DSL-based tests for scratch-out on consolidated text.
 *
 * The DSL lets tests declaratively define a document with words at
 * specific positions, simulate a scratch-out on a word, and assert
 * which strokes are removed and which remain.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class ScratchOutScenarioTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    // ── DSL ─────────────────────────────────────────────────────────────

    data class WordDef(val text: String, val startX: Float, val endX: Float)

    class LineDsl {
        internal val words = mutableListOf<WordDef>()
        fun word(text: String, startX: Float, endX: Float) {
            words.add(WordDef(text, startX, endX))
        }
    }

    class ScenarioResult(
        val removedStrokeIds: Set<String>,
        val remainingStrokes: List<InkStroke>,
        val coordinator: WritingCoordinator
    )

    class ScenarioDsl {
        internal val lines = mutableMapOf<Int, List<WordDef>>()
        internal var scratchLine = -1
        internal var scratchWord = -1

        fun line(index: Int, block: LineDsl.() -> Unit) {
            val dsl = LineDsl()
            dsl.block()
            lines[index] = dsl.words
        }

        fun scratchWord(lineIndex: Int, wordIndex: Int) {
            scratchLine = lineIndex
            scratchWord = wordIndex
        }
    }

    /** Create per-character strokes for a word at a specific X range on a line. */
    private fun wordStrokes(lineIndex: Int, startX: Float, endX: Float, word: String): List<InkStroke> {
        val y = tm + lineIndex * ls + ls * 0.5f
        val charWidth = (endX - startX) / word.length.coerceAtLeast(1)
        return word.mapIndexed { i, _ ->
            InkStroke(
                strokeId = "s${lineIndex}_${word}_$i",
                points = listOf(
                    StrokePoint(startX + i * charWidth, y - 5f, 0.5f, (lineIndex * 10000 + i * 100).toLong()),
                    StrokePoint(startX + i * charWidth + charWidth * 0.8f, y + 5f, 0.5f, (lineIndex * 10000 + i * 100 + 50).toLong())
                )
            )
        }
    }

    private fun runScenario(block: ScenarioDsl.() -> Unit): ScenarioResult {
        val scenario = ScenarioDsl()
        scenario.block()

        val documentModel = DocumentModel()
        val column = documentModel.main
        val recognitionResults = mutableMapOf<Int, RecognitionResult>()

        // Build strokes, text cache, and recognition results with bounding-box mappings
        for ((lineIdx, words) in scenario.lines) {
            val lineText = words.joinToString(" ") { it.text }

            val allLineStrokes = mutableListOf<InkStroke>()
            for (wordDef in words) {
                val strokes = wordStrokes(lineIdx, wordDef.startX, wordDef.endX, wordDef.text)
                column.activeStrokes.addAll(strokes)
                allLineStrokes.addAll(strokes)
            }

            // Build recognition result with bounding-box-based stroke mapping
            val wordConfidences = words.mapIndexed { idx, w ->
                WordConfidence(w.text, 1.0f, idx, WordBoundingBox(w.startX, 0f, w.endX - w.startX, 20f))
            }
            val mapping = StrokeMatcher.buildWordStrokeMapping(wordConfidences, allLineStrokes)
            recognitionResults[lineIdx] = RecognitionResult(
                candidates = listOf(RecognitionCandidate(lineText, null)),
                wordConfidences = wordConfidences,
                wordStrokeMapping = mapping
            )
        }

        // Create coordinator with a mock recognizer
        val canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        val recognizer = object : TextRecognizer {
            override suspend fun initialize(languageTag: String) {}
            override suspend fun recognizeLine(line: com.writer.model.InkLine, preContext: String) = ""
            override fun close() {}
        }
        val coordinator = WritingCoordinator(
            documentModel, column, recognizer, canvas,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onStatusUpdate = {}
        )

        // Populate text cache and recognition results
        for ((lineIdx, words) in scenario.lines) {
            coordinator.lineTextCache[lineIdx] = words.joinToString(" ") { it.text }
        }

        // Inject recognition results by starting coordinator briefly
        // (findStrokesForWord checks recognitionManager.lineRecognitionResults)
        coordinator.start()
        for ((lineIdx, result) in recognitionResults) {
            coordinator.setTestRecognitionResult(lineIdx, result)
        }

        // Execute findStrokesForWord for the scratch target
        val strokesBefore = column.activeStrokes.map { it.strokeId }.toSet()
        val targetStrokes = coordinator.findStrokesForWord(scenario.scratchLine, scenario.scratchWord)
        val removedIds = targetStrokes.map { it.strokeId }.toSet()

        // Remove the strokes (simulating scratch-out)
        column.activeStrokes.removeAll { it.strokeId in removedIds }

        coordinator.stop()

        return ScenarioResult(
            removedStrokeIds = removedIds,
            remainingStrokes = column.activeStrokes.toList(),
            coordinator = coordinator
        )
    }

    // ── Scenarios ───────────────────────────────────────────────────────

    @Test
    fun `scratch middle word removes only that word`() {
        val result = runScenario {
            line(0) {
                word("the", 10f, 80f)
                word("quick", 120f, 220f)
                word("brown", 260f, 360f)
            }
            scratchWord(lineIndex = 0, wordIndex = 1)
        }

        // Only "quick" strokes removed
        assertTrue("All removed strokes are from 'quick'",
            result.removedStrokeIds.all { it.startsWith("s0_quick") })
        assertEquals("5 strokes removed (one per char in 'quick')",
            5, result.removedStrokeIds.size)
        // "the" and "brown" survive
        val remainingIds = result.remainingStrokes.map { it.strokeId }
        assertTrue("'the' strokes remain", remainingIds.any { it.startsWith("s0_the") })
        assertTrue("'brown' strokes remain", remainingIds.any { it.startsWith("s0_brown") })
    }

    @Test
    fun `scratch first word preserves rest`() {
        val result = runScenario {
            line(0) {
                word("hello", 10f, 120f)
                word("world", 160f, 270f)
            }
            scratchWord(lineIndex = 0, wordIndex = 0)
        }

        assertTrue("Only 'hello' strokes removed",
            result.removedStrokeIds.all { it.startsWith("s0_hello") })
        assertTrue("'world' strokes remain",
            result.remainingStrokes.any { it.strokeId.startsWith("s0_world") })
    }

    @Test
    fun `scratch last word preserves rest`() {
        val result = runScenario {
            line(0) {
                word("hello", 10f, 120f)
                word("world", 160f, 270f)
            }
            scratchWord(lineIndex = 0, wordIndex = 1)
        }

        assertTrue("Only 'world' strokes removed",
            result.removedStrokeIds.all { it.startsWith("s0_world") })
        assertTrue("'hello' strokes remain",
            result.remainingStrokes.any { it.strokeId.startsWith("s0_hello") })
    }

    @Test
    fun `scratch on line 1 does not affect line 0`() {
        val result = runScenario {
            line(0) {
                word("the", 10f, 80f)
                word("quick", 120f, 220f)
            }
            line(1) {
                word("fox", 10f, 70f)
                word("jumps", 100f, 200f)
            }
            scratchWord(lineIndex = 1, wordIndex = 0)
        }

        assertTrue("Only 'fox' strokes removed",
            result.removedStrokeIds.all { it.startsWith("s1_fox") })
        // All of line 0 survives
        val line0Remaining = result.remainingStrokes.filter { it.strokeId.startsWith("s0_") }
        assertEquals("All line 0 strokes remain", 8, line0Remaining.size) // the(3) + quick(5)
    }

    @Test
    fun `scratch word in dense text with small gaps`() {
        // Words very close together — gap detection would struggle but
        // bounding-box mapping should get it right
        val result = runScenario {
            line(0) {
                word("in", 10f, 40f)
                word("the", 45f, 90f)    // only 5px gap
                word("closet", 95f, 180f) // only 5px gap
            }
            scratchWord(lineIndex = 0, wordIndex = 1)  // scratch "the"
        }

        assertTrue("Only 'the' strokes removed",
            result.removedStrokeIds.all { it.startsWith("s0_the") })
        assertEquals("3 strokes removed", 3, result.removedStrokeIds.size)
        assertTrue("'in' remains", result.remainingStrokes.any { it.strokeId.startsWith("s0_in") })
        assertTrue("'closet' remains", result.remainingStrokes.any { it.strokeId.startsWith("s0_closet") })
    }

    @Test
    fun `single word line returns all strokes`() {
        val result = runScenario {
            line(0) {
                word("Combinatorial", 10f, 350f)
            }
            scratchWord(lineIndex = 0, wordIndex = 0)
        }

        assertEquals("All 13 strokes removed", 13, result.removedStrokeIds.size)
        assertTrue(result.remainingStrokes.isEmpty())
    }

    @Test
    fun `scratch does not cross line boundaries`() {
        // "the closet" on line 1 should not be affected by scratching
        // "Combinatorial" on line 0, even though they're adjacent
        val result = runScenario {
            line(0) {
                word("Combinatorial", 10f, 350f)
                word("Exuberance", 380f, 700f)
            }
            line(1) {
                word("in", 10f, 40f)
                word("the", 50f, 100f)
                word("closet", 110f, 220f)
            }
            scratchWord(lineIndex = 0, wordIndex = 0)  // scratch "Combinatorial"
        }

        assertTrue("Only 'Combinatorial' strokes removed",
            result.removedStrokeIds.all { it.startsWith("s0_Combinatorial") })
        // All of line 1 survives
        val line1Remaining = result.remainingStrokes.filter { it.strokeId.startsWith("s1_") }
        assertEquals("All line 1 strokes remain (in=2 + the=3 + closet=6)",
            11, line1Remaining.size)
    }
}
