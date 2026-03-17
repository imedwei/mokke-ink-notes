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
 * The compact/standard breakpoint uses [Configuration.smallestScreenWidthDp] —
 * the same mechanism Android resource qualifiers (e.g. `values-sw600dp/`) use —
 * rather than computing a physical diagonal.
 *
 * Call [init] once in `Application.onCreate()` before any view is inflated,
 * and re-call it in `onConfigurationChanged` if the user changes font scale
 * or display density at runtime.
 * Tests use the plain-value overload to avoid an Android framework dependency.
 */
object ScreenMetrics {

    // ── Compact-mode breakpoint ───────────────────────────────────────────────
    // Palma 2 Pro ≈ 439 sw-dp (824 px / 1.875), Go 7 ≈ 674 sw-dp — threshold sits between them.
    private const val COMPACT_SW_DP = 650

    // ── Standard dp constants (Go 7, Note 5C, Tab X C) ───────────────────────
    private const val LINE_SPACING_DP        = 63f   // ≈ 10.0 mm
    private const val GUTTER_TARGET_DP       = 69f   // ≈ 11.0 mm
    private const val GUTTER_MIN_DP          = 57f   // ≈  9.0 mm  (hard floor)
    private const val GUTTER_MAX_FRACTION    = 0.12f // ≤ 12 % of screen width
    private const val CANVAS_FRACTION        = 0.70f // 70 % of screen height for canvas

    // ── Compact dp constants (Palma 2 Pro) ───────────────────────────────────
    private const val LINE_SPACING_COMPACT_DP    = 41f   // ≈  6.5 mm
    private const val GUTTER_TARGET_COMPACT_DP   = 47f   // ≈  7.5 mm
    private const val GUTTER_MIN_COMPACT_DP      = 38f   // ≈  6.0 mm  (stylus floor)
    private const val GUTTER_MAX_FRACTION_COMPACT = 0.09f // ≤  9 % of screen width
    private const val CANVAS_FRACTION_COMPACT    = 0.82f // 82 % of screen height for canvas

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
    /** True when the device's smallestScreenWidthDp is below [COMPACT_SW_DP]. */
    var isCompact:     Boolean = false; private set

    // DisplayMetrics reference for proper SP conversion (null in tests)
    private var displayMetrics: android.util.DisplayMetrics? = null
    // Font scale for test init (1.0 = no scaling)
    private var fontScale: Float = 1f

    var lineSpacing:   Float = 100f; private set
    var topMargin:     Float =  30f; private set
    var gutterWidth:   Float = 110f; private set
    var strokeWidth:   Float =   4f; private set
    var textBody:      Float =  52f; private set
    var textLogo:      Float =  96f; private set
    var textStatus:    Float =  43f; private set
    var textSubtext:   Float =  32f; private set
    var textCloseBtn:  Float =  35f; private set
    var textTutorial:  Float =  32f; private set

    private var canvasFraction: Float = CANVAS_FRACTION

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
     * @param widthPixels     screen width in pixels (used for gutter cap)
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
        isCompact      = smallestWidthDp < COMPACT_SW_DP

        val lineSpacingDp  = if (isCompact) LINE_SPACING_COMPACT_DP  else LINE_SPACING_DP
        val gutterTargetDp = if (isCompact) GUTTER_TARGET_COMPACT_DP else GUTTER_TARGET_DP
        val gutterMinDp    = if (isCompact) GUTTER_MIN_COMPACT_DP    else GUTTER_MIN_DP
        val gutterMaxFrac  = if (isCompact) GUTTER_MAX_FRACTION_COMPACT else GUTTER_MAX_FRACTION
        canvasFraction     = if (isCompact) CANVAS_FRACTION_COMPACT  else CANVAS_FRACTION

        lineSpacing  = (lineSpacingDp         * this.density).roundToInt().toFloat()
        topMargin    = (TOP_MARGIN_DP          * this.density).roundToInt().toFloat()
        strokeWidth  =  STROKE_WIDTH_DP        * this.density
        gutterWidth  = (gutterTargetDp         * this.density)
            .coerceAtMost(widthPixels          * gutterMaxFrac)
            .coerceAtLeast(gutterMinDp         * this.density)
            .roundToInt().toFloat()
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
