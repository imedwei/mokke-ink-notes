package com.writer.ui.writing

import com.writer.view.ScreenMetrics

/**
 * Pure logic for column layout decisions in Cornell Notes mode.
 *
 * Determines whether to show dual columns, which column widths to use,
 * and what toggle behavior to apply. Separated from [WritingActivity]
 * for testability — no Android framework dependency.
 *
 * **Large screens** (Tab X C, Note Air 5C): always dual-column in both
 * orientations. Main column width is fixed at 70% of portrait width.
 * Cue gets remaining space (narrow in portrait, wide in landscape).
 * Toggle expands/contracts the cue in portrait.
 *
 * **Small screens** (Go 7, Palma 2 Pro): single column in portrait with
 * fold/unfold, 50/50 dual column in landscape (unchanged behavior).
 */
class ColumnLayoutLogic(private val host: Host) {

    interface Host {
        val isLargeScreen: Boolean
        var isLandscape: Boolean
    }

    enum class ToggleAction {
        /** Large screen portrait: toggle expands/contracts cue column. */
        EXPAND_CUE,
        /** Small screen portrait: toggle folds/unfolds between main and cue views. */
        FOLD_UNFOLD,
        /** Landscape (any screen): toggle is hidden, no action. */
        NONE
    }

    data class ColumnWidths(val mainWidthPx: Int, val cueWidthPx: Int)

    /** True when both columns should be visible side by side. */
    val isDualColumn: Boolean
        get() = host.isLargeScreen || host.isLandscape

    /** True when the cue column is expanded to landscape width in portrait. */
    var isCueExpanded: Boolean = false
        private set

    /** True when fold/unfold mode is available (small screen portrait only). */
    val canFoldUnfold: Boolean
        get() = !host.isLargeScreen && !host.isLandscape

    /** Whether to show the cue indicator strip (small screen portrait notes view). */
    val showIndicatorStrip: Boolean
        get() = !isDualColumn

    /** Whether to show the toggle button (hidden in landscape). */
    val showToggleButton: Boolean
        get() = !host.isLandscape

    /** What the toggle button does in the current state. */
    val toggleAction: ToggleAction
        get() = when {
            host.isLandscape -> ToggleAction.NONE
            host.isLargeScreen -> ToggleAction.EXPAND_CUE
            else -> ToggleAction.FOLD_UNFOLD
        }

    /** Toggle cue expand/contract. Only effective on large screen portrait. */
    fun toggleCueExpand() {
        if (!host.isLargeScreen || host.isLandscape) return
        isCueExpanded = !isCueExpanded
    }

    /** Called when orientation changes. Resets expand state. */
    fun onOrientationChanged(nowLandscape: Boolean) {
        host.isLandscape = nowLandscape
        isCueExpanded = false
    }

    /**
     * Returns the column widths in pixels for the current state.
     * On large screens, widths are fixed pixel values from [ScreenMetrics].
     * On small screens, returns zero (use layout weights instead).
     */
    fun columnWidths(): ColumnWidths {
        if (!host.isLargeScreen) return ColumnWidths(0, 0)
        return when {
            host.isLandscape -> ColumnWidths(
                mainWidthPx = ScreenMetrics.mainColumnWidthPx,
                cueWidthPx = ScreenMetrics.landscapeCueWidthPx
            )
            isCueExpanded -> ColumnWidths(
                mainWidthPx = ScreenMetrics.expandedPortraitMainWidthPx,
                cueWidthPx = ScreenMetrics.landscapeCueWidthPx
            )
            else -> ColumnWidths(
                mainWidthPx = ScreenMetrics.mainColumnWidthPx,
                cueWidthPx = ScreenMetrics.portraitCueWidthPx
            )
        }
    }
}
