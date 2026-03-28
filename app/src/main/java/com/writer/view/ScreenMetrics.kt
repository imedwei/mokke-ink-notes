package com.writer.view

import android.util.TypedValue
import kotlin.math.roundToInt

/**
 * Converts dp/sp design constants to device pixels using Android's standard
 * density system ([DisplayMetrics.density] and [TypedValue.applyDimension]).
 *
 * This is the Android platform best practice:
 *  - **dp** (density-independent pixels) for all spatial measurements.
 *    1 dp = 1 px at 160 ppi; scaled by [DisplayMetrics.density].
 *  - **sp** (scale-independent pixels) for text sizes.
 *    Same as dp but additionally respects the user's system font-size preference
 *    via [TypedValue.applyDimension] with [TypedValue.COMPLEX_UNIT_SP].
 *
 * Screen-size classification uses [Configuration.smallestScreenWidthDp]:
 *  - **Large screen** (sw >= 900dp): Tab X C, Note Air 5C — dual-column in both orientations
 *  - **Small screen** (sw < 900dp): Go 7, Palma 2 Pro — fold/unfold in portrait, dual in landscape
 *
 * Call [init] once in `Application.onCreate()` before any view is inflated,
 * and re-call it in `onConfigurationChanged` if the user changes font scale
 * or display density at runtime.
 * Tests use the plain-value overload to avoid an Android framework dependency.
 */
object ScreenMetrics {

    // ── Screen-size breakpoint ─────────────────────────────────────────────
    // Tab X C ≈ 1280 sw-dp, Note Air 5C ≈ 992 sw-dp — both large.
    // Go 7 ≈ 674 sw-dp, Palma 2 Pro ≈ 439 sw-dp — both small.
    private const val LARGE_SCREEN_SW_DP = 900

    // ── Large-screen dp constants (Tab X C, Note Air 5C) ───────────────────
    private const val LINE_SPACING_LARGE_DP = 50f   // ≈  8.0 mm
    private const val CANVAS_FRACTION_LARGE = 0.70f // 70 % of screen height for canvas

    // ── Small-screen dp constants (Go 7, Palma 2 Pro) ──────────────────────
    private const val LINE_SPACING_SMALL_DP = 41f   // ≈  6.5 mm
    private const val CANVAS_FRACTION_SMALL = 0.82f // 82 % of screen height for canvas

    // ── Column layout constants ────────────────────────────────────────────
    private const val MAIN_COLUMN_FRACTION  = 0.70f // 70% main, 30% cue (Cornell standard)
    private const val COLUMN_DIVIDER_DP     = 4f    // Visual divider between columns

    // ── Shared dp constants ───────────────────────────────────────────────────
    private const val TOP_MARGIN_DP      = 19f   // ≈  3.0 mm
    private const val STROKE_WIDTH_DP    = 2.6f  // ≈  0.42 mm

    // ── Text sizes in sp ─────────────────────────────────────────────────────
    // sp = dp × fontScale, so these respect the user's Accessibility font size.
    private const val TEXT_BODY_SP       = 33f   // ≈  5.2 mm cap height
    private const val TEXT_LOGO_SP       = 60f   // ≈  9.5 mm
    private const val TEXT_STATUS_SP     = 27f   // ≈  4.3 mm
    private const val TEXT_SUBTEXT_SP    = 20f   // ≈  3.1 mm
    private const val TEXT_CLOSE_BTN_SP  = 22f   // ≈  3.5 mm
    private const val TEXT_TUTORIAL_SP   = 20f   // ≈  3.1 mm

    // ── Computed pixel values (set by init) ───────────────────────────────────
    var density:       Float   = 1f;    private set
    /** True when the device's smallestScreenWidthDp is at or above [LARGE_SCREEN_SW_DP]. */
    var isLargeScreen: Boolean = false; private set

    // DisplayMetrics reference for proper SP conversion (null in tests)
    private var displayMetrics: android.util.DisplayMetrics? = null
    // Font scale for test init (1.0 = no scaling)
    private var fontScale: Float = 1f

    var lineSpacing:   Float = 100f; private set
    var topMargin:     Float =  30f; private set
    var strokeWidth:   Float =   4f; private set
    var textBody:      Float =  52f; private set
    var textLogo:      Float =  96f; private set
    var textStatus:    Float =  43f; private set
    var textSubtext:   Float =  32f; private set
    var textCloseBtn:  Float =  35f; private set
    var textTutorial:  Float =  32f; private set

    private var canvasFraction: Float = CANVAS_FRACTION_LARGE

    // ── Large-screen column widths (pixels) ──────────────────────────────────
    // On large screens, main column width is fixed at 70% of portrait width
    // across all orientations. Cue gets the remaining space.
    // Zero on small screens (not applicable — small screens use fold/unfold).

    /** Column divider width in pixels. */
    var columnDividerPx: Int = 0; private set
    /** Main column width in pixels (70% of portrait width). 0 on small screens. */
    var mainColumnWidthPx: Int = 0; private set
    /** Cue column width in portrait mode (30% of portrait width). 0 on small screens. */
    var portraitCueWidthPx: Int = 0; private set
    /** Cue column width in landscape mode (all remaining space). 0 on small screens. */
    var landscapeCueWidthPx: Int = 0; private set
    /** Main column width when cue is expanded in portrait (portrait - landscapeCue - divider). 0 on small screens. */
    var expandedPortraitMainWidthPx: Int = 0; private set

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Production init — called from Application.onCreate().
     * [Configuration.smallestScreenWidthDp] is the narrowest width in any
     * orientation, matching the `sw<N>dp` resource qualifier convention.
     */
    fun init(
        displayMetrics: android.util.DisplayMetrics,
        configuration: android.content.res.Configuration
    ) {
        this.displayMetrics = displayMetrics
        initLayout(
            density         = displayMetrics.density,
            smallestWidthDp = configuration.smallestScreenWidthDp,
            widthPixels     = displayMetrics.widthPixels,
            heightPixels    = displayMetrics.heightPixels
        )
        computeTextSizes()
    }

    /**
     * Plain-value overload for unit tests — no Android framework dependency.
     *
     * @param density         [DisplayMetrics.density] (= densityDpi / 160)
     * @param fontScale       User font scale preference (1.0 = default, >1.0 = larger text)
     * @param smallestWidthDp [Configuration.smallestScreenWidthDp]
     * @param widthPixels     screen width in pixels
     * @param heightPixels    screen height in pixels
     */
    fun init(
        density: Float,
        fontScale: Float = 1f,
        smallestWidthDp: Int,
        widthPixels: Int,
        heightPixels: Int
    ) {
        this.displayMetrics = null
        this.fontScale = fontScale.coerceAtLeast(0.5f)
        initLayout(density, smallestWidthDp, widthPixels, heightPixels)
        computeTextSizes()
    }

    private fun initLayout(
        density: Float,
        smallestWidthDp: Int,
        widthPixels: Int,
        heightPixels: Int
    ) {
        this.density   = density.coerceAtLeast(0.5f)
        isLargeScreen  = smallestWidthDp >= LARGE_SCREEN_SW_DP

        val lineSpacingDp  = if (isLargeScreen) LINE_SPACING_LARGE_DP else LINE_SPACING_SMALL_DP
        canvasFraction     = if (isLargeScreen) CANVAS_FRACTION_LARGE else CANVAS_FRACTION_SMALL

        lineSpacing  = (lineSpacingDp         * this.density).roundToInt().toFloat()
        topMargin    = (TOP_MARGIN_DP          * this.density).roundToInt().toFloat()
        strokeWidth  =  STROKE_WIDTH_DP        * this.density

        // Column widths for large-screen dual-column layout
        columnDividerPx = (COLUMN_DIVIDER_DP * this.density).roundToInt()
        if (isLargeScreen) {
            val portraitWidthPx = minOf(widthPixels, heightPixels)
            val landscapeWidthPx = maxOf(widthPixels, heightPixels)
            mainColumnWidthPx = (portraitWidthPx * MAIN_COLUMN_FRACTION).toInt()
            portraitCueWidthPx = portraitWidthPx - mainColumnWidthPx - columnDividerPx
            landscapeCueWidthPx = landscapeWidthPx - mainColumnWidthPx - columnDividerPx
            expandedPortraitMainWidthPx = portraitWidthPx - landscapeCueWidthPx - columnDividerPx
        } else {
            mainColumnWidthPx = 0
            portraitCueWidthPx = 0
            landscapeCueWidthPx = 0
            expandedPortraitMainWidthPx = 0
        }
    }

    private fun computeTextSizes() {
        textBody     = spToPx(TEXT_BODY_SP)
        textLogo     = spToPx(TEXT_LOGO_SP)
        textStatus   = spToPx(TEXT_STATUS_SP)
        textSubtext  = spToPx(TEXT_SUBTEXT_SP)
        textCloseBtn = spToPx(TEXT_CLOSE_BTN_SP)
        textTutorial = spToPx(TEXT_TUTORIAL_SP)
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    /** Convert dp to pixels at the current display density. */
    fun dp(value: Float): Float = value * density

    /**
     * Convert sp to pixels, respecting the user's font-size preference.
     * Uses [TypedValue.applyDimension] for proper adaptive font scaling on API 34+.
     */
    fun sp(value: Float): Float = spToPx(value)

    /**
     * Internal SP to pixel conversion using [TypedValue.applyDimension] when available.
     * Falls back to manual calculation for tests without Android framework.
     */
    private fun spToPx(value: Float): Float {
        val dm = displayMetrics
        return if (dm != null) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, dm)
        } else {
            value * density * fontScale
        }
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    /**
     * Compute the default canvas height for the writing/text split.
     *
     * Guarantees:
     *  - canvas holds at least 5 complete writing lines
     *  - text panel holds at least 3 lines of body text
     */
    fun computeDefaultCanvasHeight(totalHeight: Int): Int {
        val minTextPanel   = (textBody * 3 * 1.5f).toInt()
        val minCanvas      = (lineSpacing * 5 + topMargin).toInt()
        val preferredLines = ((totalHeight * canvasFraction - topMargin) / lineSpacing)
            .toInt().coerceAtLeast(5)
        val preferred      = (topMargin + preferredLines * lineSpacing).toInt()
        return preferred
            .coerceAtLeast(minCanvas)
            .coerceAtMost(totalHeight - minTextPanel)
    }
}
