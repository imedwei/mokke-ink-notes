package com.writer.ui.writing

import com.writer.view.ScreenMetrics

/**
 * Pure logic for column layout decisions in Cornell Notes mode.
 *
 * Determines whether to show dual columns, which column widths to use,
 * what toggle behavior to apply, and — post v6 — whether and how to
 * render the transcript column. Separated from [WritingActivity] for
 * testability: no Android framework dependency.
 *
 * **Large screens** (Tab X C, Note Air 5C): always dual-column main+cue
 * in both orientations. Main column width is fixed at 70% of portrait
 * width. Cue gets remaining space (narrow in portrait, wide in landscape).
 *
 * **Small screens** (Go 7, Palma 2 Pro): single column in portrait with
 * fold/unfold, 50/50 dual column in landscape.
 *
 * **Transcript column:** appears when the document has ≥1 AudioRecording.
 * Renders side-by-side on large+landscape, as a drawer in other dual-fit
 * form factors, and as a fold stop on small portrait (three-way fold).
 */
class ColumnLayoutLogic(private val host: Host) {

    interface Host {
        val isLargeScreen: Boolean
        var isLandscape: Boolean
        /** True iff the document has at least one AudioRecording. Drives transcript visibility. */
        val hasAnyRecording: Boolean
    }

    enum class ToggleAction {
        /** Large screen portrait: toggle expands/contracts cue column. */
        EXPAND_CUE,
        /** Small screen portrait: toggle folds/unfolds between main and cue views. */
        FOLD_UNFOLD,
        /** Landscape (any screen): toggle is hidden, no action. */
        NONE
    }

    /** Which stroke column currently owns pen input (and undo/redo scope). */
    enum class ActiveColumn { MAIN, CUE, TRANSCRIPT }

    /** How the transcript column is rendered when visible. */
    enum class TranscriptDisplayMode {
        /** Three columns side by side (large+landscape). */
        SIDE_BY_SIDE,
        /** Slide-over drawer triggered by a toolbar button (large+portrait, small+landscape). */
        DRAWER,
        /** Third fold state (small+portrait): fold/unfold cycles main ↔ cue ↔ transcript. */
        FOLD,
    }

    data class ColumnWidths(val mainWidthPx: Int, val cueWidthPx: Int)

    /** True when the main + cue columns are both visible side by side. */
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

    /** Cached transcript visibility — derived from [Host.hasAnyRecording] at construction
     *  and updated through [onRecordingsChanged]. Cached (rather than recomputed on every
     *  read) so we can detect transitions and fire [onTranscriptVisibilityChanged]. */
    var transcriptVisible: Boolean = host.hasAnyRecording
        private set

    /** Fires exactly on transitions of [transcriptVisible] — host uses this to slide the
     *  drawer in/out or collapse the three-column layout. */
    var onTranscriptVisibilityChanged: ((Boolean) -> Unit)? = null

    /** How the transcript column should render when visible, per form factor. */
    val transcriptDisplayMode: TranscriptDisplayMode
        get() = when {
            host.isLargeScreen && host.isLandscape -> TranscriptDisplayMode.SIDE_BY_SIDE
            !host.isLargeScreen && !host.isLandscape -> TranscriptDisplayMode.FOLD
            else -> TranscriptDisplayMode.DRAWER
        }

    /** Which column currently owns pen input / undo-redo scope. Default MAIN. */
    var activeColumn: ActiveColumn = ActiveColumn.MAIN

    /** Toggle cue expand/contract. Only effective on large screen portrait. */
    fun toggleCueExpand() {
        if (!host.isLargeScreen || host.isLandscape) return
        isCueExpanded = !isCueExpanded
    }

    /** Called when orientation changes. Resets cue-expand state. Transcript visibility
     *  is tied to document contents, not orientation, and survives the rotation. */
    fun onOrientationChanged(nowLandscape: Boolean) {
        host.isLandscape = nowLandscape
        isCueExpanded = false
    }

    /** Called by the activity after an AudioRecording is added or removed from the document. */
    fun onRecordingsChanged() {
        val nowVisible = host.hasAnyRecording
        if (nowVisible == transcriptVisible) return
        transcriptVisible = nowVisible
        onTranscriptVisibilityChanged?.invoke(nowVisible)
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
