package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke

/** Abstraction for canvas operations needed by diagram management. */
interface DiagramCanvas {
    var diagramAreas: List<DiagramArea>
    var scrollOffsetY: Float
    fun loadStrokes(strokes: List<InkStroke>)
    /** Pause SDK overlay, redraw surface, resume SDK — for e-ink visibility. */
    fun pauseAndRedraw()
}
