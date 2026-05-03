---
status: approved
author: spm@edwei.com
created: 2026-03-23
updated: 2026-03-30
---

# Mokke - Ink Notes — Engineering Design

> Comprehensive technical guide to the Mokke - Ink Notes handwriting-to-text app for Android e-ink devices.

---

## Table of Contents

1. [Product Overview](#1-product-overview)
2. [Architecture Overview](#2-architecture-overview)
3. [Application Lifecycle & Navigation](#3-application-lifecycle--navigation)
4. [Data Models](#4-data-models)
5. [Input & Drawing Pipeline](#5-input--drawing-pipeline)
6. [Gesture Detection](#6-gesture-detection)
7. [Handwriting Recognition](#7-handwriting-recognition)
8. [Diagram Mode](#8-diagram-mode)
9. [Document Storage & Serialization](#9-document-storage--serialization)
10. [Display & Layout System](#10-display--layout-system)
11. [Undo/Redo System](#11-undoredo-system)
12. [Search & Indexing](#12-search--indexing)
13. [Tutorial System](#13-tutorial-system)
14. [Debugging & Telemetry](#14-debugging--telemetry)
15. [Build & Dependencies](#15-build--dependencies)

---

## 1. Product Overview

Mokke - Ink Notes is a handwriting-first note-taking app for Android e-ink tablets (primarily Boox devices). It treats handwritten strokes as first-class semantic objects - not raster images - that can be recognized as text, classified as diagrams, searched, exported, and reflowed.

### Core Concepts

- **Two Instruments**: Stylus always creates content (never mode-switched). Finger always navigates (never creates or deletes).
- **Cornell Notes**: Dual-column layout with a main writing area and a cue column for marginal annotations.
- **Post-capture classification**: Strokes are classified as text or diagram after writing, not before. No mode switching.
- **Structured note object**: Spatial fidelity, semantic classification, surface independence, version persistence.

### Design Principles (priority order)

1. No accidental data loss - destructive actions must be visible and reversible
2. Discoverable essentials - visible affordances within 30 seconds of first use
3. Minimize cognitive state - no hidden mode tracking
4. Convenience must not cannibalize creation - gestures never intercept plausible strokes

---

## 2. Architecture Overview

### Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.1, JVM target 17 |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |
| Serialization | Wire (Protocol Buffers) |
| Recognition | Google ML Kit Digital Ink / Onyx MyScript HWR |
| Search | AndroidX AppSearch (local, IcingSearchEngine) |
| Async | Kotlin Coroutines |
| E-ink SDK | Onyx onyxsdk-pen 1.5.2, onyxsdk-device 1.3.3 |
| DI | None (manual constructor injection) |

### Package Structure

```
com.writer
├── WriterApplication.kt          # App init (ScreenMetrics, HiddenApiBypass)
├── model/                        # Data models
│   ├── DocumentModel.kt          # Runtime document (two ColumnModels)
│   ├── DocumentData.kt           # Serializable snapshot
│   ├── ColumnModel.kt            # Runtime column state
│   ├── ColumnData.kt             # Serializable column snapshot
│   ├── InkStroke.kt              # Stroke with points, type, geometry flag
│   ├── StrokePoint.kt            # Single point (x, y, pressure, timestamp)
│   ├── InkLine.kt                # Group of strokes on one ruled line
│   ├── StrokeType.kt             # 18-variant enum (FREEHAND..DIAMOND)
│   ├── StrokeExtensions.kt       # Bounding box, path length extensions
│   ├── DiagramArea.kt            # Line-indexed diagram region
│   └── DiagramModel.kt           # Graph of nodes (shapes) and edges (connectors)
├── ui/
│   ├── documents/
│   │   └── DocumentListActivity.kt   # Launcher stub → WritingActivity
│   └── writing/
│       ├── WritingActivity.kt         # Main UI controller (1718 lines)
│       ├── WritingCoordinator.kt      # Business logic per column (767 lines)
│       ├── SaveAsActivity.kt          # Handwriting rename dialog
│       ├── DiagramManager.kt          # Diagram lifecycle orchestration
│       ├── DiagramStrokeClassifier.kt # Text vs drawing heuristics
│       ├── DiagramTextGrouping.kt     # Spatial clustering of diagram text
│       ├── DisplayManager.kt          # Text view updates, scroll animation
│       ├── GestureHandler.kt          # Strikethrough, underline, scribble-delete
│       ├── LineRecognitionManager.kt  # Debounced OCR orchestration
│       ├── ParagraphBuilder.kt        # Line→paragraph grouping with styles
│       ├── SpatialGrouping.kt         # Grid-based union-find clustering
│       ├── SpaceInsertMode.kt         # Insert/remove blank lines via button
│       ├── UndoManager.kt             # Dual-stack undo with gesture scrub
│       ├── UndoCoalescer.kt           # When to create undo snapshots
│       ├── ColumnLayoutLogic.kt       # Dual-column layout decisions
│       ├── OrientationManager.kt      # Manual rotation toggle
│       ├── AutoSaver.kt               # Debounced background save
│       ├── HersheyFont.kt             # Vector font for tutorial text
│       ├── TutorialManager.kt         # 7-step onboarding flow
│       ├── TutorialStep.kt            # Step definition
│       ├── TutorialContent.kt         # Tutorial stroke data
│       ├── TutorialDemoContent.kt     # Showcase document builder
│       ├── BugReport.kt               # JSON bug report serializer
│       ├── DebugBugReportProvider.kt   # ADB-accessible ContentProvider
│       └── StrokeEventLog.kt          # Ring buffer telemetry
├── recognition/
│   ├── TextRecognizer.kt              # Interface: recognizeLine(InkLine)
│   ├── TextRecognizerFactory.kt       # Runtime backend selection
│   ├── GoogleMLKitTextRecognizer.kt   # ML Kit Digital Ink backend
│   ├── OnyxHwrTextRecognizer.kt       # Boox MyScript AIDL backend
│   ├── HwrProtobuf.kt                # Hand-rolled protobuf for Boox IPC
│   ├── ModelManager.kt                # ML Kit model download lifecycle
│   ├── StrokeDownsampler.kt           # Dwell collapse + RDP simplification
│   ├── StrokeClassifier.kt            # List marker + underline detection
│   └── LineSegmenter.kt               # Stroke→line assignment by Y centroid
├── storage/
│   ├── DocumentStorage.kt             # File I/O (save/load/list/rename/sync)
│   ├── DocumentStorageSink.kt         # AutoSaver sink abstraction
│   ├── DocumentProtoMapper.kt         # Proto ↔ domain conversion
│   ├── SearchIndexManager.kt          # AppSearch full-text index
│   ├── NoteDocument.kt                # AppSearch document schema
│   └── SvgExporter.kt                 # Strokes → SVG string
└── view/
    ├── HandwritingCanvasView.kt       # Main canvas (touch, rendering, Onyx SDK)
    ├── RecognizedTextView.kt          # Rich text display with scroll sync
    ├── HandwritingNameInput.kt        # Single-line handwriting input
    ├── SplitLayout.kt                 # Resizable vertical split
    ├── CueIndicatorStrip.kt           # Dot strip showing cue positions
    ├── CuePreviewView.kt             # Long-press cue content popup
    ├── TutorialOverlay.kt             # Dimmed overlay with cutout
    ├── TouchFilter.kt                 # 5-layer palm rejection
    ├── CanvasTheme.kt                 # Stroke rendering (paths, arrowheads)
    ├── ScreenMetrics.kt               # DPI-aware sizing constants
    ├── ScratchOutDetection.kt         # Reversal-based erase detection
    ├── ShapeSnapDetection.kt          # Freehand → geometric shape fitting
    ├── ArrowDwellDetection.kt         # Pen-pause arrowhead classification
    ├── DiagramNodeSnap.kt             # Arrow endpoint → shape perimeter snap
    ├── DiagramTextFilter.kt           # Suppress diagram-internal text lines
    └── PreviewLayoutCalculator.kt     # Pure-logic preview metrics
```

### Key Architectural Patterns

| Pattern | Usage |
|---------|-------|
| **Coordinator** | WritingCoordinator separates business logic from Activity UI |
| **Dual Coordinator** | Each column (main + cue) has independent coordinator instance |
| **Strategy** | TextRecognizer interface with two backends |
| **Factory** | TextRecognizerFactory selects backend at runtime |
| **Snapshot** | UndoManager captures full state for undo/redo |
| **Debounce** | AutoSaver (5s), LineRecognitionManager, search queries |
| **Ring Buffer** | StrokeEventLog for bounded telemetry |
| **Pure Logic** | ColumnLayoutLogic, PreviewLayoutCalculator, OrientationLogic - testable without Android |

---

## 3. Application Lifecycle & Navigation

### Startup Flow

```
Process Start
  → WriterApplication.onCreate()
      ├── ScreenMetrics.init(displayMetrics, configuration)
      └── HiddenApiBypass.addHiddenApiExemptions("") [API 28+]
  → DocumentListActivity.onCreate()
      ├── startActivity(WritingActivity)
      └── finish()  // immediate redirect, no document list UI yet
  → WritingActivity.onCreate()
      ├── Register BroadcastReceiver (remote bug report)
      ├── Set fullscreen (hide status/nav bars)
      ├── Enable Boox SDK fullscreen mode
      ├── Create DocumentModel
      ├── Restore saved DocumentData (or create new)
      ├── Initialize TextRecognizer (async)
      ├── startCoordinator() → main WritingCoordinator
      ├── Check AppSearch migration
      ├── Show tutorial (if first launch)
      └── initDualCanvasOnyx() (if landscape)
```

### Activity Stack

| Activity | Role | Notes |
|----------|------|-------|
| `DocumentListActivity` | Launcher entry point | Immediately redirects to WritingActivity |
| `WritingActivity` | Main writing interface | Handles orientation changes via `onConfigurationChanged` |
| `SaveAsActivity` | Rename dialog | Fullscreen handwriting input, returns name via intent |

### WritingActivity Key Responsibilities

- Manages two `HandwritingCanvasView` + `RecognizedTextView` pairs (main + cue)
- Creates and owns `WritingCoordinator` instances (one per column)
- Handles orientation changes without recreating (manifest: `configChanges`)
- Manages Onyx SDK lifecycle (init, pause, resume, transfer between canvases)
- Drives auto-save, menu, document switching, sync folder selection
- Coordinates linked scroll between main and cue columns

### WritingCoordinator Responsibilities

One instance per column. Owns all business logic:

- Receives completed strokes from canvas
- Routes through gesture detection (scratch-out, strikethrough)
- Manages diagram lifecycle (create, expand, shrink)
- Drives line recognition pipeline
- Maintains undo/redo state
- Provides state snapshots for save
- Generates markdown export and bug reports

---

## 4. Data Models

### Runtime Model

```
DocumentModel
├── main: ColumnModel            # Primary writing area
├── cue: ColumnModel             # Cornell notes margin
└── language: String             # BCP-47 tag (e.g. "en-US")

ColumnModel
├── activeStrokes: MutableList<InkStroke>
├── diagramAreas: MutableList<DiagramArea>
└── diagram: DiagramModel
    ├── nodes: Map<strokeId, DiagramNode>   # Shapes
    └── edges: Map<strokeId, DiagramEdge>   # Connectors
```

### Serializable Snapshot

```
DocumentData                        # Immutable snapshot for save/undo
├── main: ColumnData
├── cue: ColumnData                 # Omitted if empty
├── scrollOffsetY: Float
├── highestLineIndex: Int
├── currentLineIndex: Int
└── userRenamed: Boolean

ColumnData
├── strokes: List<InkStroke>
├── lineTextCache: Map<Int, String> # Recognized text per line
├── everHiddenLines: Set<Int>       # Lines that scrolled off
└── diagramAreas: List<DiagramArea>
```

### Core Data Classes

**InkStroke** - A single pen stroke:
- `strokeId: String` (UUID)
- `points: List<StrokePoint>` (ordered, pressure-sensitive)
- `strokeWidth: Float` (default 3f)
- `startTime / endTime: Long` (from first/last point timestamp)
- `isGeometric: Boolean` (true for snapped shapes - rendered with sharp corners)
- `strokeType: StrokeType` (18 variants)

**StrokePoint** - Single sample:
- `x, y: Float` (document-space coordinates, Y includes scroll offset)
- `pressure: Float` (0-1, defaults to 1.0)
- `timestamp: Long` (milliseconds)

**StrokeType** - Classification enum:
- Basic: `FREEHAND`
- Lines: `LINE`, `ARROW_HEAD`, `ARROW_TAIL`, `ARROW_BOTH`
- Elbows: `ELBOW`, `ELBOW_ARROW_HEAD/TAIL/BOTH`
- Arcs: `ARC`, `ARC_ARROW_HEAD/TAIL/BOTH`
- Shapes: `ELLIPSE`, `RECTANGLE`, `ROUNDED_RECTANGLE`, `TRIANGLE`, `DIAMOND`

**InkLine** - Strokes grouped on one ruled line:
- `strokes: List<InkStroke>` (sorted left-to-right by minX)
- `boundingBox: RectF` (tight bounds across all points)

**DiagramArea** - Line-indexed spatial region:
- `id: String` (UUID)
- `startLineIndex: Int`, `heightInLines: Int`
- Computed: `endLineIndex = startLineIndex + heightInLines - 1`

---

## 5. Input & Drawing Pipeline

### Touch Input Sources

**1. Onyx SDK (primary, Boox devices):**
Raw input callback bypasses Android view system for low-latency e-ink rendering.
- `onBeginRawDrawing()` → `beginStroke()`
- `onRawDrawingTouchPointMoveReceived()` → `addStrokePoint()`
- `onEndRawDrawing()` → `endStroke()`
- SDK renders strokes directly to e-ink controller (not via Android Canvas)

**2. Fallback MotionEvent (non-Boox, testing):**
Standard Android touch handling via `onTouchEvent()`.
- Stylus/mouse: ACTION_DOWN/MOVE/UP → stroke lifecycle
- Finger: routed to `handleFingerTouch()` for scroll only

### Palm Rejection (TouchFilter)

Five-layer filter, cheapest checks first:

1. **Pen proximity** - reject all finger touches while stylus hovers
2. **Pen cooldown** - reject finger within 500ms of pen lift
3. **Touch size** - reject contacts >40dp (palm)
4. **Multi-touch** - reject when pointerCount > 1
5. **Stationary timeout** - reject finger still >200ms without moving >8dp

### Stroke Lifecycle

```
beginStroke(firstPoint)
├── Set penActive, fire onPenDown callback
├── Clear currentStrokePoints
├── Get diagram bounds for Y position
├── Initialize running bounding box (O(1))
└── Start dwell timer for arrow/shape detection

addStrokePoint(point)         [per-point, during drawing]
├── Append to currentStrokePoints
├── Update running bounding box (O(1))
└── Check dwell cancellation (>8dp movement)

endStroke(lastPoint)
├── Cancel dwell timer
├── Set penActive=false, record penUpTimestamp
├── Append final point
└── finishStroke() → processing
```

### Running Bounding Box Optimization

Maintains `strokeMinX/MaxX/MinY/MaxY` updated O(1) per point. Avoids O(n^2) cost of scanning all points after each addition. Profiling showed: naive approach = 136-267ms for 551-781 point strokes; with running bounds = 3ms.

### Stroke Processing (finishStroke)

**Text strokes:**
1. Check scratch-out (rapid back-and-forth) → erase overlapping strokes
2. Check shape snap (if dwell detected) → create geometric stroke
3. If shape snapped outside diagram → fire `onDiagramShapeDetected` (auto-create diagram)
4. Auto-classify via DiagramStrokeClassifier (score >= 0.5 → diagram)
5. Emit stroke, fire `onStrokeCompleted` callback

**Diagram strokes:**
1. Raw stroke captured for test fixtures
2. Shape snap attempt (with magnetic arrow endpoint snapping)
3. Scratch-out detection
4. Emit stroke (raw or snapped replacement)
5. Check diagram overflow → trigger expansion
6. Force e-ink refresh (pause → drawToSurface → resume)

### Rendering Pipeline (renderContent)

Rendering order (back to front):
1. White background
2. Scroll transform: `canvas.translate(0f, -scrollOffsetY)`
3. Ruled lines (gray horizontal lines at LINE_SPACING intervals)
4. Ghost strokes (tutorial demo with progressive reveal)
5. Completed strokes (viewport-culled, space-insert preview shift applied)
6. In-progress stroke (fallback rendering for non-SDK)
7. Dwell indicator dot
8. Diagram borders (solid or dashed rectangles)
9. Space-insert handles (dashed lines, red barriers at content)
10. Tutorial annotations (no scroll offset)

### Stroke Drawing (CanvasTheme)

- **Arc strokes** (3 points): quadratic bezier `moveTo → quadTo`
- **Geometric strokes** (`isGeometric=true`): sharp corners `moveTo → lineTo → lineTo...`
- **Freehand strokes**: smooth interpolation via quadratic bezier through midpoints
- **Arrowheads**: isoceles triangles computed from local tangent direction
- Paint: black, stroke style, round cap/join, no antialiasing

### E-ink Refresh Protocol

```
pauseRawDrawing()     // disable Onyx SDK overlay
drawToSurface()       // manual Canvas render to SurfaceView
resumeRawDrawing()    // re-enable SDK for writing
```

Used after: scrolling, gesture handling, text recognition refresh, shape snapping.

---

## 6. Gesture Detection

### Scratch-Out (ScratchOutDetection)

Detects rapid back-and-forth strokes for erasing.

**Algorithm:**
1. Count direction reversals on X and Y axes independently
2. Select dominant axis (more reversals or more travel)
3. Apply thresholds:
   - MIN_REVERSALS = 3 (cursive has <= 2 from letter loops)
   - MIN_X_TRAVEL = 0.5x line spacing (~59px)
   - MAX_Y_DRIFT = 0.4x line spacing (cross-axis range limit)
   - MAX_ADVANCE_RATIO = 0.4 (net progress / travel)
   - PATH_RATIO > 4.5 (distinguishes zigzag from closed outline)
4. Focused validation: builds spatial grid, samples scratch points, requires >= 30% overlap with existing strokes

### Strikethrough (GestureHandler)

Horizontal line drawn through existing text:
- xRange >= 54dp, yRange < 30% of xRange
- pathLength <= 1.3x diagonal (approximately straight)
- Must cross strokes > 1500ms old (avoids erasing while writing)
- Spans >= 60% of existing text width
- Within +/- 35% of text vertical center

### Underline Detection

- Starts in bottom 20% of line
- Spans >= 80% of existing text width
- pathLength <= 2x diagonal

### Diagram Scribble-Delete

- pathLength >= 4x diagonal
- Bounding box >= 60px
- >= 3 horizontal direction reversals

---

## 7. Handwriting Recognition

### Architecture

```
TextRecognizer (interface)
├── OnyxHwrTextRecognizer    # Boox MyScript engine (AIDL IPC)
└── GoogleMLKitTextRecognizer # Universal ML Kit fallback

TextRecognizerFactory.create(context)
  → if Boox device: OnyxHwrTextRecognizer
  → else: GoogleMLKitTextRecognizer
```

### OnyxHwrTextRecognizer (Primary)

Uses Boox's embedded MyScript engine via AIDL service binding:
1. Bind to `com.onyx.android.ksync.service.KHwrService`
2. Build hand-rolled protobuf from InkLine (`HwrProtobuf.kt`)
3. Write protobuf to pipe, pass read-end to service
4. Service returns JSON: `{"result":{"label":"recognized text"}}`
5. Concurrent pipe writing prevents deadlock on large inputs (>64KB)
6. 10-second timeout per recognition call

### GoogleMLKitTextRecognizer (Fallback)

Universal recognition for non-Boox devices:
1. Download language model via `ModelManager` (cached locally)
2. Convert InkLine strokes to ML Kit `Ink` object
3. Set `RecognitionContext` with pre-context (last 20 chars) and writing area
4. Call `recognizer.recognize(ink, context)`
5. Extract top candidate

### Recognition Pipeline

```
WritingCoordinator.onStrokeCompleted()
  → LineSegmenter.getStrokeLineIndex()     # Assign stroke to line by Y centroid
  → User switches lines? → eager recognize old line
  → Edited rendered line? → immediate re-recognize

LineRecognitionManager.doRecognizeLine(lineIndex)
  → Skip if diagram line
  → Get strokes for line from ColumnModel
  → StrokeClassifier.filterMarkerStrokes()  # Remove list markers, underlines
  → LineSegmenter.buildInkLine()            # Sort left-to-right, compute bbox
  → Build pre-context from cached text (up to 20 chars)
  → TextRecognizer.recognizeLine(line, preContext)
  → Cache result in lineTextCache[lineIndex]
  → Check if first line is heading → auto-rename
```

### Preprocessing

**StrokeDownsampler** - reduces point count:
1. Dwell collapse: merge sub-pixel stationary points (epsilon = 0.5px)
2. Ramer-Douglas-Peucker simplification (epsilon = 0.5)
3. Typically 30-50% point reduction with no visible quality loss

**LineSegmenter** - assigns strokes to ruled lines:
- Uses center-of-mass Y (not bounding box centroid) for robustness with descenders
- `lineIndex = ((yMean - TOP_MARGIN) / LINE_SPACING).toInt()`

**StrokeClassifier** - identifies non-text strokes:
- List markers: short horizontal dashes at left margin (< 10.5% indent)
- Underlines: long horizontal strokes at line bottom spanning >= 80% of text
- Adjacent-line underlines: underline on line N+1 belonging to line N

### Post-Processing

**ParagraphBuilder** - groups lines into styled paragraphs:
- Classifies each line: `LineInfo(lineIndex, text, isList, isHeading)`
- Breaks paragraphs on: list items, headings, indentation changes, diagram areas
- Result: `List<List<LineInfo>>` (paragraphs of lines)

---

## 8. Diagram Mode

### Lifecycle

1. **Detection**: Shape snaps to geometric form or DiagramStrokeClassifier score >= 0.5
2. **Creation**: `DiagramManager.onShapeDetected()` creates DiagramArea with 1-line padding, merges adjacent areas
3. **Expansion**: `onStrokeOverflow()` when drawing extends beyond bounds - two-phase shift (upward/downward) with scroll compensation
4. **Recognition**: Debounced (600ms) text recognition of freehand strokes within diagram
5. **Shrinking**: `shrinkDiagramAfterErase()` recomputes tight bounds after stroke erasure

### Shape Detection (ShapeSnapDetection)

Triggered by end-dwell (user holds pen still). Detects in priority order:

**Closed strokes:**
1. **Ellipse** - signed distance to inscribed ellipse, straightness check rejects rounded rectangles
2. **Triangle** - exactly 3 corners via angle-change derivative
3. **Diamond** - 4+ corners near bbox edge midpoints
4. **Rounded Rectangle** - no sharp corners, perimeter deviation < 15%
5. **Rectangle** - points distributed near edges, same deviation checks

**Open strokes:**
1. **Line** - max perpendicular deviation < 12% of length
2. **Elbow** - L-shaped, corner angle 60-120 degrees, straight legs
3. **Arc** - curved, bezier fit < 8% deviation
4. **Self-Loop** - start near end, ellipse fit < 15%
5. **Curve** - fallback smooth path

### Arrow Classification (ArrowDwellDetection)

Classifies connector endpoints based on pen-pause:
- Dwell at end → ARROW_HEAD
- Dwell at start → ARROW_TAIL
- Both → ARROW_BOTH
- Neither → LINE

### Node Snapping (DiagramNodeSnap)

Arrow endpoints snap to nearest shape perimeter within 1.5 line-spacings:
- Ellipse: radial projection from center
- Rectangle/Diamond: clamping with edge projection
- Degenerate guard: prevents both endpoints snapping to same point (zero-length arrow)
- Self-loop detection: both endpoints near same node with path length / distance > 2.0

### Diagram Text Recognition

```
DiagramManager.recognizeDiagramArea()
  → DiagramStrokeClassifier: partition strokes into text vs drawing
  → DiagramTextGrouping: proximity-based spatial clustering
  → Row splitting: Y-centroid clustering with 0.8x median height gap
  → TextRecognizer: recognize each text group
  → mergeAdjacentTextGroups: fix capital letter separation ("E"+"mpathy" → "Empathy")
```

**Stroke Classification Heuristics** (scored 0.0-1.0):
- Height > 2.5x LINE_SPACING → +0.6
- Path complexity > 3.5x diagonal → +0.4
- Closure (start near end) → +0.3
- Line density filter: >= 80% of points within 0.6x LINE_SPACING of Y-median → force TEXT
- Group contagion: any drawing stroke in proximity group → entire group becomes drawing
- Geometric shapes excluded from contagion (labels stay as text)

---

## 9. Document Storage & Serialization

### File Format

**Primary**: `.inkup` (Protocol Buffers binary via Wire)
**Legacy**: `.json` (read-only fallback, auto-migrated)
**Location**: `app/files/documents/{name}.inkup`

### Proto Schema (document.proto)

```protobuf
DocumentProto
├── main: ColumnDataProto
├── cue: ColumnDataProto
├── scroll_offset_y: float           # Normalized to line units
├── highest_line_index: int32
├── current_line_index: int32
├── user_renamed: bool
└── coordinate_system: int32         # 0=legacy pixels, 1=normalized

ColumnDataProto
├── strokes: repeated InkStrokeProto
├── line_text_cache: map<int32, string>
├── ever_hidden_lines: repeated int32
└── diagram_areas: repeated DiagramAreaProto

InkStrokeProto
├── stroke_id, stroke_width, stroke_type, is_geometric
├── points: repeated StrokePointProto  # Legacy (empty if using runs)
├── x_run, y_run: NumericRunProto      # Compact delta encoding (v3+)
├── pressure_run: NumericRunProto      # Omitted if all 1.0
├── time_run: NumericRunProto          # Omitted if timestamps not needed
└── stroke_timestamp: int64            # Base epoch ms (v4)

NumericRunProto                        # Delta-encoded numeric sequence
├── deltas: repeated sint32 [packed]   # Delta values
├── scale: float [default=1]           # Quantization scale
└── offset: float [default=0]          # First value offset
```

### Coordinate Normalization

Coordinates stored relative to line spacing for device-agnostic documents:

| Direction | Domain → Proto | Proto → Domain |
|-----------|----------------|----------------|
| X | `x / lineSpacing` | `x * lineSpacing` |
| Y | `(y - topMargin) / lineSpacing` | `topMargin + y * lineSpacing` |

### Compression Strategy

1. **Delta encoding**: Store differences between consecutive values as sint32
2. **Per-channel quantization**: coordinates scale=0.01, pressure scale=0.01, time scale=1.0
3. **Optional field omission**: pressure_run omitted if all 1.0, time_run omitted if all 0

### Storage Operations (DocumentStorage)

| Operation | Details |
|-----------|---------|
| `save()` | AtomicFile write (.new backup, atomic rename) |
| `load()` | Try .inkup → fallback .json → parse with coordinate denormalization |
| `listDocuments()` | Enumerate .inkup + .json, dedup by name, sort by modification time |
| `rename()` | Rename file + update search index |
| `delete()` | Remove files + search index entry |
| `exportToSyncFolder()` | Write .inkup + .md to SAF URI |
| `restoreFromSyncFolder()` | Import .writer/.inkup from SAF URI |
| `generateName()` | "Document YYYY-MM-DD" with dedup suffix |
| `generateNameFromHeading()` | Sanitize first heading as filename (max 80 chars) |

### Auto-Save (AutoSaver)

- Debounced (5000ms default) with timer reset on continued writing
- Main thread: capture snapshot (DocumentData + markdown)
- IO thread: `DocumentStorage.save()` → AtomicFile write
- Post-save: async `SearchIndexManager.indexDocument()`
- If sync configured: export .inkup + .md to sync folder
- `saveBlocking()` for app shutdown/onPause
- Sink interface for testability

---

## 10. Display & Layout System

### Column Layout (ColumnLayoutLogic)

| Screen | Portrait | Landscape |
|--------|----------|-----------|
| **Large** (sw >= 900dp) | Dual column: 70% main, 30% cue (expandable) | Dual column: 70% main, 30% cue (fixed) |
| **Small** (sw < 900dp) | Single column with fold/unfold | 50/50 dual column |

- `isDualColumn`: true when large screen OR landscape
- `canFoldUnfold`: small screen portrait only (tap to switch main↔cue views)
- `showIndicatorStrip`: single-column portrait (dot strip showing cue positions)

### Screen Metrics

| Constant | Large Screen | Small Screen |
|----------|-------------|-------------|
| LINE_SPACING | 50dp (~8mm) | 41dp (~6.5mm) |
| CANVAS_FRACTION | 70% | 82% |
| TOP_MARGIN | 19dp (~3mm) | 19dp |
| STROKE_WIDTH | 2.6dp (~0.42mm) | 2.6dp |

### RecognizedTextView

Custom view displaying recognized text alongside the canvas:

**Render items:**
- `TextRenderItem`: StaticLayout with word-wrapped paragraphs (SpannableStringBuilder)
- `DiagramRenderItem`: Scaled ink strokes with text label overlays

**Text formatting:**
- List items: bullet prefix (bullet) with hanging indent
- Headings: 1.3x size + bold
- Normal paragraphs: first-line indent (43dp)
- Dimmed segments: grayed out during recognition

**Scroll synchronization:**
- `textScrollOffset`: matches canvas scroll position
- `writtenLineHeights`: maps line index to rendered height
- Touch: pen taps resolve to line index via layout metrics; finger scrolls text independently

### DisplayManager

- Tracks `everHiddenLines` (scrolled off canvas)
- Lazy-recognizes hidden lines in background
- Builds text paragraphs and diagram displays for RecognizedTextView
- Tap-to-scroll: tapping a line in the text view scrolls the canvas to that line

### SplitLayout

Custom vertical LinearLayout with resizable divider:
- 1dp visual divider, 24dp touch target each side
- Finger-only (stylus passes through for writing)
- Callbacks: `onSplitDragStart/Drag/End`

### CueIndicatorStrip

16dp visual strip on right edge of portrait canvas:
- Dots at line positions containing cue strokes
- Thick segments for contiguous multi-line blocks
- Tap → fold to cue view
- Long-press → peek cue content via CuePreviewView popup

---

## 11. Undo/Redo System

### UndoManager

Dual-stack architecture with gesture scrub support:

**Snapshot:**
```
Snapshot {
    strokes: List<InkStroke>
    scrollOffsetY: Float
    lineTextCache: Map<Int, String>
    diagramAreas: List<DiagramArea>
}
```

- Max 50 snapshots per stack
- `saveSnapshot()` pushes to undo stack, clears redo
- `undo()` pops undo → pushes to redo, returns previous snapshot
- `redo()` pops redo → pushes to undo, returns next snapshot

**Gesture Scrub API:**
- `beginScrub(current)`: builds unified timeline [oldest_undo...current...furthest_redo]
- `scrubTo(offset)`: navigates timeline without committing
- `endScrub()`: commits final position back to stacks
- Enables fluid gesture-driven undo without discrete button taps

### UndoCoalescer

Controls when snapshots are created:

| Action | Behavior |
|--------|----------|
| `STROKE_ADDED` | Coalesces with previous if < 2000ms and same line |
| `SCRATCH_OUT` | Always creates snapshot (destructive) |
| `STROKE_REPLACED` | Always creates snapshot (structural change) |
| `GESTURE_CONSUMED` | Always creates snapshot |
| `DIAGRAM_CREATED` | Always creates snapshot |
| `ZONE_EXPANDED` | Always creates snapshot |
| `SPACE_INSERTED` | Always creates snapshot |

Coalescing rule for `STROKE_ADDED`: skip snapshot if time gap < 2000ms AND same line AND same action type.

---

## 12. Search & Indexing

### SearchIndexManager

Backend: AndroidX AppSearch with IcingSearchEngine (local-only, encrypted).

**NoteDocument schema:**
```kotlin
@Document
data class NoteDocument(
    @Document.Id val id: String,                    // document name
    @Document.StringProperty(PREFIXES) val title,   // indexed with prefix search
    @Document.StringProperty(PREFIXES) val body,     // full text, lowercased
    @Document.LongProperty val lastModified,
    @Document.StringProperty(NONE) val lineData      // JSON: lineIdx → text
)
```

**Indexing:** concatenates all `lineTextCache` values (main + cue), lowercased, newline-separated.

**Query pipeline:**
1. Full-text prefix match on title + body via AppSearch
2. For each result: parse `lineData` JSON, filter lines containing query
3. Return `Map<docName, List<SearchMatch(lineIdx, text)>>`
4. UI: click result → scroll to matching line

**Triggers:** index after save (async), remove on delete, rename on rename, rebuild on demand.

---

## 13. Tutorial System

### 7-Step Onboarding Flow

| Step | Action | Trigger |
|------|--------|---------|
| 1. `write` | Draw text with stylus | `stroke_completed` |
| 2. `draw` | Draw shape, hold to snap | `diagram_created` |
| 3. `erase` | Strikethrough + scratch-out | `scratch_out` |
| 4. `scroll` | Finger scroll | scroll detected |
| 5. `switch_to_cues` | Toggle to Cornell Notes | view toggle |
| 6. `peek_note` | Press-and-hold on indicator | peek gesture |
| 7. `write_cue` | Add cues to notes | `stroke_completed` in cue |

**Components:**
- `TutorialManager`: state machine, action detection, auto-advance (1.5s after action)
- `TutorialStep`: cutout rect, tooltip text/position, accepted input type
- `TutorialOverlay`: dimmed background + clear cutout + tooltip card + buttons
- `TutorialDemoContent`: pre-built flowchart using HersheyFont cursive
- `HersheyFont`: .jhf vector font (single-stroke polylines for handwriting simulation)

State persisted in SharedPreferences. Canvas cleared for tutorial, restored on completion.

---

## 14. Debugging & Telemetry

### StrokeEventLog

Always-on ring buffer (default capacity: 50 strokes, 150 events):
- Strokes downsampled via StrokeDownsampler before storage
- Events: ADDED, SCRATCH_OUT, SHAPE_SNAPPED, REPLACED, RECOGNIZED, DIAGRAM_CREATED, etc.
- O(1) eviction, lazy event filtering at serialization time

### BugReport

JSON report containing:
- Device info (model, manufacturer, OS, density, line spacing)
- Recent strokes with points (from ring buffer)
- Processing events with timing
- Performance summary (p50, p95, max, count > 50ms)
- Full document state (strokes, diagram areas, line text cache)

### DebugBugReportProvider

ContentProvider for remote debugging via ADB:
```bash
# Generate bug report
adb shell am broadcast -W -a com.writer.dev.GENERATE_BUG_REPORT

# Read it
adb shell content read --uri content://com.writer.dev.debug/bugreport
```

Serves latest JSON file from `<externalFilesDir>/Documents/bug-reports/`.

---

## 15. Build & Dependencies

### Key Dependencies

| Category | Package |
|----------|---------|
| E-ink hardware | `com.onyx.android.sdk:onyxsdk-pen:1.5.2`, `onyxsdk-device:1.3.3` |
| Recognition | `com.google.mlkit:digital-ink-recognition:19.0.0` |
| Serialization | Wire (Protocol Buffers) |
| Search | `androidx.appsearch:appsearch:1.1.0-alpha05`, `appsearch-local-storage` |
| Coroutines | `kotlinx-coroutines-android:1.8.1` |
| Hidden API | `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` |
| Testing | `junit:4.13.2`, `robolectric:4.14.1` |

### Build Commands

```bash
./gradlew assembleDebug          # Build
./gradlew installDebug           # Install to device
./gradlew testDebugUnitTest      # Unit tests (no device)
./gradlew allTests               # Unit + instrumented
./gradlew localReview            # Self-review diff
```

NDK filter (debug): `arm64-v8a` (64-bit ARM for Boox tablets).
