package com.writer.ui.writing

import com.writer.model.AnchorTarget
import com.writer.model.AudioRecording
import com.writer.model.InkStroke
import com.writer.model.TextBlock
import com.writer.recognition.LineSegmenter

/** Result of audio↔stroke correlation: which column a TextBlock anchors to and the row inside it. */
data class Anchor(val target: AnchorTarget, val lineIndex: Int)

/**
 * Correlates a TextBlock's audio window against strokes in main and cue to choose
 * an anchor (target column + line index). Returns null when no stroke in either
 * column overlaps the window — the caller is responsible for the "place at bottom
 * of main" fallback.
 *
 * Time model: the TextBlock stores audio-relative offsets (audioStartMs,
 * audioEndMs); strokes carry wall-clock timestamps. Conversion adds the
 * recording's startTimeMs so both sides share the same epoch.
 *
 * Majority rules: target is the column with more hits; ties prefer MAIN.
 * Line index is the median (upper-middle on ties) of the winning column's hits,
 * which is robust to a single outlier stroke at the edge of the window.
 */
class TextBlockAnchorComputer(
    private val lineSegmenter: LineSegmenter = LineSegmenter(),
) {
    fun computeAnchor(
        block: TextBlock,
        mainStrokes: List<InkStroke>,
        cueStrokes: List<InkStroke>,
        recordings: List<AudioRecording>,
    ): Anchor? {
        val rec = recordings.firstOrNull { it.audioFile == block.audioFile } ?: return null
        val absStart = rec.startTimeMs + block.audioStartMs
        val absEnd = rec.startTimeMs + block.audioEndMs
        val mainHits = mainStrokes.filter { it.startTime in absStart..absEnd }
        val cueHits = cueStrokes.filter { it.startTime in absStart..absEnd }
        if (mainHits.isEmpty() && cueHits.isEmpty()) return null
        val target = if (mainHits.size >= cueHits.size) AnchorTarget.MAIN else AnchorTarget.CUE
        val hits = if (target == AnchorTarget.MAIN) mainHits else cueHits
        val lines = hits.map { lineSegmenter.getStrokeLineIndex(it) }.sorted()
        return Anchor(target, lines[lines.size / 2])
    }
}
