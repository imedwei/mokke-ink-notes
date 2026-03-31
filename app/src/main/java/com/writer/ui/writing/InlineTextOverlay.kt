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
 * Diff candidate strings to find which specific words differ.
 * Returns a list of word positions where candidates disagree.
 */
fun findWordAlternatives(candidates: List<RecognitionCandidate>): List<WordAlternative> {
    if (candidates.size < 2) return emptyList()
    val topWords = candidates[0].text.split(" ")
    if (topWords.isEmpty()) return emptyList()

    val result = mutableListOf<WordAlternative>()
    for ((i, topWord) in topWords.withIndex()) {
        val alts = mutableSetOf<String>()
        for (c in candidates.drop(1)) {
            val cWords = c.text.split(" ")
            if (i < cWords.size && cWords[i] != topWord) {
                alts.add(cWords[i])
            }
        }
        if (alts.isNotEmpty()) {
            result.add(WordAlternative(
                wordIndex = i,
                word = topWord,
                alternatives = alts.take(3).toList()
            ))
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
)
