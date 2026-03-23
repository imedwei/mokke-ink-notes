package com.writer.view

import kotlin.math.abs

/**
 * Pure geometry functions for undo/redo gesture detection.
 *
 * The gesture is an L-shaped stroke: wide horizontal extent followed by a net
 * vertical displacement. It is evaluated **post-stroke** (after pen-up), so the
 * SDK is never disabled mid-draw and box drawing is not affected.
 *
 * ## Why post-stroke is safe for boxes
 *
 * Each box side is drawn as a separate stroke with the pen lifted at corners.
 * - A pure horizontal stroke (box top/bottom): xRange is large but net yDelta
 *   is negligible — the user lifts before travelling far vertically.
 * - A pure vertical stroke (box side): xRange is small — the horizontal
 *   threshold is not met.
 * Only a deliberate L-shaped stroke (horizontal then continuous vertical) meets
 * both criteria simultaneously.
 *
 * ## Threshold rationale
 *
 * | Constant                 | Standard (118 px LS) | Compact (77 px LS) |
 * |--------------------------|----------------------|--------------------|
 * | HORIZONTAL_MIN_SPANS 1.5 | ≈ 177 px / 15 mm     | ≈ 115 px / 10 mm   |
 * | VERTICAL_ACTIVATION  1.0 | ≈ 118 px / 10 mm     | ≈  77 px /  6.5 mm |
 *
 * Extracted from [HandwritingCanvasView] to allow JVM unit testing without
 * Android framework dependencies.
 */
object UndoGestureDetection {

    /**
     * Minimum horizontal bounding-box width (in line spacings) for the trigger.
     * Requires a clearly intentional horizontal stroke.
     */
    const val HORIZONTAL_MIN_SPANS = 1.5f

    /**
     * Maximum vertical bounding-box height as a fraction of xRange while
     * evaluating the horizontal component. Keeps the stroke flat.
     *
     * Used by [isHorizontalTrigger] for mid-stroke (legacy) checks and unit tests.
     */
    const val HORIZONTAL_MAX_DRIFT = 0.2f

    /**
     * Minimum net vertical displacement (in line spacings) required to classify
     * the stroke as an undo gesture. Must exceed a typical box-corner dip.
     */
    const val VERTICAL_ACTIVATION_SPANS = 1.0f

    /**
     * Detect a post-stroke undo/redo gesture.
     *
     * Returns the scrub offset (negative = undo steps, positive = redo steps)
     * proportional to the stroke's vertical displacement, or null if the stroke
     * does not have the undo L-shape.
     *
     * @param firstY      doc-space Y of stroke start
     * @param lastY       doc-space Y of stroke end
     * @param xRange      horizontal bounding-box width: maxX − minX
     * @param lineSpacing line spacing in pixels
     * @param stepSize    pixels of vertical travel per one scrub step
     */
    fun detect(
        firstY: Float,
        lastY: Float,
        xRange: Float,
        lineSpacing: Float,
        stepSize: Float
    ): Int? {
        if (xRange <= HORIZONTAL_MIN_SPANS * lineSpacing) return null
        val yDelta = lastY - firstY
        if (abs(yDelta) <= VERTICAL_ACTIVATION_SPANS * lineSpacing) return null
        return (yDelta / stepSize).toInt()
    }

    /**
     * Returns true if a stroke has sufficient horizontal extent and is flat
     * enough to qualify as the horizontal undo trigger.
     *
     * Kept for unit tests. The production path uses [detect] instead.
     */
    fun isHorizontalTrigger(xRange: Float, yRange: Float, lineSpacing: Float): Boolean =
        xRange > HORIZONTAL_MIN_SPANS * lineSpacing &&
        yRange < xRange * HORIZONTAL_MAX_DRIFT

    /**
     * Returns true if the pen has moved far enough vertically from the trigger
     * point to activate the scrub.
     *
     * Kept for unit tests. The production path uses [detect] instead.
     */
    fun isVerticalActivation(verticalDisplacement: Float, lineSpacing: Float): Boolean =
        abs(verticalDisplacement) > VERTICAL_ACTIVATION_SPANS * lineSpacing
}
