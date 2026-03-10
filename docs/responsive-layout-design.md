# Responsive Layout Design

## Problem

All layout measurements were hardcoded in raw pixels inside `HandwritingCanvasView`,
`RecognizedTextView`, `StrokeClassifier`, and `GestureHandler`. A pixel-constant tuned
for a ~300 ppi tablet is physically enormous on the 207 ppi Tab X C and cramped on the
small physical screen of the Palma 2 Pro.

---

## Android Best Practice: dp/sp Units

Android's standard solution is **density-independent pixels (dp)** and
**scale-independent pixels (sp)**:

- **dp**: 1 dp = 1 px at 160 ppi, scaled by `DisplayMetrics.density` (= `densityDpi / 160`).
  Use for all spatial measurements (spacing, margins, padding, touch targets).
- **sp**: Same as dp but additionally multiplied by the user's system font-scale preference
  (`DisplayMetrics.scaledDensity`). Use for all text sizes so Accessibility settings are
  respected.
- **`Configuration.smallestScreenWidthDp`**: The canonical Android mechanism for
  screen-size breakpoints — the same qualifier as `values-sw600dp/` resource directories.
  Equivalent to the narrowest screen axis in dp regardless of orientation.

This replaces the previous approach of working in physical inches using `DisplayMetrics.xdpi`.

---

## Target Devices

All four Onyx Boox devices use E Ink panels at **300 PPI** (B&W layer), giving a uniform
`DisplayMetrics.density` of 300/160 = **1.875** on all devices.

| Device | Diagonal | B&W resolution | Orientation | smallestWidthDp | Compact? |
|---|---|---|---|---|---|
| Tab X C | 13.3" | 3200 × 2400 | Landscape | 1280 dp (2400/1.875) | No |
| Note Air 5C | 10.3" | 2480 × 1860 | Landscape | 992 dp (1860/1.875) | No |
| Go 7 | 7.0" | 1264 × 1680 | Portrait | 674 dp (1264/1.875) | No |
| Palma 2 Pro | 6.1" | 824 × 1648 | Portrait | 439 dp (824/1.875) | **Yes** |

The compact/standard breakpoint is `smallestWidthDp < 650`. Palma 2 Pro (≈439 sw-dp) falls
into compact mode; Go 7 (≈674 sw-dp) stays standard.

> Note: Tab X C and Palma 2 Pro also have color Kaleido 3 overlays at half resolution
> (150 PPI). The app renders in B&W mode (standard for handwriting) so the 300 PPI
> B&W resolution is the effective display density.

---

## Design: `ScreenMetrics`

`com.writer.view.ScreenMetrics` is a singleton initialized once in
`WriterApplication.onCreate()`. It holds dp/sp constants and converts them to pixels once at
startup. Every other class reads from `ScreenMetrics` instead of holding its own pixel constants.

### dp Constants

| Property | Standard | Compact (Palma 2 Pro) | Rationale |
|---|---|---|---|
| Line spacing | 63 dp (≈10.0 mm) | 41 dp (≈6.5 mm) | Comfortable for adult handwriting |
| Top margin | 19 dp (≈3.0 mm) | ← same | Small visual gap before first line |
| Gutter target | 69 dp (≈11.0 mm) | 47 dp (≈7.5 mm) | Reliable stylus touch target |
| Gutter min | 57 dp (≈9.0 mm) | 38 dp (≈6.0 mm) | Hard floor |
| Gutter max fraction | 12% of screen width | 9% | Protects narrow screens |
| Pen stroke width | 2.6 dp (≈0.42 mm) | ← same | Medium ballpoint equivalent |
| Canvas fraction | 70% of screen height | 82% | More writing area on small screens |

### sp Constants (text sizes)

| Property | sp value | Approximate cap height |
|---|---|---|
| Body text | 33 sp | ≈5.2 mm |
| Logo text | 60 sp | ≈9.5 mm |
| Status text | 27 sp | ≈4.3 mm |
| Status subtext | 20 sp | ≈3.1 mm |
| Close button | 22 sp | ≈3.5 mm |
| Tutorial text | 20 sp | ≈3.1 mm |

sp values respect the user's Accessibility font-size preference.

### Resulting pixel values per device

All devices share density = 1.875, so dp-to-pixel conversion is identical across
standard devices. Compact mode (Palma 2 Pro) uses smaller dp constants.

| Property | Tab X C | Note Air 5C | Go 7 | Palma 2 Pro (compact) |
|---|---|---|---|---|
| `lineSpacing` | 118 px (63 dp) | 118 px | 118 px | 77 px (41 dp) |
| `topMargin` | 36 px (19 dp) | 36 px | 36 px | 36 px |
| `gutterWidth` | 129 px (69 dp) | 129 px | 129 px | 74 px (capped at 9% of 824 px) |
| Body text | 62 px (33 sp) | 62 px | 62 px | 62 px |

### Initialisation

```kotlin
// WriterApplication.onCreate()
ScreenMetrics.init(resources.displayMetrics, resources.configuration)
```

The `init()` overload for unit tests avoids Android framework dependencies:

```kotlin
ScreenMetrics.init(
    density         = 1.88f,
    scaledDensity   = 1.88f,
    smallestWidthDp = 674,
    widthPixels     = 1264,
    heightPixels    = 1680
)
```

---

## Changes to Existing Classes

### `HandwritingCanvasView`

```kotlin
// Lazy property getters — pick up initialized values at runtime
val LINE_SPACING get() = ScreenMetrics.lineSpacing
val TOP_MARGIN   get() = ScreenMetrics.topMargin
val GUTTER_WIDTH get() = ScreenMetrics.gutterWidth
private val UNDO_STEP_SIZE get() = ScreenMetrics.dp(11f)   // ~1.8 mm per undo step
```

### `CanvasTheme`

```kotlin
val DEFAULT_STROKE_WIDTH get() = ScreenMetrics.strokeWidth
```

### `RecognizedTextView`

```kotlin
// companion object — spatial layout
private val HORIZONTAL_PADDING    get() = ScreenMetrics.dp(21f)
private val PARAGRAPH_SPACING     get() = ScreenMetrics.dp(12f)
private val LIST_ITEM_SPACING     get() = ScreenMetrics.dp(3f)
private val FIRST_LINE_INDENT     get() = ScreenMetrics.dp(43f).toInt()
private val LIST_BASE_INDENT      get() = ScreenMetrics.dp(32f).toInt()
private val BULLET_HANG_INDENT    get() = ScreenMetrics.dp(54f).toInt()
private val HEADING_SPACING_AFTER get() = ScreenMetrics.dp(6f)
private val BOTTOM_PADDING        get() = ScreenMetrics.dp(5f)
// instance — close button
private val closeButtonHeight get() = ScreenMetrics.dp(60f)

// Paint init blocks
textSize = ScreenMetrics.textBody   // body
textSize = ScreenMetrics.textLogo   // logo in gutter
textSize = ScreenMetrics.textStatus
```

### `StrokeClassifier`

| Constant | dp expression |
|---|---|
| `MARKER_MIN_WIDTH` | `ScreenMetrics.dp(8f)` |
| `MARKER_MAX_WIDTH` | `ScreenMetrics.dp(64f)` |
| `MARKER_MIN_GAP`   | `ScreenMetrics.dp(11f)` |
| `UNDERLINE_MIN_WIDTH` | `ScreenMetrics.dp(54f)` |

### `GestureHandler`

| Constant | dp expression |
|---|---|
| `STRIKETHROUGH_MIN_WIDTH` | `ScreenMetrics.dp(54f)` |

Dimensionless ratios (`MARKER_MAX_HEIGHT_RATIO`, `SIMPLICITY_MAX_RATIO`, etc.) are
unaffected.

---

## Adaptive Default Split

`WritingActivity` overrides the XML weight-based split with an explicit pixel height
computed by `ScreenMetrics.computeDefaultCanvasHeight()`:

```kotlin
recognizedTextView.post {
    val totalHeight = recognizedTextView.height + inkCanvas.height
    val canvasHeight = ScreenMetrics.computeDefaultCanvasHeight(totalHeight)
    val textHeight   = totalHeight - canvasHeight
    // apply heights and set weight = 0 on both panels …
}
```

`computeDefaultCanvasHeight` guarantees:
- Canvas holds at least 5 complete writing lines.
- Text panel holds at least 3 lines of body text.

### Resulting defaults per device

| Device | Screen ht (px) | Canvas fraction | Lines visible | Text panel |
|---|---|---|---|---|
| Tab X C (landscape) | 2400 px | 70% | ≥13 lines | ≥720 px |
| Note Air 5C (landscape) | 1860 px | 70% | ≥10 lines | ≥558 px |
| Go 7 (portrait)     | 1680 px | 70% | ≥9 lines  | ≥504 px |
| Palma 2 Pro (portrait) | 1648 px | 82% | ≥17 lines | ≤18%  |

---

## Test Strategy

Unit tests in `app/src/test/java/com/writer/view/ScreenMetricsTest.kt` use the
plain-value `init()` overload and run on the JVM (no Android framework stubs).

Tests cover all four target devices and assert:

- Line spacing is in the ergonomic writing range for each mode.
- Gutter width meets the minimum touch/stylus target.
- Gutter does not exceed the max-fraction cap on narrow screens.
- Text sizes are readable across all devices.
- `computeDefaultCanvasHeight` provides sufficient lines and a minimum text panel.
- Compact mode is correctly detected for Palma 2 Pro and not for Go 7, Note 5C, Tab X C.
- No computed value is zero or negative.

---

## Calibration Notes

1. After installing on real hardware, note which elements feel too large or too small.
2. Adjust the dp constant in `ScreenMetrics` (and the compact variant if only the
   Palma 2 Pro is affected). Pixel values for all four devices update automatically.
3. Re-run `ScreenMetricsTest` to confirm the change stays within the defined bounds.
4. If a test bound needs relaxing, document the reason in the test comment.

Device fixture parameters (`DENSITY_*`, `SW_*`) in `ScreenMetricsTest` should be
verified against `DisplayMetrics.density` and `Configuration.smallestScreenWidthDp`
on real hardware and corrected if they differ by more than 5%.
