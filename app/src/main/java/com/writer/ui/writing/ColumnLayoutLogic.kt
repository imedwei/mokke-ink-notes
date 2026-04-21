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

    data class ColumnWidths(
        val mainWidthPx: Int,
        val cueWidthPx: Int,
        val transcriptWidthPx: Int = 0,
    )

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

    /** Which column currently owns pen input / undo-redo scope. In FOLD mode this
     *  is also the fold-view state machine (which of main / cue / transcript the
     *  user is viewing full-screen). Default MAIN. */
    var activeColumn: ActiveColumn = ActiveColumn.MAIN
        set(value) {
            if (field == value) return
            field = value
            onActiveColumnChanged?.invoke(value)
        }

    /** Fires on transitions of [activeColumn] — activity uses this to swap
     *  visible canvas in FOLD mode (small portrait). */
    var onActiveColumnChanged: ((ActiveColumn) -> Unit)? = null

    /** Whether the transcript drawer is currently open. Only meaningful in DRAWER mode;
     *  SIDE_BY_SIDE has no drawer concept (transcript is always in-flow when visible)
     *  and FOLD uses the three-way fold instead. Auto-resets to closed on rotation
     *  or when the transcript column becomes hidden. */
    var isTranscriptDrawerOpen: Boolean = false
        private set

    /** Toggle cue expand/contract. Only effective on large screen portrait. */
    fun toggleCueExpand() {
        if (!host.isLargeScreen || host.isLandscape) return
        isCueExpanded = !isCueExpanded
    }

    /** Toggle the transcript drawer open/closed. Only effective when transcript is
     *  visible AND the display mode is DRAWER (large+portrait, small+landscape). */
    fun toggleTranscriptDrawer() {
        if (!transcriptVisible) return
        if (transcriptDisplayMode != TranscriptDisplayMode.DRAWER) return
        isTranscriptDrawerOpen = !isTranscriptDrawerOpen
    }

    /** Advance the fold-view state machine: MAIN → CUE → (TRANSCRIPT if visible) → MAIN.
     *  Only applies in FOLD mode (small screen, portrait). In other modes the user has
     *  other navigation affordances (side-by-side columns, drawer) and fold cycling is a
     *  no-op. Fires [onActiveColumnChanged] on every real transition. */
    fun cycleFold() {
        if (!canFoldUnfold) return
        val next = when (activeColumn) {
            ActiveColumn.MAIN -> ActiveColumn.CUE
            ActiveColumn.CUE -> if (transcriptVisible) ActiveColumn.TRANSCRIPT else ActiveColumn.MAIN
            ActiveColumn.TRANSCRIPT -> ActiveColumn.MAIN
        }
        activeColumn = next
    }

    /** Called when orientation changes. Resets cue-expand, drawer, and fold-view
     *  state. Transcript visibility itself is tied to document contents, not
     *  orientation, and survives. */
    fun onOrientationChanged(nowLandscape: Boolean) {
        host.isLandscape = nowLandscape
        isCueExpanded = false
        isTranscriptDrawerOpen = false
        activeColumn = ActiveColumn.MAIN
    }

    /** Called by the activity after an AudioRecording is added or removed from the document. */
    fun onRecordingsChanged() {
        val nowVisible = host.hasAnyRecording
        if (nowVisible == transcriptVisible) return
        transcriptVisible = nowVisible
        if (!nowVisible) {
            isTranscriptDrawerOpen = false
            // If the user was stranded on the transcript fold-view when the column
            // disappeared, revert them to MAIN rather than leaving them on an
            // invisible column.
            if (activeColumn == ActiveColumn.TRANSCRIPT) activeColumn = ActiveColumn.MAIN
        }
        onTranscriptVisibilityChanged?.invoke(nowVisible)
    }

    /**
     * Returns the column widths in pixels for the current state.
     * On large screens, widths are fixed pixel values from [ScreenMetrics].
     * On small screens, returns zero (use layout weights instead).
     *
     * When the transcript column is visible in SIDE_BY_SIDE mode, its width is
     * carved out of the cue column — never main — so main's writing space stays
     * fixed across the two-col → three-col transition.
     */
    fun columnWidths(): ColumnWidths {
        if (!host.isLargeScreen) return ColumnWidths(0, 0)
        val transcript = when {
            !transcriptVisible -> 0
            transcriptDisplayMode == TranscriptDisplayMode.SIDE_BY_SIDE ->
                ScreenMetrics.portraitCueWidthPx
            transcriptDisplayMode == TranscriptDisplayMode.DRAWER && isTranscriptDrawerOpen ->
                ScreenMetrics.portraitCueWidthPx
            else -> 0
        }
        return when {
            host.isLandscape -> ColumnWidths(
                mainWidthPx = ScreenMetrics.mainColumnWidthPx,
                cueWidthPx = ScreenMetrics.landscapeCueWidthPx - transcript,
                transcriptWidthPx = transcript,
            )
            isCueExpanded -> ColumnWidths(
                mainWidthPx = ScreenMetrics.expandedPortraitMainWidthPx,
                cueWidthPx = ScreenMetrics.landscapeCueWidthPx,
            )
            else -> ColumnWidths(
                mainWidthPx = ScreenMetrics.mainColumnWidthPx,
                cueWidthPx = ScreenMetrics.portraitCueWidthPx,
                transcriptWidthPx = transcript,
            )
        }
    }
}
