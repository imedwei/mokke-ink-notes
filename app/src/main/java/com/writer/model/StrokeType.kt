package com.writer.model

enum class StrokeType {
    FREEHAND,
    LINE,
    ARROW_HEAD,          // arrowhead at end (→)
    ARROW_TAIL,          // arrowhead at start (←)
    ARROW_BOTH,          // bidirectional (↔)
    ELLIPSE,
    RECTANGLE,
    ROUNDED_RECTANGLE,
    TRIANGLE,
    DIAMOND;

    /** True for LINE, ARROW_HEAD, ARROW_TAIL, ARROW_BOTH — strokes rendered as a single segment. */
    val isArrowOrLine: Boolean get() = this == LINE || this == ARROW_HEAD || this == ARROW_TAIL || this == ARROW_BOTH
}
