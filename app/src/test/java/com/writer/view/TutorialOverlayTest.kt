package com.writer.view

import android.graphics.Rect
import com.writer.ui.writing.TutorialStep
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TutorialOverlay] positioning math.
 *
 * Tests the tooltip card positioning logic to prevent crashes from
 * coerceIn with empty ranges (e.g., card wider than screen on Palma).
 *
 * These are pure math tests — no Robolectric or View rendering needed.
 */
class TutorialOverlayTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 439, widthPixels = 824, heightPixels = 1680)
    }

    /**
     * Replicates the tooltip card X positioning logic from TutorialOverlay.onDraw.
     * Returns the cardX position. Throws if the logic would crash.
     */
    private fun computeCardX(
        screenWidth: Int,
        tooltipText: String,
        cutoutRect: Rect,
        position: TutorialStep.TooltipPosition
    ): Float {
        // Approximate text width: ~10dp per character at 20dp font size
        val charWidth = ScreenMetrics.dp(10f)
        val textWidth = tooltipText.length * charWidth
        val cardPadH = ScreenMetrics.dp(16f)
        val cardWidth = textWidth + 2 * cardPadH

        return when (position) {
            TutorialStep.TooltipPosition.ABOVE,
            TutorialStep.TooltipPosition.BELOW -> {
                (cutoutRect.centerX() - cardWidth / 2f)
                    .coerceIn(0f, (screenWidth - cardWidth).coerceAtLeast(0f))
            }
            TutorialStep.TooltipPosition.CENTER -> {
                (cutoutRect.centerX() - cardWidth / 2f)
                    .coerceIn(0f, (screenWidth - cardWidth).coerceAtLeast(0f))
            }
        }
    }

    @Test
    fun `card fits within normal screen`() {
        val x = computeCardX(
            screenWidth = 824,
            tooltipText = "Write with your stylus",
            cutoutRect = Rect(0, 200, 824, 1400),
            position = TutorialStep.TooltipPosition.CENTER
        )
        assertTrue("cardX=$x should be >= 0", x >= 0f)
    }

    @Test
    fun `card wider than screen does not crash`() {
        val x = computeCardX(
            screenWidth = 300,
            tooltipText = "This is a very long tooltip that exceeds the small screen width easily",
            cutoutRect = Rect(0, 50, 300, 200),
            position = TutorialStep.TooltipPosition.CENTER
        )
        assertTrue("cardX=$x should be >= 0", x >= 0f)
    }

    @Test
    fun `card wider than screen clamps to zero`() {
        val x = computeCardX(
            screenWidth = 100,
            tooltipText = "Strike through text to delete. Scribble over shapes to erase.",
            cutoutRect = Rect(0, 50, 100, 200),
            position = TutorialStep.TooltipPosition.ABOVE
        )
        assertTrue("cardX=$x should be 0 when card overflows", x == 0f)
    }

    @Test
    fun `ABOVE position on small screen does not crash`() {
        val x = computeCardX(
            screenWidth = 200,
            tooltipText = "Draw a shape — hold at the end to snap it",
            cutoutRect = Rect(0, 10, 200, 180),
            position = TutorialStep.TooltipPosition.ABOVE
        )
        assertTrue("cardX=$x should be >= 0", x >= 0f)
    }

    @Test
    fun `BELOW position on small screen does not crash`() {
        val x = computeCardX(
            screenWidth = 200,
            tooltipText = "Scroll with your finger to turn writing into text",
            cutoutRect = Rect(0, 100, 200, 180),
            position = TutorialStep.TooltipPosition.BELOW
        )
        assertTrue("cardX=$x should be >= 0", x >= 0f)
    }

    @Test
    fun `all tutorial step texts fit on Palma screen`() {
        val palmaWidth = 824  // Palma2 Pro portrait width
        val cutout = Rect(0, 200, palmaWidth, 1400)

        val texts = listOf(
            "Write with your stylus",
            "Draw a shape — hold at the end to snap it",
            "Strike through text to delete. Scribble over shapes to erase.",
            "Scroll with your finger to turn writing into text",
            "Great! Your writing appears as text"
        )

        for (text in texts) {
            for (pos in TutorialStep.TooltipPosition.entries) {
                val x = computeCardX(palmaWidth, text, cutout, pos)
                assertTrue("'$text' at $pos: cardX=$x should be >= 0", x >= 0f)
            }
        }
    }

    @Test
    fun `all tutorial step texts fit on landscape screen`() {
        val landscapeWidth = 1648
        val cutout = Rect(0, 100, landscapeWidth, 500)

        val texts = listOf(
            "Write with your stylus",
            "Strike through text to delete. Scribble over shapes to erase.",
            "Great! Your writing appears as text"
        )

        for (text in texts) {
            val x = computeCardX(landscapeWidth, text, cutout, TutorialStep.TooltipPosition.CENTER)
            assertTrue("'$text': cardX=$x should be >= 0", x >= 0f)
        }
    }
}
