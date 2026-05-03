---
status: approved
author: spm@edwei.com
created: 2026-04-15
updated: 2026-04-15
---

# Document Format and Data Model

## Overview

InkUp uses a layered storage architecture:

1. **In-memory:** `DocumentData` — mutable Kotlin data classes for runtime editing
2. **Automerge CRDT:** delta-encoded binary with packed points — primary persistence, future sync
3. **.mok ZIP:** protobuf + audio — interchange format, SAF export, local-state persistence

```
Pen input → InkStroke → DocumentData snapshot
  → AutomergeSync (diff by ID, write deltas only)
  → AutomergeStorage (.automerge binary)

Document close:
  → .mok ZIP (local-only state + audio for SAF)
```

## Data Model

### DocumentData

```kotlin
DocumentData(
    main: ColumnData,                      // primary writing column
    cue: ColumnData,                       // secondary notes column
    scrollOffsetY: Float,                  // viewport Y position (local-only, not synced)
    highestLineIndex: Int,                 // line tracking (local-only)
    currentLineIndex: Int,                 // active line (local-only)
    userRenamed: Boolean,                  // manual rename flag (local-only)
    audioRecordings: List<AudioRecording>, // document-level audio
)
```

### ColumnData

```kotlin
ColumnData(
    strokes: List<InkStroke>,              // all ink strokes
    lineTextCache: Map<Int, String>,       // recognized text per line (derived, not synced)
    everHiddenLines: Set<Int>,             // hidden line indices (derived, not synced)
    diagramAreas: List<DiagramArea>,       // diagram regions
    textBlocks: List<TextBlock>,           // transcribed text blocks
)
```

### InkStroke

```kotlin
InkStroke(
    strokeId: String,          // UUID, unique across sync
    points: List<StrokePoint>, // sampled pen input
    strokeWidth: Float,        // pen width (not persisted to Automerge — constant DEFAULT_STROKE_WIDTH)
    startTime: Long,           // first point timestamp (derived)
    endTime: Long,             // last point timestamp (derived)
    isGeometric: Boolean,      // true for snapped shapes (sharp corners)
    strokeType: StrokeType,    // FREEHAND, LINE, ARROW_HEAD, RECTANGLE, etc.
)
```

### StrokePoint

```kotlin
StrokePoint(
    x: Float,        // screen X in pixels
    y: Float,        // screen Y in pixels
    pressure: Float,  // pen pressure 0.0–1.0
    timestamp: Long,  // absolute milliseconds since epoch
)
```

### StrokeType

18 variants: `FREEHAND`, `LINE`, `ARROW_HEAD`, `ARROW_TAIL`, `ARROW_BOTH`, `ELBOW` (+ arrow variants), `ARC` (+ arrow variants), `ELLIPSE`, `RECTANGLE`, `ROUNDED_RECTANGLE`, `TRIANGLE`, `DIAMOND`.

### TextBlock

```kotlin
TextBlock(
    id: String,              // UUID
    startLineIndex: Int,     // line position (line-height units)
    heightInLines: Int,      // vertical span
    text: String,            // recognized text
    audioFile: String,       // associated audio filename (e.g., "rec-1713078000000.ogg")
    audioStartMs: Long,      // audio timestamp range
    audioEndMs: Long,
    words: List<WordInfo>,   // per-word metadata from speech recognition
)
```

### DiagramArea

```kotlin
DiagramArea(
    id: String,          // UUID
    startLineIndex: Int, // line position
    heightInLines: Int,  // vertical span
)
```

### AudioRecording

```kotlin
AudioRecording(
    audioFile: String,    // filename in sidecar directory
    startTimeMs: Long,    // recording start (epoch ms)
    durationMs: Long,     // recording duration
)
```

## Automerge Schema (v2)

```
Document root
├── _schemaVersion: 2
├── main (Map)
│   ├── strokes (List of Maps)
│   │   └── { strokeId: String,
│   │         strokeType: String,
│   │         isGeometric: Bool,
│   │         originLine: Int,         ← line index, updated on space insert
│   │         heightInLines: Int,      ← stroke span for viewport filtering
│   │         pointsData: byte[] }     ← delta-encoded packed points
│   ├── textBlocks (List of Maps)
│   │   └── { id, startLineIndex, heightInLines, text, audioFile,
│   │         audioStartMs, audioEndMs,
│   │         words: List of { text, confidence, startMs, endMs } }
│   └── diagramAreas (List of Maps)
│       └── { id, startLineIndex, heightInLines }
├── cue (Map) — same structure as main
└── audioRecordings (List of Maps)
    └── { audioFile, startTimeMs, durationMs }
```

**Not synced** (persisted to .mok only): scrollOffsetY, highestLineIndex, currentLineIndex, userRenamed, lineTextCache, everHiddenLines.

## Packed Points Format (pointsData)

Coordinates are **normalized to line-height units** (device-independent). Y is relative to `originLine`.

```
Header (22 bytes, little-endian):
  numPoints:     u16    (max 65535 points per stroke)
  baseTimestamp:  i64    (absolute ms of first point)
  x0:            i32    (first X in quantized units)
  y0:            i32    (first Y, relative to originLine)
  p0:            i32    (first pressure in quantized units)

Subsequent points (varint-encoded deltas):
  dx:         varsint   (zigzag + varint, typically 1 byte)
  dy:         varsint
  dPressure:  varsint
  dTimestamp:  varint   (unsigned, typically 1–2 bytes)
```

**Quantization scales:**
- Coordinates: 0.01 line-height (~0.94px at 94px line spacing)
- Pressure: 0.001 (0–1000 range)

**Typical size:** 22 bytes header + ~5 bytes/point. A 20-point stroke ≈ 120 bytes.

**Conversion:**
```
Pack:   quantizedX = round(pixelX / lineSpacing / 0.01)
        quantizedY = round((pixelY - originY) / lineSpacing / 0.01)
        where originY = topMargin + originLine * lineSpacing

Unpack: pixelX = quantizedX * 0.01 * lineSpacing
        pixelY = originY + quantizedY * 0.01 * lineSpacing
```

**Space insert:** bumps `originLine` without rewriting `pointsData`. Points are relative to origin, so they shift automatically on unpack.

## File Layout

```
app/files/documents/
├── Document Name.automerge          ← Automerge binary (primary)
├── Document Name.automerge.inc      ← incremental changes (appended between full saves)
├── Document Name.checkpoints.json   ← version history checkpoints
├── Document Name.mok                ← ZIP bundle (protobuf + audio, for SAF/local state)
├── .audio-Document Name/            ← audio file sidecar cache
│   ├── rec-1713078000000.ogg
│   └── rec-1713079200000.ogg
└── ...
```

### .automerge file

Raw binary from `Document.save()`. Contains the full Automerge CRDT state including change history DAG. Loaded via `Document.load(bytes)`.

### .automerge.inc file

Appended incremental changes from `Document.encodeChangesSince(lastSavedHeads)`. Applied to the base document on load via `Document.applyEncodedChanges(bytes)`. Deleted after each full save.

### .mok file (ZIP bundle)

```
document.mok (ZIP):
├── document.pb    ← protobuf binary (DocumentProto)
└── audio/
    ├── rec-*.ogg  ← audio files
    └── ...
```

Written on document close and SAF export. Contains local-only state (scroll, line index) and audio. The protobuf uses line-height normalized coordinates with delta-encoded runs (NumericRunProto).

### .checkpoints.json

```json
[
  {
    "label": "2:30 PM",
    "timestamp": 1713078600000,
    "heads": ["a1b2c3d4e5f6..."]
  }
]
```

Heads are hex-encoded `ChangeHash` byte arrays. Created after 10 seconds of idle following a burst of activity. Used by version history to fork the document at a previous state.

### Audio sidecar (.audio-*)

Write-once audio files cached on disk. Auto-save reads from sidecar (no ZIP parsing). Audio packed into .mok ZIP only during SAF export or document close. Files named by recording start timestamp: `rec-{epochMs}.ogg`.

## Coordinate System

All Automerge-stored coordinates are in **line-height units** (device-independent):

```
normalizedX = pixelX / lineSpacing
normalizedY = (pixelY - topMargin) / lineSpacing
```

Line spacing varies by device:
- Large screens (≥900dp smallest width): 50dp × density
- Small screens (<900dp): 41dp × density

On unpack, coordinates are denormalized to the current device's line spacing. A stroke drawn on a tablet with 94px line spacing renders correctly on a phone with 50px line spacing.

## Save Architecture

```
Pen up → saveNow() [200ms debounce]
  → AutoSaver.saveAsync(snapshot)           ← IO thread
  → AutomergeSink.save(name, state)
    → synchronized(lock)
    → AutomergeSync.sync(state)             ← diff by ID, apply deltas
    → AutomergeStorage.saveIncremental()    ← append delta to .automerge.inc

10s idle → createIdleCheckpoint()
  → AutomergeSink.withDocument(name) { doc → versionHistory.createCheckpoint() }

Document close (onStop):
  → flush pending save + checkpoint
  → snapshotAndSaveBlocking()               ← final .automerge write
  → DocumentStorage.save()                  ← .mok with local-only state
  → autoSaver.exportIfDirty()               ← .mok to SAF if configured

Document open:
  → loadDocument(name)
    → try .automerge first (AutomergeAdapter.fromAutomerge)
    → merge local-only state from .mok (scrollOffsetY, etc.)
    → fall back to .mok with auto-migration if no .automerge exists
```

## Version History

Checkpoints are created after 10 seconds of idle following activity (not per pen-up). Each checkpoint records the Automerge document heads at that moment.

**Preview:** fork the document at checkpoint heads, viewport-scoped read (only unpack visible strokes), render to canvas. ~20ms for 62 strokes.

**Restore:** fork at heads, save as current document, reload UI.

**UI:** bottom overlay with SeekBar slider (tap left/right of thumb to step) + session-grouped list (sessions delineated by 30-minute gaps).

## Performance Counters

`PerfCounters` uses zero-allocation `LongArray` ring buffers (100 samples per metric). Metrics: `preview.fork`, `preview.read`, `preview.draw`, `save.sync`, `save.incremental`. Included in bug report JSON.
