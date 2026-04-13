package com.writer.ui.writing

import android.app.Application
import com.writer.model.TextBlock
import com.writer.storage.DocumentBundle
import com.writer.view.ScreenMetrics
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Debug test: loads the actual document from device, prints TextBlock content,
 * and simulates scratch-out to find the bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScratchOutDebugTest {

    private val ls get() = ScreenMetrics.lineSpacing
    private val tm get() = ScreenMetrics.topMargin

    @Before
    fun setUp() {
        // Palma 2 Pro metrics
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    /**
     * Reproduce the bug: scratching 'mike' erases 'with'.
     * Simulates a text that likely wraps and contains both words.
     */
    @Test
    fun scratchMikeErasesWrongWord() {
        // Likely text from the device doc — contains 'mike' and 'with' on different wrapped lines
        val texts = listOf(
            "testing the mike with a new transcription this time",
            "recording with mike testing the new transcription mode",
            "this is a test with mike to check the transcription quality",
            "the transcription might not work with the mike properly"
        )

        val paint = android.text.TextPaint().apply { textSize = ScreenMetrics.textBody }
        val canvasWidth = 824f
        val textLeftMargin = ls * 0.3f
        val textWidth = (canvasWidth - 2 * textLeftMargin).toInt().coerceAtLeast(100)

        for (text in texts) {
            if (!text.contains("mike")) continue
            val block = TextBlock(startLineIndex = 5, heightInLines = 3, text = text)
            val fullText = text.trimStart()

            val layout = android.text.StaticLayout.Builder
                .obtain(fullText, 0, fullText.length, paint, textWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .build()

            println("\n=== Text: '$text' ===")
            println("  Layout: ${layout.lineCount} lines")
            for (i in 0 until layout.lineCount) {
                println("    Line $i: '${fullText.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()}'")
            }

            // Find 'mike' position
            val words = fullText.split(" ").filter { it.isNotEmpty() }
            var charPos = 0
            for ((idx, word) in words.withIndex()) {
                val wordLine = layout.getLineForOffset(charPos)
                val lineStart = layout.getLineStart(wordLine)
                val xInLine = paint.measureText(fullText.substring(lineStart, charPos))
                val wordX = textLeftMargin + xInLine
                val wordWidth = paint.measureText(word)
                println("    [$idx] '$word' charPos=$charPos line=$wordLine x=%.1f w=%.1f".format(wordX, wordWidth))
                charPos += word.length + 1
            }

            // Simulate scratch-out of 'mike'
            charPos = 0
            for (word in words) {
                if (word == "mike") {
                    val wordLine = layout.getLineForOffset(charPos)
                    val lineStart = layout.getLineStart(wordLine)
                    val xInLine = paint.measureText(fullText.substring(lineStart, charPos))
                    val scratchLeft = textLeftMargin + xInLine
                    val scratchRight = scratchLeft + paint.measureText("mike")
                    val scratchLine = block.startLineIndex + wordLine
                    val centerY = tm + scratchLine * ls + ls / 2

                    println("  Scratch 'mike': left=%.1f right=%.1f scratchLine=$scratchLine".format(scratchLeft, scratchRight))

                    val result = TextBlockEraser.findAndErase(
                        scratchLeft, centerY - 10f, scratchRight, centerY + 10f,
                        listOf(block), ls, tm, canvasWidth = 824f
                    )
                    if (result != null) {
                        println("  RESULT: removed='${result.second.removedWords}' remaining='${result.second.newText}'")
                        org.junit.Assert.assertEquals("Should remove 'mike'", "mike", result.second.removedWords)
                    } else {
                        println("  RESULT: null")
                        org.junit.Assert.fail("Should have found and erased 'mike'")
                    }
                    break
                }
                charPos += word.length + 1
            }
        }
    }
}
