package com.writer.ui.writing

import com.writer.model.InkStroke

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
)
