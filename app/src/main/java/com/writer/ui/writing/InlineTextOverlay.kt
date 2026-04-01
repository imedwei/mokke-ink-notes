package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.recognition.RecognitionCandidate

/**
 * A word in the recognized text that has alternative candidates.
 * Used to show per-word alternatives inline rather than whole-line alternatives.
 */
data class WordAlternative(
    /** Index of the word in the recognized text (0-based). */
    val wordIndex: Int,
    /** The primary word (from top candidate). */
    val word: String,
    /** Alternative words from other candidates (deduplicated, max 3). */
    val alternatives: List<String>
)

/**
 * Diff candidate strings to find which specific words differ,
 * filtered by agreement ratio. Only words where fewer than
 * [agreementThreshold] fraction of candidates agree with the
 * top choice are returned as alternatives.
 *
 * @param agreementThreshold fraction (0-1) of candidates that must
 *        agree with the top word to suppress alternatives. Default 0.6
 *        means if 60%+ of candidates agree, no alternatives shown.
 */
fun findWordAlternatives(
    candidates: List<RecognitionCandidate>,
    agreementThreshold: Float = 0.6f
): List<WordAlternative> {
    if (candidates.size < 2) return emptyList()
    val topWords = candidates[0].text.split(" ")
    if (topWords.isEmpty()) return emptyList()
    val totalCandidates = candidates.size

    val result = mutableListOf<WordAlternative>()
    for ((i, topWord) in topWords.withIndex()) {
        val wordCounts = mutableMapOf<String, Int>()
        for (c in candidates) {
            val cWords = c.text.split(" ")
            if (i < cWords.size) {
                wordCounts[cWords[i]] = (wordCounts[cWords[i]] ?: 0) + 1
            }
        }
        val topCount = wordCounts[topWord] ?: 0
        val agreementRatio = topCount.toFloat() / totalCandidates

        // Only show alternatives if agreement is below threshold
        if (agreementRatio < agreementThreshold) {
            val alts = wordCounts.keys
                .filter { it != topWord }
                .sortedByDescending { wordCounts[it] }
                .take(2)
            if (alts.isNotEmpty()) {
                result.add(WordAlternative(
                    wordIndex = i,
                    word = topWord,
                    alternatives = alts
                ))
            }
        }
    }
    return result
}

/**
 * State for a single line's inline text overlay on the canvas.
 *
 * When consolidated, the original handwritten strokes are hidden during rendering
 * and replaced by Hershey font synthetic strokes. The originals remain in
 * [com.writer.model.ColumnModel.activeStrokes] and are serialized to disk.
 */
data class InlineTextState(
    val lineIndex: Int,
    val recognizedText: String,
    /** True = show Hershey text, hide handwritten strokes for this line. */
    val consolidated: Boolean,
    /** True = double-tapped to reveal original strokes (with border). */
    val unConsolidated: Boolean = false,
    /** Hershey-generated replacement strokes (empty until consolidated). */
    val syntheticStrokes: List<InkStroke> = emptyList(),
    /** Recognition alternatives (empty if no alternatives or single candidate). */
    val candidates: List<RecognitionCandidate> = emptyList(),
    /** True when top candidate has low confidence and alternatives should be shown inline. */
    val lowConfidence: Boolean = false,
    /** Per-word alternatives for inline display (only words that differ across candidates). */
    val wordAlternatives: List<WordAlternative> = emptyList(),
    /** Per-word confidence scores for wavy underline rendering. */
    val wordConfidences: List<com.writer.recognition.WordConfidence> = emptyList(),
)

/**
 * Tracks a pending word replacement in consolidated text.
 * Created when the user scratches out a word in Hershey text.
 * Cleared when the user writes a replacement word.
 */
data class PendingWordEdit(
    /** The original line index in lineTextCache where the word was. */
    val lineIndex: Int,
    /** The word that was scratched out. */
    val oldWord: String,
    /** Index of the word within the ORIGINAL line text (before reflow). */
    val origWordIndex: Int,
    /** X bounds of the erased word in Hershey space (for gap rendering). */
    val wordStartX: Float,
    val wordEndX: Float,
    /** X bounds of the original handwriting strokes (for relocation). */
    val origStrokeStartX: Float,
    val origStrokeEndX: Float,
    /** Y position of the original line (document space). */
    val origLineY: Float,
    /** Stroke IDs added after this edit was created (the replacement strokes). */
    val replacementStrokeIds: MutableSet<String> = mutableSetOf()
)
