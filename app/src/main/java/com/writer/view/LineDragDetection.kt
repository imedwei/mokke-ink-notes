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

    /** Minimum vertical span (in line spacings) to classify a stroke as a line-drag. */
    const val MIN_SPANS = 1f

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
}
