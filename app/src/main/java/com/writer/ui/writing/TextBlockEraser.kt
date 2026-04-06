package com.writer.ui.writing

import android.text.TextPaint
import com.writer.model.TextBlock
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics

/**
 * Handles scratch-out gestures over TextBlocks.
 *
 * Maps the scratch-out's horizontal span to character offsets in the rendered
 * text, then removes the overlapping word(s). If all text is removed, signals
 * that the entire TextBlock should be deleted.
 */
object TextBlockEraser {

    /**
     * Result of a scratch-out over a TextBlock.
     */
    data class EraseResult(
        /** The modified text (with words removed), or empty if block should be deleted. */
        val newText: String,
        /** True if the entire TextBlock should be removed. */
        val deleteBlock: Boolean,
        /** Character index in the NEW text where the gap is (for replacement insertion). */
        val gapCharIndex: Int = 0,
        /** The word(s) that were removed. */
        val removedWords: String = ""
    )

    /**
     * Find which TextBlock (if any) the scratch-out overlaps, and compute the
     * erase result.
     *
     * @param scratchLeft left edge of scratch bbox in document coordinates
     * @param scratchTop top edge of scratch bbox in document coordinates
     * @param scratchRight right edge of scratch bbox
     * @param scratchBottom bottom edge of scratch bbox
     * @param textBlocks all text blocks in the column
     * @param lineSpacing line spacing in pixels
     * @param topMargin top margin in pixels
     * @return pair of (TextBlock, EraseResult) if a block was hit, null otherwise
     */
    fun findAndErase(
        scratchLeft: Float, scratchTop: Float,
        scratchRight: Float, scratchBottom: Float,
        textBlocks: List<TextBlock>,
        lineSpacing: Float,
        topMargin: Float
    ): Pair<TextBlock, EraseResult>? {
        // Find which text block the scratch overlaps
        val scratchCenterY = (scratchTop + scratchBottom) / 2
        val scratchLineIndex = ((scratchCenterY - topMargin) / lineSpacing).toInt().coerceAtLeast(0)

        val block = textBlocks.find { it.containsLine(scratchLineIndex) } ?: return null

        // Map scratch horizontal span to words using actual text measurement
        val words = block.text.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return block to EraseResult("", deleteBlock = true)

        val textLeftMargin = lineSpacing * 0.3f
        val paint = TextPaint().apply { textSize = ScreenMetrics.textBody }
        val spaceWidth = paint.measureText(" ")

        // Find which words overlap the scratch horizontal span
        val scratchStartX = scratchLeft
        val scratchEndX = scratchRight

        val surviving = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var xPos = textLeftMargin
        var gapCharIndex = 0
        var foundGap = false
        for (word in words) {
            val wordWidth = paint.measureText(word)
            val wordStart = xPos
            val wordEnd = xPos + wordWidth
            val overlaps = wordEnd > scratchStartX && wordStart < scratchEndX
            if (!overlaps) {
                if (!foundGap) gapCharIndex = surviving.joinToString(" ").length + if (surviving.isNotEmpty()) 1 else 0
                surviving.add(word)
            } else {
                if (!foundGap) {
                    gapCharIndex = surviving.joinToString(" ").length + if (surviving.isNotEmpty()) 1 else 0
                    foundGap = true
                }
                removed.add(word)
            }
            xPos = wordEnd + spaceWidth
        }

        val newText = surviving.joinToString(" ").trim()
        val removedText = removed.joinToString(" ")
        return block to EraseResult(
            newText = newText,
            deleteBlock = newText.isEmpty(),
            gapCharIndex = gapCharIndex,
            removedWords = removedText
        )
    }

    /**
     * Estimate character width based on text length and available rendering width.
     * This is approximate — a more accurate approach would use TextPaint.measureText,
     * but this avoids Android dependencies for testability.
     */
    fun estimateCharWidth(text: String, canvasWidth: Float, textLeftMargin: Float): Float {
        if (text.isEmpty()) return 10f
        val availableWidth = canvasWidth - 2 * textLeftMargin
        // Assume text fills the available width (word-wrapped)
        // Use a simple per-character estimate
        return (availableWidth / text.length.toFloat()).coerceIn(5f, 30f)
    }
}
