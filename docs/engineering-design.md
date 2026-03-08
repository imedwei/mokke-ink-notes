# InkUp — Engineering Design

## Overview

InkUp is an Android application targeting Onyx Boox e-ink tablets. It provides a ruled handwriting canvas where the user writes naturally with a stylus; as lines scroll off the top of the canvas they are recognized using Google ML Kit Digital Ink Recognition and displayed as formatted text in a panel above. The result is a distraction-free note-taking workflow where ink becomes text without any explicit recognition step.

**Target platform:** Android 10+ (API 29+), Onyx Boox devices with e-ink display
**Language:** Kotlin
**Build system:** Gradle (Kotlin DSL)
**Min SDK:** 29 · Target/Compile SDK: 34

---

## High-Level Architecture

The app is organized into four layers:

```
┌─────────────────────────────────────────────────────┐
│                      UI Layer                        │
│  WritingActivity · DocumentListActivity · SaveAs     │
├─────────────────────────────────────────────────────┤
│                 Coordinator Layer                    │
│  WritingCoordinator · GestureHandler                │
│  ParagraphBuilder · UndoManager · TutorialManager   │
├─────────────────────────────────────────────────────┤
│              Recognition & View Layer                │
│  GoogleMLKitTextRecognizer · LineSegmenter              │
│  StrokeClassifier · ModelManager                    │
│  HandwritingCanvasView · RecognizedTextView         │
├─────────────────────────────────────────────────────┤
│            Model & Storage Layer                     │
│  DocumentModel · DocumentData · InkStroke           │
│  StrokePoint · InkLine · DocumentStorage            │
└─────────────────────────────────────────────────────┘
```

---

## Package Structure

```
com.writer
├── model/
│   ├── StrokePoint.kt          — x, y, pressure, timestamp for a single pen sample
│   ├── InkStroke.kt            — ordered list of StrokePoints, UUID, strokeWidth
│   ├── StrokeExtensions.kt     — computed properties: bounds, pathLength, diagonal, shiftY
│   ├── InkLine.kt              — group of strokes on one ruled line + bounding box
│   ├── DocumentModel.kt        — runtime document state (active strokes, language)
│   └── DocumentData.kt         — serializable snapshot used for persistence
│
├── recognition/
│   ├── ModelManager.kt         — downloads/caches ML Kit language models
│   ├── GoogleMLKitTextRecognizer.kt — wraps ML Kit, recognizes InkLine → text
│   ├── LineSegmenter.kt        — maps strokes to line indices, builds InkLines
│   └── StrokeClassifier.kt     — detects list-marker and underline (heading) strokes
│
├── ui/
│   ├── writing/
│   │   ├── WritingActivity.kt   — single main activity; lifecycle, menus, doc ops
│   │   ├── WritingCoordinator.kt — orchestrates recognition, scroll, text sync, undo
│   │   ├── GestureHandler.kt    — strikethrough-to-delete and heading-underline logic
│   │   ├── ParagraphBuilder.kt  — groups lines into paragraphs with formatting hints
│   │   ├── UndoManager.kt       — stack-based undo/redo with gesture-scrub API
│   │   ├── TutorialManager.kt   — interactive tutorial overlay lifecycle
│   │   ├── TutorialContent.kt   — generates tutorial strokes and annotations
│   │   └── SaveAsActivity.kt    — handwriting-based document rename dialog
│   └── documents/
│       └── DocumentListActivity.kt — launcher stub (currently redirects to WritingActivity)
│
├── view/
│   ├── HandwritingCanvasView.kt  — SurfaceView ink canvas with Onyx SDK integration
│   ├── RecognizedTextView.kt     — custom View for formatted recognized-text display
│   ├── CanvasTheme.kt            — paint/color constants for e-ink rendering
│   └── HandwritingNameInput.kt   — handwriting input widget for document naming
│
├── storage/
│   └── DocumentStorage.kt        — JSON CRUD, sync folder export (SAF), migration
│
└── WriterApplication.kt          — Application subclass (Onyx hidden-API bypass init)
```

---

## Core Data Model

### StrokePoint
Single digitizer sample: `x`, `y`, `pressure` (Float), `timestamp` (Long ms). Coordinates are in **document space** — the absolute position in the infinite scrollable document, not screen space.

### InkStroke
An immutable sequence of `StrokePoint`s representing one pen-down to pen-up motion. Has a UUID `strokeId` for identity tracking, `strokeWidth`, and derived `startTime`/`endTime`. Extension properties (`minX`, `maxX`, `minY`, `maxY`, `xRange`, `yRange`, `pathLength`, `diagonal`) are computed lazily.

### InkLine
A group of strokes that belong to the same ruled line, plus a bounding box computed from all their points. Used as the unit of input to the recognizer.

### DocumentModel
Runtime-only state: the mutable list of `activeStrokes` currently in the document, plus the language tag for recognition.

### DocumentData
The full serializable snapshot of a document:
- `strokes` — all ink strokes
- `scrollOffsetY` — current scroll position
- `lineTextCache` — `Map<lineIndex, recognizedText>`
- `everHiddenLines` — set of line indices that have scrolled above the viewport
- `highestLineIndex`, `currentLineIndex` — cursor tracking
- `userRenamed` — whether the user has manually named the document

---

## Line-Based Canvas Model

The canvas is a vertically-infinite ruled sheet. Lines are evenly spaced:

```
TOP_MARGIN = 40px
LINE_SPACING = 128px   (~0.43" at 300 ppi)
GUTTER_WIDTH = 144px   (right-edge scroll strip)
```

A stroke's **line index** is determined solely by the Y coordinate of its **first point**:

```
lineIndex = floor((firstPoint.y - TOP_MARGIN) / LINE_SPACING)
```

Using the starting point (not the centroid or bounding-box top) means descenders (g, y, p) on one line don't bleed into the line below.

Coordinate transforms:
- Screen Y → Document Y: add `scrollOffsetY`
- Document Y → Screen Y: subtract `scrollOffsetY`
- Line index → Document Y (top of line): `TOP_MARGIN + lineIndex * LINE_SPACING`

---

## Input Pipeline

### Onyx SDK Path (Boox devices)
`HandwritingCanvasView` uses the Onyx `TouchHelper` / `RawInputCallback` API for hardware-accelerated e-ink pen rendering. The SDK renders strokes directly to the display at low latency without involving the Android canvas pipeline. The app receives callbacks:

1. `onBeginRawDrawing` — pen down; clears in-progress buffer
2. `onRawDrawingTouchPointMoveReceived` — per-point move; gesture detection runs here
3. `onEndRawDrawing` — pen up; assembles `InkStroke`, fires `onStrokeCompleted`

The SDK is paused/resumed around scroll operations and interactive gestures to avoid conflicts. The limit rect excludes the right gutter so gutter touches are handled by `onTouchEvent` instead.

### Fallback Path (non-Boox / emulator)
Standard `MotionEvent` in `onTouchEvent`. Points are collected, gestures checked, and strokes assembled identically to the SDK path. The current stroke is drawn to the Canvas directly during the move phase.

### Gutter Touch
Pen or mouse in the right `GUTTER_WIDTH` strip is handled separately: vertical drag scrolls the canvas (`scrollOffsetY`), and once at maximum canvas scroll, further downward drag overscrolls into the text pane (`textOverscroll`).

---

## Gesture System

Three gesture types are detected inside `HandwritingCanvasView` during stroke collection, before the stroke is committed to the document:

### Gutter Scroll
Continuous vertical drag in the gutter. No threshold — activates immediately on `ACTION_DOWN` within the gutter. Snaps to line boundaries on `ACTION_UP`.

### Line-Drag Gesture
- **Trigger:** vertical stroke spanning ≥ 1 line spacing with horizontal drift ≤ 30% of vertical span
- **Effect:** lifts all strokes from the anchor line downward and repositions them vertically by whole-line increments. Upward drag can delete lines by merging them.
- Implementation: SDK is disabled during the gesture; `onTouchEvent` receives subsequent moves. `WritingCoordinator` receives `onLineDragStart/Step/End` callbacks and updates `DocumentModel` directly.

### Undo/Redo Scrub Gesture
- **Phase 1 (horizontal):** stroke spanning ≥ 1.5 line spacings horizontally with vertical drift ≤ 20% sets `undoGestureReady`
- **Phase 2 (vertical):** subsequent vertical movement ≥ 0.75 line spacings activates `undoScrubActive`
- **Effect:** vertical position maps linearly to a timeline position in `UndoManager.scrubTimeline`. Moving down undoes; moving up redoes. Releasing commits the chosen position.

### Strikethrough (via GestureHandler)
Detected *after* stroke completion (in `WritingCoordinator`, before adding to `DocumentModel`):
- Wide flat stroke (xRange ≥ 100px, yRange < 30% of xRange)
- Horizontal — starts and ends on same line index
- Not a heading underline (which starts in bottom 20% of line and spans ≥ 80% of text width)
- **Effect:** all overlapping strokes on that line are removed from `DocumentModel` and canvas

---

## Recognition Pipeline

### Model Lifecycle
`ModelManager` uses `RemoteModelManager` (ML Kit) to download language models on demand. `GoogleMLKitTextRecognizer` holds one `DigitalInkRecognizer` instance per language session and reuses it across all recognition calls.

### Eager Recognition
Recognition is triggered eagerly — not on demand. The coordinator uses these triggers:

| Event | Action |
|---|---|
| User moves pen to a different line | Recognize previous line (`eagerRecognizeLine`) |
| Idle timeout (2 s after last stroke) | Recognize current line |
| Stroke on a line that has been rendered to text | Re-recognize that line immediately |
| App startup with existing strokes | Recognize all lines with missing/failed cache entries |
| Line scrolls above viewport | Recognize if not yet cached |

Recognition runs on `Dispatchers.IO` (`recognizer.recognizeLine`), but all cache mutations happen on the main thread (`Dispatchers.Main` via `lifecycleScope`). A `recognizingLines` set prevents duplicate concurrent recognitions; `pendingRerecognize` queues a follow-up if a line changes while it is being recognized.

### Pre-Context
Each recognition call includes up to 20 characters of preceding recognized text as `preContext`. This improves accuracy for words that depend on prior context (e.g. proper nouns, punctuation).

### Stroke Classification (pre-recognition filtering)
Before passing strokes to ML Kit, `StrokeClassifier` identifies and removes two special stroke types:

**List Marker:** a short, flat, simple horizontal stroke on the far left (within 10.5% of writing width) with a gap of ≥ 20px before the next stroke. Indicates a list item; filtered out to prevent the recognizer from seeing it as a letter.

**Underline (Heading):** a long, flat horizontal stroke in the lower half of the line spanning ≥ 80% of the text width. Indicates a heading. Filtered from recognition, but preserved in `DocumentData` for paragraph formatting.

Both checks use a **path simplicity** gate: `pathLength / diagonal ≤ 2.0`. This rejects strokes that trace back on themselves (e.g. a letter "s") that would otherwise match the geometric criteria.

---

## Text Display Synchronization

### Viewport-Based Reveal
Text is only shown for lines that have **ever** scrolled above the viewport midpoint (`everHiddenLines`). This set is monotonically growing (except when strokes are deleted). As the user scrolls down, lines disappear from canvas and appear as text in the text panel above.

### Scroll Offset Sync
The text panel scroll offset (`textScrollOffset`) is computed so that the text panel scrolls smoothly in sync with the canvas, creating the illusion that ink flows up into text. For each written line, its rendered text height contributes to the offset proportionally based on how far its successor line has scrolled below the viewport.

### Text Overscroll
When the canvas is already scrolled to its maximum useful position, further downward gutter drag increases `textOverscroll`, which shifts the text content upward inside `RecognizedTextView` so the user can read earlier text.

### Paragraph Formation
`ParagraphBuilder` groups `LineInfo` objects (classified lines) into paragraphs:

A paragraph break occurs when:
- The line is a list item (`isList`)
- The line is a heading (`isHeading`)
- The previous line was a heading (heading always stands alone)
- The line is indented (leftmost X > 10.5% of writing width) and the previous was not a list
- The previous paragraph was a list and this line is not

`RecognizedTextView` renders paragraphs as `StaticLayout` instances with:
- Body text: 64px, first-line indent 80px
- List items: bullet prefix `•`, hanging indent
- Headings: 1.3× size, bold, no indent
- Dimming: lines not yet in `notYetVisible` (i.e. partially visible) render in a light grey

---

## Undo/Redo

`UndoManager` maintains two `ArrayDeque<Snapshot>` stacks (max 50 entries). A `Snapshot` captures the full document state: all strokes, scroll offset, and the recognized text cache. Snapshots are saved:
- Before any stroke is added to the model
- Before a gesture mutation (strikethrough, line-drag)

The **scrub API** flattens both stacks into a single timeline for gesture-based navigation:

```
[ oldest_undo, ..., newest_undo, CURRENT, nearest_redo, ..., furthest_redo ]
```

On `endScrub`, the stacks are rebuilt from the timeline split at the final scrub position.

---

## Document Storage

### Format
Documents are stored as JSON files in `<filesDir>/documents/<name>.json`. The JSON schema includes:

```json
{
  "strokes": [{ "strokeId", "strokeWidth", "points": [{ "x", "y", "pressure", "timestamp" }] }],
  "scrollOffsetY": 0.0,
  "highestLineIndex": 5,
  "currentLineIndex": 5,
  "userRenamed": false,
  "lineTextCache": { "0": "Hello", "1": "World" },
  "everHiddenLines": [0, 1]
}
```

### Document Naming
New documents get a date-based name (`Document YYYY-MM-DD`). If the first line has an underline (heading marker) and the user has not manually renamed the document, `WritingCoordinator` fires `onHeadingDetected` and the activity renames the file to the heading text (sanitized, max 80 chars).

### Sync Folder Export
Via the Storage Access Framework (SAF), users can designate a folder for export. On every save, two files are written:
- `<name>.writer` — the full JSON (same as internal storage)
- `<name>.md` — markdown export: headings prefixed `## `, list items prefixed `- `, body text joined with spaces

### Migration
On first launch, the legacy single-file `document.json` is migrated to `documents/Document 1.json`.

---

## UI Layout

```
┌─────────────────────────────────────────┬───────┐
│                                         │       │
│         RecognizedTextView              │       │
│    (recognized text, bottom-aligned)    │       │
│                                         │ Gutter│
├─────────────────────────────────────────┤       │
│                                         │  drag │
│         HandwritingCanvasView           │  to   │
│     (ruled ink canvas, SurfaceView)     │ resize│
│                                         │       │
└─────────────────────────────────────────┴───────┘
```

The split between text and canvas is adjustable by dragging the gutter vertically. The text panel height ranges from its natural size to consuming the full screen. Default split: canvas takes 75% of screen height (configurable by weight in the layout XML).

The app runs in fully-immersive fullscreen mode (status bar and navigation bar hidden, swiped in transiently).

---

## Tutorial System

On first launch `TutorialManager` takes over the full screen:
1. Saves current document state and stops the coordinator
2. Expands the text panel to fit the tutorial text
3. Loads pre-built tutorial strokes and annotation overlays into the canvas
4. Shows a "Close Tutorial" button in the text panel and arrow annotations pointing at the gutter
5. On close: restores the original document state, layout, and restarts the coordinator

Tutorial content is generated programmatically by `TutorialContent` rather than hardcoded assets, so it adapts to the device's screen dimensions.

---

## Key Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `com.onyx.android.sdk:onyxsdk-pen` | 1.5.2 | Low-latency e-ink pen input on Boox devices |
| `com.onyx.android.sdk:onyxsdk-device` | 1.3.3 | Device-level Onyx APIs |
| `org.lsposed.hiddenapibypass` | 4.3 | Bypass hidden API restrictions on Android 14+ (required by Onyx SDK) |
| `com.google.mlkit:digital-ink-recognition` | 19.0.0 | On-device handwriting recognition |
| `androidx.room` | 2.6.1 | (Included in deps, not yet used for active storage) |
| `kotlinx-coroutines-android` | 1.8.1 | Async recognition and scroll animation |
| `kotlinx-coroutines-play-services` | 1.8.1 | Converts ML Kit `Task` to coroutine `await()` |
| `androidx.documentfile` | 1.0.1 | SAF sync folder export |

---

## Threading Model

All mutable state (`lineTextCache`, `everHiddenLines`, `recognizingLines`, `DocumentModel.activeStrokes`) lives on the **main thread**. Recognition work itself runs on `Dispatchers.IO` via `withContext`. The coordinator uses `lifecycleScope` as its coroutine scope, so all work is automatically cancelled when the activity is destroyed.

There is no ViewModel; `WritingCoordinator` is owned directly by `WritingActivity` and survives only within the activity lifecycle.

---

## Known Design Constraints / Technical Debt

- **Room dependency declared but unused.** `DocumentStorage` uses plain JSON files; Room is included as a dependency but has no entities or DAOs defined yet.
- **`DocumentListActivity` is a stub.** The launcher activity immediately redirects to `WritingActivity`. Multi-document management is handled via a popup menu inside `WritingActivity`.
- **No background save.** Documents save synchronously on the main thread during `onStop`. Large documents with many strokes may cause a perceptible pause.
- **Pre-context is approximate.** The 20-character pre-context is built from previously recognized lines in the same session. If a line was never recognized (e.g. first launch, no model yet), it contributes nothing to context.
- **Line-drag deletes overwritten content.** When dragging lines upward, any existing strokes in the overwritten zone are silently discarded. There is no conflict resolution.
- **Single language per document.** `DocumentModel.language` is set once at startup (`en-US`) with no UI to change it.
