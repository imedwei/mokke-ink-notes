package com.writer.recognition

import android.graphics.RectF
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
     * HWR bounding boxes are in the recognizer's internal coordinate space
     * (not pixels). The [inkLineBounds] parameter provides the pixel-space
     * bounding box of the input strokes, used to transform HWR coordinates
     * to pixel space.
     *
     * @param wordConfidences per-word data including bounding boxes (null boxes are skipped)
     * @param strokes the input strokes that were sent to recognition
     * @param inkLineBounds pixel-space bounding box of the InkLine (null = assume coords match)
     * @return word index → set of stroke IDs, or empty map if no bounding boxes available
     */
    fun buildWordStrokeMapping(
        wordConfidences: List<WordConfidence>,
        strokes: List<InkStroke>,
        inkLineBounds: RectF? = null
    ): Map<Int, Set<String>> {
        val wordsWithBoxes = wordConfidences.filter { it.boundingBox != null }
        if (wordsWithBoxes.isEmpty() || strokes.isEmpty()) return emptyMap()

        // Compute transform from HWR coordinate space to pixel space.
        // HWR extent = bounding box of all word bounding boxes.
        // Pixel extent = InkLine bounding box.
        val hwrMinX = wordsWithBoxes.minOf { it.boundingBox!!.x }
        val hwrMaxX = wordsWithBoxes.maxOf { it.boundingBox!!.x + it.boundingBox!!.width }
        val hwrWidth = hwrMaxX - hwrMinX

        val pixelLeft: Float
        val pixelWidth: Float
        if (inkLineBounds != null && hwrWidth > 0f) {
            pixelLeft = inkLineBounds.left
            pixelWidth = inkLineBounds.width()
        } else {
            // No transform — assume coordinates already match (tests)
            pixelLeft = 0f
            pixelWidth = 0f
        }
        val needsTransform = pixelWidth > 0f && hwrWidth > 0f
        val scaleX = if (needsTransform) pixelWidth / hwrWidth else 1f
        val offsetX = if (needsTransform) pixelLeft - hwrMinX * scaleX else 0f

        val mapping = mutableMapOf<Int, MutableSet<String>>()
        for (wc in wordsWithBoxes) mapping[wc.wordIndex] = mutableSetOf()

        val assigned = mutableSetOf<String>()

        // Pass 1: assign strokes whose X center falls within a word's bounding box + tolerance
        for (stroke in strokes) {
            val cx = (stroke.minX + stroke.maxX) / 2f
            for (wc in wordsWithBoxes) {
                val bb = wc.boundingBox!!
                // Transform HWR bbox to pixel space
                val pixBBLeft = bb.x * scaleX + offsetX
                val pixBBWidth = bb.width * scaleX
                val tol = maxOf(pixBBWidth * 0.1f, 4f)
                if (cx >= pixBBLeft - tol && cx <= pixBBLeft + pixBBWidth + tol) {
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
                val pixBBLeft = bb.x * scaleX + offsetX
                val pixBBWidth = bb.width * scaleX
                val wordCx = pixBBLeft + pixBBWidth / 2f
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
