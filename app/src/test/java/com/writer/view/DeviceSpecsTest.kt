package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the device fixture constants used in [ScreenMetricsTest] are
 * correctly derived from the known hardware specifications.
 *
 * If a device's reported density or smallestWidthDp differ from what is listed here,
 * update the hardware spec row and re-derive the fixture constants. Do not change
 * the derived values without also updating the hardware spec.
 *
 * Sources (verified 2025):
 *   Tab X C      https://onyxboox.com/boox_tabxc
 *   Note Air 5C  https://onyxboox.com/boox_noteair5c  (also sold as "Note 5C")
 *   Go 7         https://onyxboox.com/boox_go7
 *   Palma 2 Pro  https://onyxboox.com/boox_palma2pro
 */
class DeviceSpecsTest {

    // ── Hardware specs ────────────────────────────────────────────────────────

    data class HardwareSpec(
        val name: String,
        val diagonalIn: Float,
        val widthPx: Int,
        val heightPx: Int,
        val ppi: Int,
        val orientation: String   // "landscape" or "portrait"
    ) {
        /** Android density = ppi / 160. */
        val density: Float get() = ppi / 160f

        /**
         * smallestScreenWidthDp = narrowest axis in dp.
         * Matches [android.content.res.Configuration.smallestScreenWidthDp].
         */
        val smallestWidthDp: Int get() = (minOf(widthPx, heightPx) / density).toInt()

        /** Verify the diagonal is geometrically consistent with resolution and PPI. */
        val computedDiagonalIn: Float
            get() = Math.sqrt((widthPx.toDouble().let { it * it } +
                               heightPx.toDouble().let { it * it })).toFloat() / ppi
    }

    private val TAB_X_C = HardwareSpec(
        name        = "Onyx Boox Tab X C",
        diagonalIn  = 13.3f,
        widthPx     = 3200,
        heightPx    = 2400,
        ppi         = 300,
        orientation = "landscape"
    )

    private val NOTE_AIR_5C = HardwareSpec(
        name        = "Onyx Boox Note Air 5C",
        diagonalIn  = 10.3f,
        widthPx     = 2480,
        heightPx    = 1860,
        ppi         = 300,
        orientation = "landscape"
    )

    private val GO_7 = HardwareSpec(
        name        = "Onyx Boox Go 7",
        diagonalIn  = 7.0f,
        widthPx     = 1264,
        heightPx    = 1680,
        ppi         = 300,
        orientation = "portrait"
    )

    private val PALMA_2_PRO = HardwareSpec(
        name        = "Onyx Boox Palma 2 Pro",
        diagonalIn  = 6.13f,
        widthPx     = 824,
        heightPx    = 1648,
        ppi         = 300,
        orientation = "portrait"
    )

    private val ALL_DEVICES = listOf(TAB_X_C, NOTE_AIR_5C, GO_7, PALMA_2_PRO)

    // ── Geometry sanity checks ────────────────────────────────────────────────

    @Test fun diagonal_isConsistentWithResolutionAndPpi() {
        for (d in ALL_DEVICES) {
            assertEquals(
                "${d.name}: computed diagonal (${d.computedDiagonalIn}\") " +
                "should match spec (${d.diagonalIn}\")",
                d.diagonalIn, d.computedDiagonalIn, 0.1f
            )
        }
    }

    // ── Density derivation ────────────────────────────────────────────────────

    @Test fun density_allDevices_is1_875() {
        // All four target devices run at 300 PPI → density = 300/160 = 1.875
        for (d in ALL_DEVICES) {
            assertEquals(
                "${d.name}: density should be 1.875 (300 PPI / 160)",
                ScreenMetricsTest.DENSITY, d.density, 0.001f
            )
        }
    }

    // ── smallestWidthDp derivation ────────────────────────────────────────────

    @Test fun smallestWidthDp_tabXC_matches_fixture() {
        assertEquals(
            "Tab X C smallestWidthDp: min(3200,2400)/1.875",
            ScreenMetricsTest.SW_TAB_X_C, TAB_X_C.smallestWidthDp
        )
    }

    @Test fun smallestWidthDp_noteAir5C_matches_fixture() {
        assertEquals(
            "Note Air 5C smallestWidthDp: min(2480,1860)/1.875",
            ScreenMetricsTest.SW_NOTE_5C, NOTE_AIR_5C.smallestWidthDp
        )
    }

    @Test fun smallestWidthDp_go7_matches_fixture() {
        assertEquals(
            "Go 7 smallestWidthDp: min(1264,1680)/1.875",
            ScreenMetricsTest.SW_GO_7, GO_7.smallestWidthDp
        )
    }

    @Test fun smallestWidthDp_palma2Pro_matches_fixture() {
        assertEquals(
            "Palma 2 Pro smallestWidthDp: min(824,1648)/1.875",
            ScreenMetricsTest.SW_PALMA_2PRO, PALMA_2_PRO.smallestWidthDp
        )
    }

    // ── Compact-mode classification ───────────────────────────────────────────

    @Test fun compactThreshold_classifiesDevicesCorrectly() {
        val threshold = 650
        mapOf(
            TAB_X_C     to false,
            NOTE_AIR_5C to false,
            GO_7        to false,
            PALMA_2_PRO to true,
        ).forEach { (device, expectedCompact) ->
            val isCompact = device.smallestWidthDp < threshold
            assertEquals(
                "${device.name} (sw=${device.smallestWidthDp} dp): isCompact should be $expectedCompact",
                expectedCompact, isCompact
            )
        }
    }
}
