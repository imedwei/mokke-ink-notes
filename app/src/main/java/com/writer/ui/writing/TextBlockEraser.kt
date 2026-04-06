package com.writer.ui.writing

import com.writer.model.TextBlock
import com.writer.view.HandwritingCanvasView

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
        val deleteBlock: Boolean
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

        // Map scratch horizontal span to words
        val words = block.text.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return block to EraseResult("", deleteBlock = true)

        // Approximate character positions using uniform character width
        val textLeftMargin = lineSpacing * 0.3f
        val textAreaWidth = scratchRight.coerceAtLeast(scratchLeft + 1) // avoid div by zero
        val charWidth = estimateCharWidth(block.text, textAreaWidth, textLeftMargin)

        // Find which words overlap the scratch horizontal span
        val scratchStartX = scratchLeft - textLeftMargin
        val scratchEndX = scratchRight - textLeftMargin

        val surviving = mutableListOf<String>()
        var charOffset = 0f
        for (word in words) {
            val wordStart = charOffset
            val wordEnd = charOffset + word.length * charWidth
            // Keep the word if it doesn't overlap the scratch
            val overlaps = wordEnd > scratchStartX && wordStart < scratchEndX
            if (!overlaps) {
                surviving.add(word)
            }
            charOffset = wordEnd + charWidth // space between words
        }

        val newText = surviving.joinToString(" ").trim()
        return block to EraseResult(
            newText = newText,
            deleteBlock = newText.isEmpty()
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
