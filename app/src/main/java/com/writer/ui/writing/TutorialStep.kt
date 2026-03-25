package com.writer.ui.writing

import android.graphics.Rect

/**
 * A single step in the interactive guided tutorial.
 *
 * Each step highlights a zone on screen, shows a tooltip, and waits for
 * the user to perform a specific action before advancing.
 */
data class TutorialStep(
    /** Unique identifier for this step. */
    val id: String,
    /** Screen region to reveal (cutout in the dimmed overlay). Null = full screen. */
    val cutoutRect: Rect?,
    /** Instruction text shown in the tooltip. */
    val tooltipText: String,
    /** Where to position the tooltip relative to the cutout. */
    val tooltipPosition: TooltipPosition,
    /** What input type this step accepts in the cutout zone. */
    val acceptsInput: InputType
) {
    enum class TooltipPosition {
        ABOVE, BELOW, CENTER
    }

    enum class InputType {
        /** Only stylus/pen input accepted. */
        PEN,
        /** Only finger input accepted. */
        FINGER,
        /** Any input accepted. */
        ANY
    }
}
