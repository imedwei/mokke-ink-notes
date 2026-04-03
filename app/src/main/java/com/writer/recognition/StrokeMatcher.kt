package com.writer.recognition

import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.maxX

/**
 * Matches recognized word bounding boxes against input strokes to build
 * a direct word→strokeId mapping. Used to replace the heuristic N-1
 * largest gaps algorithm in [com.writer.ui.writing.WritingCoordinator.findStrokesForWord].
 */
object StrokeMatcher {

    /**
     * Build a mapping from word index to stroke IDs by matching each word's
     * bounding box (from the recognizer) against the X center of each stroke.
     *
     * @param wordConfidences per-word data including bounding boxes (null boxes are skipped)
     * @param strokes the input strokes that were sent to recognition
     * @return word index → set of stroke IDs, or empty map if no bounding boxes available
     */
    fun buildWordStrokeMapping(
        wordConfidences: List<WordConfidence>,
        strokes: List<InkStroke>
    ): Map<Int, Set<String>> {
        val wordsWithBoxes = wordConfidences.filter { it.boundingBox != null }
        if (wordsWithBoxes.isEmpty() || strokes.isEmpty()) return emptyMap()

        val mapping = mutableMapOf<Int, MutableSet<String>>()
        for (wc in wordsWithBoxes) mapping[wc.wordIndex] = mutableSetOf()

        val assigned = mutableSetOf<String>()

        // Pass 1: assign strokes whose X center falls within a word's bounding box + tolerance
        for (stroke in strokes) {
            val cx = (stroke.minX + stroke.maxX) / 2f
            for (wc in wordsWithBoxes) {
                val bb = wc.boundingBox!!
                val tol = maxOf(bb.width * 0.1f, 4f)
                if (cx >= bb.x - tol && cx <= bb.x + bb.width + tol) {
                    mapping[wc.wordIndex]!!.add(stroke.strokeId)
                    assigned.add(stroke.strokeId)
                    break  // each stroke assigned to at most one word
                }
            }
        }

        // Pass 2: assign unmatched strokes to the nearest word by X center distance
        for (stroke in strokes) {
            if (stroke.strokeId in assigned) continue
            val cx = (stroke.minX + stroke.maxX) / 2f
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (wc in wordsWithBoxes) {
                val bb = wc.boundingBox!!
                val wordCx = bb.x + bb.width / 2f
                val dist = kotlin.math.abs(cx - wordCx)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = wc.wordIndex
                }
            }
            if (bestIdx >= 0) {
                mapping[bestIdx]!!.add(stroke.strokeId)
            }
        }

        return mapping
    }
}
