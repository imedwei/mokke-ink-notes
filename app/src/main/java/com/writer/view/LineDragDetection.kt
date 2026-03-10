package com.writer.view

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry functions for line-drag gesture detection.
 *
 * Extracted from [HandwritingCanvasView] to allow JVM unit testing
 * without Android framework dependencies.
 */
object LineDragDetection {

    /**
     * Minimum vertical span (in line spacings) to classify a stroke as a line-drag.
     *
     * Set to 2.0 so the gesture must span two full line spacings (≈ 15 mm at 300 PPI).
     * This is far taller than any single handwritten letter (including capitals and
     * ascenders) and eliminates the false positives that caused writing strokes near
     * the right margin to be silently consumed after a diagram was drawn (Bug #6).
     */
    const val MIN_SPANS = 2f

    /**
     * Maximum allowed horizontal drift as a fraction of the stroke's vertical span.
     * A perfectly vertical stroke has drift 0; a diagonal has drift ≥ 1.
     */
    const val MAX_DRIFT = 0.3f

    /**
     * Determine whether a completed stroke has the shape of a line-drag gesture.
     *
     * A line-drag is a nearly-vertical stroke: its net vertical displacement must
     * exceed [MIN_SPANS] line spacings, and its horizontal bounding-box width must
     * be less than [MAX_DRIFT] times the vertical displacement.
     *
     * @param firstY      doc-space Y of stroke start (first point)
     * @param lastY       doc-space Y of stroke end (last point)
     * @param xRange      horizontal bounding-box width: maxX − minX across all points
     * @param lineSpacing line spacing in pixels
     * @return the shift in lines (positive = downward, negative = upward),
     *         or null if the stroke is not a line-drag
     */
    fun detect(firstY: Float, lastY: Float, xRange: Float, lineSpacing: Float): Int? {
        val yDelta = lastY - firstY
        val absYDelta = abs(yDelta)
        if (absYDelta <= MIN_SPANS * lineSpacing) return null
        if (xRange >= absYDelta * MAX_DRIFT) return null
        return (yDelta / lineSpacing).roundToInt()
    }

    /**
     * Returns true if the stroke would snap to a straight line via [ShapeSnapDetection],
     * meaning it should NOT be consumed as a line-drag gesture.
     */
    fun isSnappableLine(xs: FloatArray, ys: FloatArray, lineSpacing: Float): Boolean {
        return ShapeSnapDetection.detect(xs, ys, lineSpacing) is ShapeSnapDetection.SnapResult.Line
    }

    /**
     * Returns true if the stroke's leftmost X position is within the valid line-drag zone:
     * the rightmost [gutterWidth] px of the writing area (i.e. the column immediately to
     * the left of the scroll gutter).
     *
     * This guard prevents tall narrow writing strokes (ascender letters, digit '1', etc.)
     * in the main text area from being falsely consumed as line-drag gestures.  Only
     * deliberate gestures drawn right beside the gutter should trigger a drag.
     *
     * @param strokeMinX  leftmost X coordinate of the stroke (document space)
     * @param canvasWidth full width of the writing canvas in px (before gutter is excluded)
     * @param gutterWidth width of the scroll gutter in px
     */
    fun isInDragZone(strokeMinX: Float, canvasWidth: Float, gutterWidth: Float): Boolean {
        return strokeMinX >= canvasWidth - gutterWidth * 2
    }
}
