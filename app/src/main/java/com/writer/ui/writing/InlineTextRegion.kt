package com.writer.ui.writing

import com.writer.model.InkStroke

/** Alternative recognition candidate for a line, with its confidence score. */
data class RecognitionCandidate(
    val text: String,
    val confidence: Float,
)

/**
 * Per-line HWR text region rendered over a main/cue canvas.
 *
 * `consolidated` means the original strokes have been replaced by Hershey-rendered
 * synthetic strokes (Phase 7). `unConsolidated` is the user-explicit opt-out state
 * and only applies to a line that would otherwise consolidate — the two flags are
 * not both true at once.
 */
data class InlineTextRegion(
    val lineIndex: Int,
    val text: String,
    val consolidated: Boolean = false,
    val unConsolidated: Boolean = false,
    val syntheticStrokes: List<InkStroke> = emptyList(),
    val candidates: List<RecognitionCandidate> = emptyList(),
    val lowConfidence: Boolean = false,
)

/**
 * Build a per-line region list from a column's recognized-text cache.
 *
 * Pure function so main and cue columns each build their own list independently.
 * A line is considered [InlineTextRegion.consolidated] once the pen has advanced
 * past it — i.e. the current line index is strictly greater than the line index.
 */
fun buildInlineTextRegions(
    lineTextCache: Map<Int, String>,
    currentLineIndex: Int,
): List<InlineTextRegion> =
    lineTextCache.entries
        .sortedBy { it.key }
        .map { (lineIndex, text) ->
            InlineTextRegion(
                lineIndex = lineIndex,
                text = text,
                consolidated = currentLineIndex > lineIndex,
            )
        }
