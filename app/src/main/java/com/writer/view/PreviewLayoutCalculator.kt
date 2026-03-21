package com.writer.view

import com.writer.model.DiagramArea

/**
 * Pure-logic calculator for the complementary preview layout.
 *
 * Determines which lines are currently hidden (scrolled off the canvas),
 * which diagram areas are visible in the preview, and how to size/clip
 * diagram render items so the preview is flush with the canvas boundary.
 *
 * All methods are side-effect-free and depend only on their parameters,
 * making them easy to test on JVM without Android framework dependencies.
 */
object PreviewLayoutCalculator {

    // ── Line visibility ─────────────────────────────────────────────────

    /**
     * Returns the set of line indices whose bottom edge is at or above [scrollOffsetY].
     * These lines are fully scrolled off the canvas and should appear in the preview.
     */
    fun currentlyHiddenLines(
        lineIndices: Set<Int>,
        scrollOffsetY: Float,
        topMargin: Float,
        lineSpacing: Float
    ): Set<Int> = lineIndices.filter { lineIdx ->
        val lineBottom = topMargin + lineIdx * lineSpacing + lineSpacing
        lineBottom <= scrollOffsetY
    }.toSet()

    /**
     * Returns the set of line indices whose midpoint is at or above [scrollOffsetY].
     * Used for dimming: a line is "not yet visible" once its midpoint has scrolled off.
     */
    fun notYetVisibleLines(
        lineIndices: Set<Int>,
        scrollOffsetY: Float,
        topMargin: Float,
        lineSpacing: Float
    ): Set<Int> = lineIndices.filter { lineIdx ->
        val lineMid = topMargin + lineIdx * lineSpacing + lineSpacing / 2f
        lineMid <= scrollOffsetY
    }.toSet()

    // ── Diagram visibility ──────────────────────────────────────────────

    /** Result of computing diagram visibility for the preview. */
    data class DiagramVisibility(
        val startLineIndex: Int,
        /** Full area height in document pixels. */
        val fullHeight: Float,
        /** Document-space Y of the area top. */
        val areaTop: Float,
        /** How much of the diagram is scrolled off the canvas (capped at stroke bounds). */
        val visibleHeight: Float,
        /** Whether this is a partial render (not all strokes visible yet). */
        val isPartial: Boolean
    )

    /**
     * Determine which diagram areas should appear in the preview and how much
     * of each is visible. A diagram appears as soon as its top edge scrolls off
     * the canvas. The visible height is capped at the actual stroke bottom
     * (not the full area height) to avoid empty-line gaps.
     */
    fun diagramVisibilities(
        areas: List<DiagramArea>,
        scrollOffsetY: Float,
        topMargin: Float,
        lineSpacing: Float,
        /** For each area, the max Y of its strokes in document space (null if no strokes). */
        strokeMaxYByArea: Map<Int, Float>,
        strokeWidthPadding: Float
    ): List<DiagramVisibility> {
        return areas.mapNotNull { area ->
            val areaTop = topMargin + area.startLineIndex * lineSpacing
            if (areaTop >= scrollOffsetY) return@mapNotNull null // not scrolled off yet

            val fullHeight = area.heightInLines * lineSpacing
            val scrolledOff = (scrollOffsetY - areaTop).coerceIn(0f, fullHeight)

            val strokeMaxY = strokeMaxYByArea[area.startLineIndex]
            val strokeBottom = if (strokeMaxY != null) {
                (strokeMaxY - areaTop + strokeWidthPadding).coerceAtMost(fullHeight)
            } else {
                fullHeight
            }

            val visibleHeight = scrolledOff.coerceAtMost(strokeBottom)

            DiagramVisibility(
                startLineIndex = area.startLineIndex,
                fullHeight = fullHeight,
                areaTop = areaTop,
                visibleHeight = visibleHeight,
                isPartial = visibleHeight < fullHeight
            )
        }
    }

    // ── Render item layout ──────────────────────────────────────────────

    /** Computed layout metrics for a diagram render item. */
    data class DiagramRenderMetrics(
        val scale: Float,
        val fullRenderedHeight: Float,
        val renderedHeight: Float,
        val isPartial: Boolean
    )

    /**
     * Compute the render metrics for a diagram in the preview.
     *
     * @param visibleHeight  How much of the diagram is scrolled off (document px)
     * @param fullHeight     Full diagram area height (document px)
     * @param canvasWidth    Width of the canvas (source coordinate space)
     * @param textViewWidth  Width of the text view (target coordinate space)
     * @param paragraphSpacing  Spacing added between render items
     */
    fun diagramRenderMetrics(
        visibleHeight: Float,
        fullHeight: Float,
        canvasWidth: Float,
        textViewWidth: Float,
        paragraphSpacing: Float
    ): DiagramRenderMetrics {
        val scale = if (canvasWidth > 0f) textViewWidth / canvasWidth else 1f
        val isPartial = visibleHeight < fullHeight
        val fullRenderedHeight = fullHeight * scale + paragraphSpacing
        val renderedHeight = visibleHeight * scale + if (isPartial) 0f else paragraphSpacing

        return DiagramRenderMetrics(
            scale = scale,
            fullRenderedHeight = fullRenderedHeight,
            renderedHeight = renderedHeight,
            isPartial = isPartial
        )
    }

    /**
     * Strip trailing spacing from the last item height so the preview content
     * is flush with the divider. Returns the trimmed height.
     *
     * @param heightPx         The item's current rendered height
     * @param fullHeightPx     The item's full rendered height (including spacing)
     * @param paragraphSpacing Spacing to remove
     * @param isText           True if text item, false if diagram
     * @param textLayoutHeight The text StaticLayout height (only used for text items)
     */
    fun trimLastItemHeight(
        heightPx: Float,
        fullHeightPx: Float,
        paragraphSpacing: Float,
        isText: Boolean,
        textLayoutHeight: Float = 0f
    ): Float {
        return if (isText) {
            textLayoutHeight
        } else {
            heightPx.coerceAtMost(fullHeightPx - paragraphSpacing)
        }
    }
}
