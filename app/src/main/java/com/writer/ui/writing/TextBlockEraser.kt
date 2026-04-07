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

        // Map scratch to words accounting for multi-line wrapping
        val allWords = block.text.split(" ").filter { it.isNotEmpty() }
        if (allWords.isEmpty()) return block to EraseResult("", deleteBlock = true)

        val textLeftMargin = lineSpacing * 0.3f
        val paint = TextPaint().apply { textSize = ScreenMetrics.textBody }
        val fullText = block.text.trimStart()

        // Use StaticLayout to find which wrapped line the scratch is on
        val textWidth = (scratchRight.coerceAtLeast(500f) - 2 * textLeftMargin).toInt().coerceAtLeast(100)
        val layout = android.text.StaticLayout.Builder
            .obtain(fullText, 0, fullText.length, paint, textWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .build()

        val scratchCenterX = (scratchLeft + scratchRight) / 2
        val scratchWrappedLine = scratchLineIndex - block.startLineIndex

        // Find words on the scratched line and check overlap
        val surviving = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var gapCharIndex = 0
        var foundGap = false

        val wordsInText = fullText.split(" ").filter { it.isNotEmpty() }
        var charPos = 0
        for ((i, word) in wordsInText.withIndex()) {
            // Determine which wrapped line this word is on
            val wordCharStart = charPos
            val wordLine = layout.getLineForOffset(wordCharStart)

            if (wordLine == scratchWrappedLine) {
                // This word is on the scratched line — check X overlap
                val wordX = textLeftMargin + paint.measureText(
                    fullText.substring(layout.getLineStart(wordLine), wordCharStart)
                )
                val wordWidth = paint.measureText(word)
                val overlaps = (wordX + wordWidth) > scratchLeft && wordX < scratchRight

                if (overlaps) {
                    if (!foundGap) {
                        gapCharIndex = surviving.joinToString(" ").length + if (surviving.isNotEmpty()) 1 else 0
                        foundGap = true
                    }
                    removed.add(word)
                } else {
                    if (!foundGap) gapCharIndex = surviving.joinToString(" ").length + if (surviving.isNotEmpty()) 1 else 0
                    surviving.add(word)
                }
            } else {
                // Word on a different line — always survives
                if (!foundGap) gapCharIndex = surviving.joinToString(" ").length + if (surviving.isNotEmpty()) 1 else 0
                surviving.add(word)
            }
            charPos += word.length + 1 // +1 for space
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
