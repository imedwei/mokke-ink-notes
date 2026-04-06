package com.writer.ui.writing

import android.app.Application
import com.writer.model.ColumnModel
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.TextBlock
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test DSL for TextBlock operations: scratch-out, replacement, space insert,
 * and transcription insertion.
 *
 * Usage:
 * ```
 * doc {
 *     stroke(line = 0, text = "hello world")
 *     textBlock(line = 2, text = "transcribed lecture notes")
 * }.scratchOut(line = 2, word = "lecture") {
 *     assertTextBlock(line = 2, text = "transcribed notes")
 *     assertGap(removedWord = "lecture")
 * }.replaceWith("class") {
 *     assertTextBlock(line = 2, text = "transcribed class notes")
 * }
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TextBlockOperationsTest {

    private val ls get() = ScreenMetrics.lineSpacing
    private val tm get() = ScreenMetrics.topMargin

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    // ═══════════════════════════════════════════════════════════════════
    // DSL
    // ═══════════════════════════════════════════════════════════════════

    private fun doc(block: DocBuilder.() -> Unit): DocState {
        val builder = DocBuilder()
        builder.block()
        return DocState(builder.columnModel, builder.segmenter)
    }

    class DocBuilder {
        val columnModel = ColumnModel()
        val segmenter = LineSegmenter()

        fun stroke(line: Int, startX: Float = 50f, endX: Float = 300f, id: String = "s-$line") {
            val y = ScreenMetrics.topMargin + line * ScreenMetrics.lineSpacing + ScreenMetrics.lineSpacing * 0.4f
            columnModel.activeStrokes.add(
                InkStroke(
                    strokeId = id,
                    points = listOf(
                        StrokePoint(startX, y, 0.5f, 0L),
                        StrokePoint(endX, y + 5f, 0.5f, 100L)
                    )
                )
            )
        }

        fun textBlock(line: Int, text: String, heightInLines: Int = 1, id: String = "tb-$line") {
            columnModel.textBlocks.add(
                TextBlock(id = id, startLineIndex = line, heightInLines = heightInLines, text = text)
            )
        }

        fun diagram(line: Int, height: Int = 2, id: String = "d-$line") {
            columnModel.diagramAreas.add(DiagramArea(id = id, startLineIndex = line, heightInLines = height))
        }
    }

    inner class DocState(
        val columnModel: ColumnModel,
        val segmenter: LineSegmenter
    ) {
        var lastEraseResult: TextBlockEraser.EraseResult? = null
        var lastErasedBlock: TextBlock? = null

        // ── Assertions ──────────────────────────────────────────────

        fun assertTextBlock(line: Int, text: String, msg: String = ""): DocState {
            val block = columnModel.textBlocks.find { it.startLineIndex == line }
            assertNotNull("TextBlock at line $line should exist. $msg", block)
            assertEquals("TextBlock text at line $line. $msg", text, block!!.text)
            return this
        }

        fun assertNoTextBlock(line: Int, msg: String = ""): DocState {
            val block = columnModel.textBlocks.find { it.startLineIndex == line }
            assertNull("TextBlock at line $line should not exist. $msg", block)
            return this
        }

        fun assertTextBlockCount(count: Int, msg: String = ""): DocState {
            assertEquals("TextBlock count. $msg", count, columnModel.textBlocks.size)
            return this
        }

        fun assertStrokeCount(count: Int, msg: String = ""): DocState {
            assertEquals("Stroke count. $msg", count, columnModel.activeStrokes.size)
            return this
        }

        fun assertGap(removedWord: String, msg: String = ""): DocState {
            assertNotNull("Erase result should exist. $msg", lastEraseResult)
            assertEquals("Removed word. $msg", removedWord, lastEraseResult!!.removedWords)
            assertFalse("Block should not be deleted. $msg", lastEraseResult!!.deleteBlock)
            return this
        }

        fun assertDeleted(msg: String = ""): DocState {
            assertNotNull("Erase result should exist. $msg", lastEraseResult)
            assertTrue("Block should be deleted. $msg", lastEraseResult!!.deleteBlock)
            return this
        }

        // ── Operations ──────────────────────────────────────────────

        /** Scratch out a specific word in the TextBlock at [line]. */
        fun scratchOut(line: Int, word: String, then: DocState.() -> Unit = {}): DocState {
            val block = columnModel.textBlocks.find { it.containsLine(line) }
                ?: error("No TextBlock at line $line")

            // Compute the X bounds of the target word using TextPaint
            val paint = android.text.TextPaint().apply { textSize = ScreenMetrics.textBody }
            val textLeftMargin = ls * 0.3f
            val spaceWidth = paint.measureText(" ")
            val words = block.text.split(" ").filter { it.isNotEmpty() }
            var xPos = textLeftMargin
            var targetLeft = 0f
            var targetRight = 0f
            var found = false
            for (w in words) {
                val wordWidth = paint.measureText(w)
                if (w == word && !found) {
                    targetLeft = xPos
                    targetRight = xPos + wordWidth
                    found = true
                }
                xPos += wordWidth + spaceWidth
            }
            assertTrue("Word '$word' not found in '${block.text}'", found)

            val centerY = tm + line * ls + ls / 2
            val result = TextBlockEraser.findAndErase(
                targetLeft, centerY - 10f, targetRight, centerY + 10f,
                columnModel.textBlocks, ls, tm
            )
            assertNotNull("Scratch-out should hit a TextBlock", result)
            val (hitBlock, eraseResult) = result!!
            lastErasedBlock = hitBlock
            lastEraseResult = eraseResult

            if (eraseResult.deleteBlock) {
                columnModel.textBlocks.remove(hitBlock)
            } else {
                val idx = columnModel.textBlocks.indexOf(hitBlock)
                if (idx >= 0) {
                    // Insert gap placeholder (same as WritingCoordinator)
                    val gapPlaceholder = " ".repeat(eraseResult.removedWords.length.coerceAtLeast(4))
                    val newText = if (eraseResult.gapCharIndex <= 0) {
                        "$gapPlaceholder ${eraseResult.newText}"
                    } else if (eraseResult.gapCharIndex >= eraseResult.newText.length) {
                        "${eraseResult.newText} $gapPlaceholder"
                    } else {
                        val before = eraseResult.newText.substring(0, eraseResult.gapCharIndex).trimEnd()
                        val after = eraseResult.newText.substring(eraseResult.gapCharIndex).trimStart()
                        "$before $gapPlaceholder $after"
                    }
                    columnModel.textBlocks[idx] = hitBlock.copy(text = newText)
                }
            }

            then()
            return this
        }

        /** Replace the gap left by scratch-out with [word]. */
        fun replaceWith(word: String, then: DocState.() -> Unit = {}): DocState {
            val block = lastErasedBlock?.let { b -> columnModel.textBlocks.find { it.id == b.id } }
                ?: error("No active gap to replace")

            val gapRegex = Regex("  +")
            val match = gapRegex.find(block.text)
            val newText = if (match != null) {
                val before = block.text.substring(0, match.range.first).trimEnd()
                val after = block.text.substring(match.range.last + 1).trimStart()
                "$before $word $after".replace(Regex("  +"), " ").trim()
            } else {
                "${block.text} $word".trim()
            }

            val idx = columnModel.textBlocks.indexOfFirst { it.id == block.id }
            if (idx >= 0) {
                columnModel.textBlocks[idx] = block.copy(text = newText)
            }
            lastEraseResult = null
            lastErasedBlock = null

            then()
            return this
        }

        /** Insert space at [anchorLine], shifting content down by [lines]. */
        fun insertSpace(anchorLine: Int, lines: Int, then: DocState.() -> Unit = {}): DocState {
            SpaceInsertMode.insertSpace(columnModel, segmenter, anchorLine, lines)
            then()
            return this
        }

        /** Remove space at [anchorLine], shifting content up by up to [lines]. */
        fun removeSpace(anchorLine: Int, lines: Int, then: DocState.() -> Unit = {}): DocState {
            SpaceInsertMode.removeSpace(columnModel, segmenter, anchorLine, lines)
            then()
            return this
        }

        /** Add a new TextBlock via insertTextBlock-like logic. */
        fun addTextBlock(text: String, then: DocState.() -> Unit = {}): DocState {
            val highestStrokeLine = if (columnModel.activeStrokes.isNotEmpty()) {
                columnModel.activeStrokes.maxOf { segmenter.getStrokeLineIndex(it) }
            } else -1
            val highestTextBlockLine = if (columnModel.textBlocks.isNotEmpty()) {
                columnModel.textBlocks.maxOf { it.endLineIndex }
            } else -1
            val lineIndex = maxOf(highestStrokeLine, highestTextBlockLine) + 1
            columnModel.textBlocks.add(
                TextBlock(startLineIndex = lineIndex, heightInLines = 1, text = text)
            )
            then()
            return this
        }

        /** Scratch out covering the entire TextBlock at [line]. */
        fun scratchOutAll(line: Int, then: DocState.() -> Unit = {}): DocState {
            val centerY = tm + line * ls + ls / 2
            val result = TextBlockEraser.findAndErase(
                0f, centerY - 10f, 2000f, centerY + 10f,
                columnModel.textBlocks, ls, tm
            )
            assertNotNull("Scratch-out should hit a TextBlock", result)
            lastEraseResult = result!!.second
            lastErasedBlock = result.first
            if (result.second.deleteBlock) {
                columnModel.textBlocks.remove(result.first)
            }
            then()
            return this
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tests
    // ═══════════════════════════════════════════════════════════════════

    // ── Scratch-out word removal ────────────────────────────────────

    @Test
    fun `scratch out middle word leaves gap`() {
        doc {
            textBlock(line = 2, text = "testing the lecture")
        }.scratchOut(line = 2, word = "the") {
            assertGap(removedWord = "the")
            assertTextBlockCount(1)
        }
    }

    @Test
    fun `scratch out first word`() {
        doc {
            textBlock(line = 2, text = "hello beautiful world")
        }.scratchOut(line = 2, word = "hello") {
            assertGap(removedWord = "hello")
        }
    }

    @Test
    fun `scratch out last word`() {
        doc {
            textBlock(line = 2, text = "hello beautiful world")
        }.scratchOut(line = 2, word = "world") {
            assertGap(removedWord = "world")
        }
    }

    @Test
    fun `scratch out only word deletes block`() {
        doc {
            textBlock(line = 2, text = "hello")
        }.scratchOutAll(line = 2) {
            assertDeleted()
            assertTextBlockCount(0)
        }
    }

    // ── Scratch-out and replace ─────────────────────────────────────

    @Test
    fun `scratch and replace middle word`() {
        doc {
            textBlock(line = 2, text = "testing the lecture")
        }.scratchOut(line = 2, word = "the") {
            assertGap(removedWord = "the")
        }.replaceWith("a") {
            assertTextBlock(line = 2, text = "testing a lecture")
        }
    }

    @Test
    fun `scratch and replace first word`() {
        doc {
            textBlock(line = 2, text = "bad morning everyone")
        }.scratchOut(line = 2, word = "bad") {
            assertGap(removedWord = "bad")
        }.replaceWith("good") {
            assertTextBlock(line = 2, text = "good morning everyone")
        }
    }

    @Test
    fun `scratch and replace last word`() {
        doc {
            textBlock(line = 2, text = "hello beautiful world")
        }.scratchOut(line = 2, word = "world") {
            assertGap(removedWord = "world")
        }.replaceWith("everyone") {
            assertTextBlock(line = 2, text = "hello beautiful everyone")
        }
    }

    @Test
    fun `multiple scratch and replace cycles`() {
        doc {
            textBlock(line = 2, text = "the quick brown fox")
        }.scratchOut(line = 2, word = "quick") {
            assertGap(removedWord = "quick")
        }.replaceWith("slow") {
            assertTextBlock(line = 2, text = "the slow brown fox")
        }.scratchOut(line = 2, word = "brown") {
            assertGap(removedWord = "brown")
        }.replaceWith("grey") {
            assertTextBlock(line = 2, text = "the slow grey fox")
        }
    }

    // ── Space insert with TextBlocks ────────────────────────────────

    @Test
    fun `insert space shifts TextBlock down`() {
        doc {
            stroke(line = 0)
            textBlock(line = 2, text = "memo")
        }.insertSpace(anchorLine = 1, lines = 2) {
            assertTextBlock(line = 4, text = "memo")
        }
    }

    @Test
    fun `insert space above TextBlock shifts it down`() {
        doc {
            textBlock(line = 3, text = "hello")
        }.insertSpace(anchorLine = 2, lines = 1) {
            assertTextBlock(line = 4, text = "hello")
            assertNoTextBlock(line = 3)
        }
    }

    @Test
    fun `insert space below TextBlock leaves it`() {
        doc {
            textBlock(line = 2, text = "stays here")
            stroke(line = 5)
        }.insertSpace(anchorLine = 4, lines = 1) {
            assertTextBlock(line = 2, text = "stays here")
        }
    }

    @Test
    fun `remove space shifts TextBlock up`() {
        doc {
            stroke(line = 0)
            // Lines 1-2 are empty
            textBlock(line = 3, text = "memo")
        }.removeSpace(anchorLine = 3, lines = 2) {
            assertTextBlock(line = 1, text = "memo")
        }
    }

    @Test
    fun `remove space blocked by TextBlock`() {
        doc {
            textBlock(line = 1, text = "blocker")
            // Line 2 is empty
            textBlock(line = 3, text = "below")
        }.removeSpace(anchorLine = 3, lines = 5) {
            // Can only remove 1 empty line (line 2), blocked by TextBlock at line 1
            assertTextBlock(line = 1, text = "blocker")
            assertTextBlock(line = 2, text = "below")
        }
    }

    // ── Add TextBlock positioning ───────────────────────────────────

    @Test
    fun `addTextBlock after strokes`() {
        doc {
            stroke(line = 0)
            stroke(line = 1)
        }.addTextBlock("voice memo") {
            assertTextBlock(line = 2, text = "voice memo")
        }
    }

    @Test
    fun `addTextBlock after existing TextBlock`() {
        doc {
            stroke(line = 0)
            textBlock(line = 1, text = "first memo")
        }.addTextBlock("second memo") {
            assertTextBlock(line = 1, text = "first memo")
            assertTextBlock(line = 2, text = "second memo")
            assertTextBlockCount(2)
        }
    }

    @Test
    fun `addTextBlock on empty doc`() {
        doc {
        }.addTextBlock("first note") {
            assertTextBlock(line = 0, text = "first note")
        }
    }

    // ── Mixed content ──────────────────────────────────────────────

    @Test
    fun `space insert shifts strokes, diagrams, and TextBlocks together`() {
        doc {
            stroke(line = 0)
            diagram(line = 2, height = 2)
            textBlock(line = 5, text = "lecture notes")
        }.insertSpace(anchorLine = 1, lines = 3) {
            // Diagram shifted from line 2 to line 5
            assertEquals(5, columnModel.diagramAreas[0].startLineIndex)
            // TextBlock shifted from line 5 to line 8
            assertTextBlock(line = 8, text = "lecture notes")
        }
    }

    @Test
    fun `scratch out does not affect strokes`() {
        doc {
            stroke(line = 0)
            stroke(line = 1)
            textBlock(line = 2, text = "testing the lecture")
        }.scratchOut(line = 2, word = "the") {
            assertStrokeCount(2, "Strokes should be unaffected")
            assertGap(removedWord = "the")
        }
    }
}
