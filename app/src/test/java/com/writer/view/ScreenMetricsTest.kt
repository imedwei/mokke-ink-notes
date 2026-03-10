package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ScreenMetrics].
 *
 * Uses the plain-float [ScreenMetrics.init] overload so no Android framework
 * dependency is needed — these run on the JVM with plain JUnit.
 *
 * Device specs (verified via official BOOX product pages, 2025):
 *
 *   Tab X C    : 13.3" 3200×2400 (landscape), 300 PPI B&W, density = 300/160 = 1.875
 *   Note Air 5C: 10.3" 2480×1860 (landscape), 300 PPI B&W, density = 1.875
 *   Go 7       :  7.0" 1264×1680 (portrait),  300 PPI,     density = 1.875
 *   Palma 2 Pro:  6.1"  824×1648 (portrait),  300 PPI B&W, density = 1.875
 *
 * All four Onyx Boox devices run at 300 PPI (density = 1.875).
 * smallestWidthDp = narrowest axis in pixels / density.
 */
class ScreenMetricsTest {

    companion object {
        const val DENSITY = 1.875f   // 300 PPI / 160 — same for all four devices

        // Tab X C: 13.3" landscape, 3200×2400
        const val SW_TAB_X_C    = 1280  // 2400 / 1.875
        const val W_TAB_X_C     = 3200
        const val H_TAB_X_C     = 2400

        // Note Air 5C: 10.3" landscape, 2480×1860
        const val SW_NOTE_5C    = 992   // 1860 / 1.875
        const val W_NOTE_5C     = 2480
        const val H_NOTE_5C     = 1860

        // Go 7: 7.0" portrait, 1264×1680
        const val SW_GO_7       = 674   // 1264 / 1.875
        const val W_GO_7        = 1264
        const val H_GO_7        = 1680

        // Palma 2 Pro: 6.1" portrait, 824×1648
        const val SW_PALMA_2PRO = 439   // 824 / 1.875
        const val W_PALMA_2PRO  = 824
        const val H_PALMA_2PRO  = 1648
    }

    // Re-initialise before each test so state from previous tests doesn't leak.
    @Before fun resetToDefault() {
        ScreenMetrics.init(DENSITY, DENSITY, SW_GO_7, W_GO_7, H_GO_7)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun init(sw: Int, w: Int, h: Int) =
        ScreenMetrics.init(DENSITY, DENSITY, sw, w, h)

    /** Convert pixels back to mm at 300 PPI. */
    private fun toMm(px: Float) = px / (DENSITY * 160f) * 25.4f

    // ── line spacing ─────────────────────────────────────────────────────────

    @Test fun lineSpacing_tabXC_isInWritingRange() {
        init(SW_TAB_X_C, W_TAB_X_C, H_TAB_X_C)
        val mm = toMm(ScreenMetrics.lineSpacing)
        assertTrue("lineSpacing should be >= 8 mm on Tab X C, was $mm mm", mm >= 8f)
        assertTrue("lineSpacing should be <= 13 mm on Tab X C, was $mm mm", mm <= 13f)
    }

    @Test fun lineSpacing_note5C_isInWritingRange() {
        init(SW_NOTE_5C, W_NOTE_5C, H_NOTE_5C)
        val mm = toMm(ScreenMetrics.lineSpacing)
        assertTrue("lineSpacing should be >= 8 mm on Note Air 5C, was $mm mm", mm >= 8f)
        assertTrue("lineSpacing should be <= 13 mm on Note Air 5C, was $mm mm", mm <= 13f)
    }

    @Test fun lineSpacing_go7_isInWritingRange() {
        init(SW_GO_7, W_GO_7, H_GO_7)
        val mm = toMm(ScreenMetrics.lineSpacing)
        assertTrue("lineSpacing should be >= 8 mm on Go 7, was $mm mm", mm >= 8f)
        assertTrue("lineSpacing should be <= 13 mm on Go 7, was $mm mm", mm <= 13f)
    }

    @Test fun lineSpacing_palma2Pro_isInCompactWritingRange() {
        // Compact mode uses tighter spacing; 6 mm is the practical floor for ML Kit recognition.
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        val mm = toMm(ScreenMetrics.lineSpacing)
        assertTrue("lineSpacing should be >= 6 mm on Palma 2 Pro, was $mm mm", mm >= 6f)
        assertTrue("lineSpacing should be <= 9 mm on Palma 2 Pro, was $mm mm", mm <= 9f)
    }

    // ── gutter width ─────────────────────────────────────────────────────────

    @Test fun gutterWidth_isAtLeastMinimumTouchTarget_standardDevices() {
        val standard = listOf(
            Triple(SW_TAB_X_C, W_TAB_X_C, H_TAB_X_C),
            Triple(SW_NOTE_5C, W_NOTE_5C, H_NOTE_5C),
            Triple(SW_GO_7,    W_GO_7,    H_GO_7),
        )
        for ((sw, w, h) in standard) {
            init(sw, w, h)
            val mm = toMm(ScreenMetrics.gutterWidth)
            assertTrue("gutterWidth < 9 mm on sw=$sw (was $mm mm)", mm >= 9f)
        }
    }

    @Test fun gutterWidth_isAtLeastStylusTarget_compactDevices() {
        // Compact screens use a narrower gutter; 6 mm is still reliable for a stylus.
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        val mm = toMm(ScreenMetrics.gutterWidth)
        assertTrue("gutterWidth < 6 mm on Palma 2 Pro (was $mm mm)", mm >= 6f)
    }

    @Test fun gutterWidth_doesNotExceedMaxFraction_narrowScreen() {
        // Palma 2 Pro portrait — 824 px wide at 300 PPI
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        val fraction = ScreenMetrics.gutterWidth / W_PALMA_2PRO.toFloat()
        assertTrue("gutter fraction $fraction exceeds 0.12 on Palma 2 Pro portrait", fraction <= 0.12f)
    }

    @Test fun gutterWidth_isConsistentAcrossStandardDevices() {
        // All standard devices share the same density, so gutter pixel values should be equal
        // (barring screen-width cap, which doesn't apply at these resolutions).
        init(SW_TAB_X_C, W_TAB_X_C, H_TAB_X_C)
        val tabXC = ScreenMetrics.gutterWidth
        init(SW_NOTE_5C, W_NOTE_5C, H_NOTE_5C)
        val note5C = ScreenMetrics.gutterWidth
        assertEquals("Standard devices at same density should have equal gutter px", tabXC, note5C, 1f)
    }

    // ── text sizes ───────────────────────────────────────────────────────────

    @Test fun textBody_isReadable_allDevices() {
        val devices = listOf(
            Triple(SW_TAB_X_C,    W_TAB_X_C,    H_TAB_X_C),
            Triple(SW_NOTE_5C,    W_NOTE_5C,    H_NOTE_5C),
            Triple(SW_GO_7,       W_GO_7,       H_GO_7),
            Triple(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO),
        )
        for ((sw, w, h) in devices) {
            init(sw, w, h)
            val mm = toMm(ScreenMetrics.textBody)
            assertTrue("textBody < 3.5 mm on sw=$sw (was $mm mm)", mm >= 3.5f)
            assertTrue("textBody > 9.0 mm on sw=$sw (was $mm mm)", mm <= 9.0f)
        }
    }

    @Test fun textLogo_isLargerThanTextBody_allDevices() {
        val devices = listOf(
            Triple(SW_TAB_X_C,    W_TAB_X_C,    H_TAB_X_C),
            Triple(SW_NOTE_5C,    W_NOTE_5C,    H_NOTE_5C),
            Triple(SW_GO_7,       W_GO_7,       H_GO_7),
            Triple(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO),
        )
        for ((sw, w, h) in devices) {
            init(sw, w, h)
            assertTrue(
                "textLogo (${ScreenMetrics.textLogo}) should be > textBody (${ScreenMetrics.textBody}) on sw=$sw",
                ScreenMetrics.textLogo > ScreenMetrics.textBody
            )
        }
    }

    // ── dp() helper ───────────────────────────────────────────────────────────

    @Test fun dp_convertsToCorrectMm() {
        init(SW_GO_7, W_GO_7, H_GO_7)
        // At 300 PPI (density=1.875), 63 dp should equal ~10 mm
        val px = ScreenMetrics.dp(63f)
        val mm = toMm(px)
        assertEquals("63 dp should be ~10.0 mm at 300 PPI", 10.0f, mm, 0.2f)
    }

    // ── adaptive split ───────────────────────────────────────────────────────

    @Test fun adaptiveSplit_tabXC_canvas_hasAtLeastTenLines() {
        init(SW_TAB_X_C, W_TAB_X_C, H_TAB_X_C)
        val canvas = ScreenMetrics.computeDefaultCanvasHeight(H_TAB_X_C)
        val lines  = (canvas / ScreenMetrics.lineSpacing).toInt()
        assertTrue("Expected >= 10 canvas lines on Tab X C, got $lines", lines >= 10)
    }

    @Test fun adaptiveSplit_note5C_canvas_hasAtLeastEightLines() {
        init(SW_NOTE_5C, W_NOTE_5C, H_NOTE_5C)
        val canvas = ScreenMetrics.computeDefaultCanvasHeight(H_NOTE_5C)
        val lines  = (canvas / ScreenMetrics.lineSpacing).toInt()
        assertTrue("Expected >= 8 canvas lines on Note Air 5C, got $lines", lines >= 8)
    }

    @Test fun adaptiveSplit_go7_canvas_hasAtLeastSixLines() {
        init(SW_GO_7, W_GO_7, H_GO_7)
        val canvas = ScreenMetrics.computeDefaultCanvasHeight(H_GO_7)
        val lines  = (canvas / ScreenMetrics.lineSpacing).toInt()
        assertTrue("Expected >= 6 canvas lines on Go 7, got $lines", lines >= 6)
    }

    @Test fun adaptiveSplit_palma2Pro_canvas_hasAtLeastTenLines() {
        // Compact mode: 6.5 mm spacing + 82 % canvas fraction. Portrait height 1648 px
        // gives ~17 lines. Tested in portrait orientation (natural use for a phone device).
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        val canvas = ScreenMetrics.computeDefaultCanvasHeight(H_PALMA_2PRO)
        val lines  = (canvas / ScreenMetrics.lineSpacing).toInt()
        assertTrue("Expected >= 10 canvas lines on Palma 2 Pro (compact), got $lines", lines >= 10)
    }

    @Test fun adaptiveSplit_textPanel_isNeverLessThanThreeBodyTextLines() {
        val devices = listOf(
            Triple(SW_TAB_X_C,    W_TAB_X_C,    H_TAB_X_C),
            Triple(SW_NOTE_5C,    W_NOTE_5C,    H_NOTE_5C),
            Triple(SW_GO_7,       W_GO_7,       H_GO_7),
            Triple(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO),
        )
        for ((sw, w, screenH) in devices) {
            init(sw, w, screenH)
            val canvas    = ScreenMetrics.computeDefaultCanvasHeight(screenH)
            val textPanel = screenH - canvas
            val minPanel  = (ScreenMetrics.textBody * 3 * 1.5f).toInt()
            assertTrue(
                "textPanel ($textPanel px) < minimum ($minPanel px) on sw=$sw",
                textPanel >= minPanel
            )
        }
    }

    @Test fun adaptiveSplit_canvasNeverExceedsTotalHeight() {
        val devices = listOf(
            Triple(SW_TAB_X_C,    W_TAB_X_C,    H_TAB_X_C),
            Triple(SW_NOTE_5C,    W_NOTE_5C,    H_NOTE_5C),
            Triple(SW_GO_7,       W_GO_7,       H_GO_7),
            Triple(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO),
        )
        for ((sw, w, screenH) in devices) {
            init(sw, w, screenH)
            val canvas = ScreenMetrics.computeDefaultCanvasHeight(screenH)
            assertTrue("canvas ($canvas px) >= totalHeight ($screenH px) on sw=$sw", canvas < screenH)
        }
    }

    // ── regression: no zero / negative values ────────────────────────────────

    @Test fun allValues_arePositive_allDevices() {
        val devices = listOf(
            Triple(SW_TAB_X_C,    W_TAB_X_C,    H_TAB_X_C),
            Triple(SW_NOTE_5C,    W_NOTE_5C,    H_NOTE_5C),
            Triple(SW_GO_7,       W_GO_7,       H_GO_7),
            Triple(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO),
        )
        for ((sw, w, h) in devices) {
            init(sw, w, h)
            assertTrue("lineSpacing <= 0 on sw=$sw",  ScreenMetrics.lineSpacing  > 0f)
            assertTrue("topMargin <= 0 on sw=$sw",    ScreenMetrics.topMargin    > 0f)
            assertTrue("gutterWidth <= 0 on sw=$sw",  ScreenMetrics.gutterWidth  > 0f)
            assertTrue("strokeWidth <= 0 on sw=$sw",  ScreenMetrics.strokeWidth  > 0f)
            assertTrue("textBody <= 0 on sw=$sw",     ScreenMetrics.textBody     > 0f)
            assertTrue("textLogo <= 0 on sw=$sw",     ScreenMetrics.textLogo     > 0f)
            assertTrue("textStatus <= 0 on sw=$sw",   ScreenMetrics.textStatus   > 0f)
            assertTrue("textSubtext <= 0 on sw=$sw",  ScreenMetrics.textSubtext  > 0f)
            assertTrue("textCloseBtn <= 0 on sw=$sw", ScreenMetrics.textCloseBtn > 0f)
            assertTrue("textTutorial <= 0 on sw=$sw", ScreenMetrics.textTutorial > 0f)
        }
    }

    @Test fun extremelyLowDensity_doesNotCrash() {
        // Clamps to minimum 0.5 internally
        ScreenMetrics.init(0.3f, 0.3f, 400, 800, 600)
        assertTrue(ScreenMetrics.lineSpacing > 0f)
        assertTrue(ScreenMetrics.gutterWidth > 0f)
    }

    // ── compact-mode classification ───────────────────────────────────────────

    @Test fun isCompact_palma2Pro_isTrue() {
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        assertTrue("Palma 2 Pro (sw=$SW_PALMA_2PRO dp) should be compact mode", ScreenMetrics.isCompact)
    }

    @Test fun isCompact_go7_isFalse() {
        init(SW_GO_7, W_GO_7, H_GO_7)
        assertTrue("Go 7 (sw=$SW_GO_7 dp) should NOT be compact mode", !ScreenMetrics.isCompact)
    }

    @Test fun isCompact_note5C_isFalse() {
        init(SW_NOTE_5C, W_NOTE_5C, H_NOTE_5C)
        assertTrue("Note Air 5C (sw=$SW_NOTE_5C dp) should NOT be compact mode", !ScreenMetrics.isCompact)
    }

    @Test fun isCompact_tabXC_isFalse() {
        init(SW_TAB_X_C, W_TAB_X_C, H_TAB_X_C)
        assertTrue("Tab X C (sw=$SW_TAB_X_C dp) should NOT be compact mode", !ScreenMetrics.isCompact)
    }

    @Test fun compactMode_lineSpacing_isSmallerThan_standardMode() {
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        val compactSpacing = ScreenMetrics.lineSpacing
        init(SW_GO_7, W_GO_7, H_GO_7)
        val standardSpacing = ScreenMetrics.lineSpacing
        assertTrue(
            "Compact line spacing ($compactSpacing px) should be < standard ($standardSpacing px)",
            compactSpacing < standardSpacing
        )
    }

    @Test fun compactMode_textPanel_isNoMoreThan30Percent_palma2Pro() {
        init(SW_PALMA_2PRO, W_PALMA_2PRO, H_PALMA_2PRO)
        val canvas    = ScreenMetrics.computeDefaultCanvasHeight(H_PALMA_2PRO)
        val textPanel = H_PALMA_2PRO - canvas
        val fraction  = textPanel.toFloat() / H_PALMA_2PRO.toFloat()
        assertTrue(
            "Text panel fraction $fraction exceeds 30 % on Palma 2 Pro",
            fraction <= 0.30f
        )
    }

    @Test fun standardMode_go7_lineSpacing_isUnchanged() {
        // Go 7 sits above the compact threshold and must use standard targets.
        init(SW_GO_7, W_GO_7, H_GO_7)
        val mmSpacing = toMm(ScreenMetrics.lineSpacing)
        assertTrue("Go 7 line spacing should be >= 9 mm (standard), was $mmSpacing mm", mmSpacing >= 9f)
    }
}
