package com.writer.ui.writing

import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ColumnLayoutLogicTest {

    companion object {
        const val DENSITY = 1.875f

        // Tab X C: large screen, 3200×2400
        const val SW_TAB_X_C = 1280
        const val W_TAB_X_C = 3200
        const val H_TAB_X_C = 2400

        // Go 7: small screen, 1264×1680
        const val SW_GO_7 = 674
        const val W_GO_7 = 1264
        const val H_GO_7 = 1680

        // Palma 2 Pro: small screen, 824×1648
        const val SW_PALMA = 439
        const val W_PALMA = 824
        const val H_PALMA = 1648
    }

    private lateinit var host: FakeHost
    private lateinit var logic: ColumnLayoutLogic

    @Before
    fun setUp() {
        initTabXC()
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
    }

    private fun initTabXC() =
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_TAB_X_C, widthPixels = W_TAB_X_C, heightPixels = H_TAB_X_C)
    private fun initGo7() =
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_GO_7, widthPixels = W_GO_7, heightPixels = H_GO_7)
    private fun initPalma() =
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_PALMA, widthPixels = W_PALMA, heightPixels = H_PALMA)

    // ── isDualColumn ────────────────────────────────────────────────────────

    @Test
    fun `large screen portrait is dual column`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.isDualColumn)
    }

    @Test
    fun `large screen landscape is dual column`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.isDualColumn)
    }

    @Test
    fun `small screen portrait is not dual column`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.isDualColumn)
    }

    @Test
    fun `small screen landscape is dual column`() {
        host = FakeHost(isLargeScreen = false, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.isDualColumn)
    }

    // ── showIndicatorStrip ──────────────────────────────────────────────────

    @Test
    fun `indicator strip hidden on large screen portrait`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.showIndicatorStrip)
    }

    @Test
    fun `indicator strip hidden on small screen landscape`() {
        host = FakeHost(isLargeScreen = false, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.showIndicatorStrip)
    }

    @Test
    fun `indicator strip shown on small screen portrait`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.showIndicatorStrip)
    }

    // ── showToggleButton ────────────────────────────────────────────────────

    @Test
    fun `toggle button shown in portrait`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.showToggleButton)
    }

    @Test
    fun `toggle button hidden in landscape`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.showToggleButton)
    }

    @Test
    fun `toggle button shown on small screen portrait`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.showToggleButton)
    }

    @Test
    fun `toggle button hidden on small screen landscape`() {
        host = FakeHost(isLargeScreen = false, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.showToggleButton)
    }

    // ── toggleAction ────────────────────────────────────────────────────────

    @Test
    fun `toggle action is expand_cue on large screen portrait`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertEquals(ColumnLayoutLogic.ToggleAction.EXPAND_CUE, logic.toggleAction)
    }

    @Test
    fun `toggle action is fold_unfold on small screen portrait`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertEquals(ColumnLayoutLogic.ToggleAction.FOLD_UNFOLD, logic.toggleAction)
    }

    @Test
    fun `toggle action is none in landscape`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertEquals(ColumnLayoutLogic.ToggleAction.NONE, logic.toggleAction)
    }

    // ── cue expand/contract ─────────────────────────────────────────────────

    @Test
    fun `cue starts not expanded`() {
        assertFalse(logic.isCueExpanded)
    }

    @Test
    fun `toggleCueExpand expands on large screen portrait`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        assertTrue(logic.isCueExpanded)
    }

    @Test
    fun `toggleCueExpand contracts when already expanded`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        logic.toggleCueExpand()
        assertFalse(logic.isCueExpanded)
    }

    @Test
    fun `toggleCueExpand is noop in landscape`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        assertFalse(logic.isCueExpanded)
    }

    @Test
    fun `toggleCueExpand is noop on small screen`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        assertFalse(logic.isCueExpanded)
    }

    // ── onOrientationChanged ────────────────────────────────────────────────

    @Test
    fun `rotation resets cue expanded`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        assertTrue(logic.isCueExpanded)
        logic.onOrientationChanged(nowLandscape = true)
        assertFalse(logic.isCueExpanded)
    }

    @Test
    fun `rotation updates isDualColumn for small screen`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.isDualColumn)
        host.isLandscape = true
        assertTrue(logic.isDualColumn)
    }

    // ── column widths (large screen) ────────────────────────────────────────

    @Test
    fun `large screen portrait default uses ScreenMetrics main and portrait cue`() {
        initTabXC()
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        val widths = logic.columnWidths()
        assertEquals(ScreenMetrics.mainColumnWidthPx, widths.mainWidthPx)
        assertEquals(ScreenMetrics.portraitCueWidthPx, widths.cueWidthPx)
    }

    @Test
    fun `large screen landscape uses ScreenMetrics main and landscape cue`() {
        initTabXC()
        host = FakeHost(isLargeScreen = true, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        val widths = logic.columnWidths()
        assertEquals(ScreenMetrics.mainColumnWidthPx, widths.mainWidthPx)
        assertEquals(ScreenMetrics.landscapeCueWidthPx, widths.cueWidthPx)
    }

    @Test
    fun `large screen portrait expanded uses landscape cue width`() {
        initTabXC()
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        val widths = logic.columnWidths()
        assertEquals(ScreenMetrics.expandedPortraitMainWidthPx, widths.mainWidthPx)
        assertEquals(ScreenMetrics.landscapeCueWidthPx, widths.cueWidthPx)
    }

    @Test
    fun `small screen returns zero column widths`() {
        initGo7()
        host = FakeHost(isLargeScreen = false, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        val widths = logic.columnWidths()
        assertEquals(0, widths.mainWidthPx)
        assertEquals(0, widths.cueWidthPx)
    }

    // ── fold/unfold applicability ───────────────────────────────────────────

    @Test
    fun `canFoldUnfold is true on small screen portrait`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.canFoldUnfold)
    }

    @Test
    fun `canFoldUnfold is false on large screen`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.canFoldUnfold)
    }

    @Test
    fun `canFoldUnfold is false in landscape`() {
        host = FakeHost(isLargeScreen = false, isLandscape = true)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.canFoldUnfold)
    }

    // ── Phase 3b: transcript column visibility + tri-state activeColumn ────

    @Test
    fun `transcript hidden when no recordings`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = false)
        logic = ColumnLayoutLogic(host)
        assertFalse("transcript hides when document has no audio", logic.transcriptVisible)
    }

    @Test
    fun `transcript visible appears after first recording`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = false)
        logic = ColumnLayoutLogic(host)
        assertFalse(logic.transcriptVisible)

        host.hasAnyRecording = true
        logic.onRecordingsChanged()

        assertTrue("transcript becomes visible after first recording", logic.transcriptVisible)
    }

    @Test
    fun `transcript hides when last recording deleted`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        assertTrue(logic.transcriptVisible)

        host.hasAnyRecording = false
        logic.onRecordingsChanged()

        assertFalse("transcript hides when last recording removed", logic.transcriptVisible)
    }

    @Test
    fun `transcript visibility fires listener`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = false)
        logic = ColumnLayoutLogic(host)
        val changes = mutableListOf<Boolean>()
        logic.onTranscriptVisibilityChanged = { changes.add(it) }

        host.hasAnyRecording = true
        logic.onRecordingsChanged()
        host.hasAnyRecording = false
        logic.onRecordingsChanged()

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `visibility listener does not fire when unchanged`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        val changes = mutableListOf<Boolean>()
        logic.onTranscriptVisibilityChanged = { changes.add(it) }

        // Same state — no fire
        logic.onRecordingsChanged()
        assertTrue("no listener fire when state is unchanged", changes.isEmpty())
    }

    // ── Phase 3b: transcriptDisplayMode per form factor ─────────────────────

    @Test
    fun `tabXc landscape shows three columns side by side`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        assertEquals(
            ColumnLayoutLogic.TranscriptDisplayMode.SIDE_BY_SIDE,
            logic.transcriptDisplayMode
        )
    }

    @Test
    fun `tabXc portrait renders transcript as drawer`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        assertEquals(
            ColumnLayoutLogic.TranscriptDisplayMode.DRAWER,
            logic.transcriptDisplayMode
        )
    }

    @Test
    fun `palma landscape renders transcript as drawer`() {
        // Three side-by-side columns don't fit on small landscape — use a drawer.
        host = FakeHost(isLargeScreen = false, isLandscape = true, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        assertEquals(
            ColumnLayoutLogic.TranscriptDisplayMode.DRAWER,
            logic.transcriptDisplayMode
        )
    }

    @Test
    fun `palma portrait fold unfold cycles three views`() {
        host = FakeHost(isLargeScreen = false, isLandscape = false, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        assertEquals(
            ColumnLayoutLogic.TranscriptDisplayMode.FOLD,
            logic.transcriptDisplayMode
        )
    }

    // ── Phase 3b: tri-state activeColumn ────────────────────────────────────

    @Test
    fun `activeColumn tri-state default is MAIN`() {
        assertEquals(ColumnLayoutLogic.ActiveColumn.MAIN, logic.activeColumn)
    }

    @Test
    fun `activeColumn tri-state transitions MAIN to CUE to TRANSCRIPT`() {
        host = FakeHost(isLargeScreen = true, isLandscape = true, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)

        logic.activeColumn = ColumnLayoutLogic.ActiveColumn.CUE
        assertEquals(ColumnLayoutLogic.ActiveColumn.CUE, logic.activeColumn)

        logic.activeColumn = ColumnLayoutLogic.ActiveColumn.TRANSCRIPT
        assertEquals(ColumnLayoutLogic.ActiveColumn.TRANSCRIPT, logic.activeColumn)

        logic.activeColumn = ColumnLayoutLogic.ActiveColumn.MAIN
        assertEquals(ColumnLayoutLogic.ActiveColumn.MAIN, logic.activeColumn)
    }

    // ── Phase 3b: orientation change behavior ───────────────────────────────

    @Test
    fun `orientation change preserves transcript visibility and resets cue expand`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        logic.toggleCueExpand()
        assertTrue(logic.isCueExpanded)
        assertTrue(logic.transcriptVisible)

        logic.onOrientationChanged(nowLandscape = true)

        assertFalse("cue expand resets on rotation", logic.isCueExpanded)
        assertTrue("transcript visibility persists across rotation", logic.transcriptVisible)
    }

    @Test
    fun `toggleCueExpand does not alter transcriptVisible`() {
        host = FakeHost(isLargeScreen = true, isLandscape = false, hasAnyRecording = true)
        logic = ColumnLayoutLogic(host)
        val before = logic.transcriptVisible
        logic.toggleCueExpand()
        assertEquals("cue expand does not touch transcript visibility", before, logic.transcriptVisible)
    }

    // ── Fake host ───────────────────────────────────────────────────────────

    private class FakeHost(
        override val isLargeScreen: Boolean,
        override var isLandscape: Boolean,
        override var hasAnyRecording: Boolean = false,
    ) : ColumnLayoutLogic.Host
}
