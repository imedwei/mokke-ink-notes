# Undo/Redo UX Design & Snapshot Policy

## Context

InkUp has a working `UndoManager` with snapshot-based undo/redo (max 50 entries), but two critical problems make it unusable:

1. **No discoverability**: Undo/redo gutter tap zones are defined as constants (`GUTTER_UNDO_ZONE`, `GUTTER_REDO_ZONE`) but never wired into touch handling or rendered visually. They're dead code.
2. **Per-stroke snapshots**: Every single stroke creates a snapshot. Writing "Hello" (~15 strokes) requires 15 undo taps to revert. This defeats the purpose.

This document defines the UX and snapshot policy to guide implementation.

---

## 1. Undo/Redo Invocation

### Primary: Visible Gutter Buttons

Draw undo (curved-left arrow) and redo (curved-right arrow) icons in the text view's right gutter, stacked below the existing "I" logo icon.

| Property | Value |
|---|---|
| Position | Same gutter column as "I" logo, at `y = iconSize * 2.4` (undo) and `y = iconSize * 3.6` (redo) |
| Tap target | `iconSize` (56dp) square — same as logo |
| Enabled state | Black icon when `canUndo()`/`canRedo()` is true |
| Disabled state | Light gray (`CanvasTheme.LINE_COLOR`) |
| Visibility | Auto-hide with logo during active pen input (`onPenStateChanged`) |
| Input | Both finger and stylus taps accepted |

**Why**: Satisfies VISION.md Principle 2 (discoverable essentials) without consuming canvas space. The gutter already has the "I" icon — undo/redo are natural extensions of the same pattern.

### Secondary: Scrub Gesture (future)

Vertical drag starting from the undo icon enters scrub mode. Drag up = undo further, drag down = redo. The `UndoManager` scrub API (`beginScrub`/`scrubTo`/`endScrub`) already supports this. Show a counter overlay ("-3" / "+2") during drag. This is an accelerator for power users — implement after the primary buttons are validated.

### Rejected Alternatives

| Method | Why rejected |
|---|---|
| Two-finger canvas gesture | `TouchFilter` rejects multi-touch; conflicts with Principle 4 (convenience vs creation) |
| Shake to undo | Awkward with 10"+ tablet on desk |
| L-shaped stylus gesture | Stroke consumption anti-pattern per VISION.md |
| Hardware buttons | Device-specific, not universal |

---

## 2. Snapshot Creation Policy

### Core Principle

**One undo step = one user intent.** A snapshot boundary should correspond to a meaningful unit of work from the user's perspective, not a mechanical event.

### Action Types & Coalescing Rules

| Action Type | Snapshot Rule | Rationale |
|---|---|---|
| **Writing strokes** (`STROKE_ADDED`) | **Coalesce**: skip snapshot if <2s since last `STROKE_ADDED` AND same or adjacent line | Writing is rapid and sequential. "Hello World" should undo as one unit. |
| **Erase** (`SCRATCH_OUT`) | **Always snapshot** | Destructive — user must be able to undo each erase independently. |
| **Shape snap** (`STROKE_REPLACED`) | **Always snapshot** | User expects "undo the snap" as a distinct operation. Preserves existing two-phase behavior (raw stroke snapshot, then snap snapshot). |
| **Gesture mutation** (`GESTURE_CONSUMED`) | **Always snapshot** | Destructive (strikethrough, scribble-delete). |
| **Diagram area created** (`DIAGRAM_CREATED`) | **Always snapshot** | Structural document change. |
| **Sticky zone expanded** (`ZONE_EXPANDED`) | **Always snapshot** | Structural change. |

### Coalescing Algorithm

```kotlin
fun shouldCreateSnapshot(action: ActionType, lineIndex: Int): Boolean {
    if (action != STROKE_ADDED) return true          // destructive/structural = always
    if (lastAction != STROKE_ADDED) return true       // action type changed
    if (now - lastSnapshotTime > 2000ms) return true  // pause detected
    if (abs(lineIndex - lastLineIndex) > 1) return true  // jumped to distant line
    return false                                       // coalesce with previous
}
```

### Coalescing Window: 2 seconds

Why 2s: matches the existing idle timeout for text recognition. A 2-second pause in handwriting indicates the user has completed a thought unit. Shorter windows (500ms) would still create too many snapshots during normal letter spacing. Longer windows (5s) would coalesce separate words/phrases that the user might want to undo independently.

### What This Means in Practice

| User Action | Snapshots Created | Undo Steps |
|---|---|---|
| Write "Hello World" (40 strokes, 5s, 1 pause) | 2-3 | 2-3 taps to erase it all |
| Write a word, erase it, write another | 3 (word, erase, word) | Each independently undoable |
| Draw a rectangle (snapped) | 2 (raw stroke, snap) | First undo restores raw freehand, second undo removes stroke |
| Write 3 lines continuously (no long pause) | ~3-5 (one per ~2s pause) | Each phrase independently undoable |

---

## 3. Visual Feedback

### On State Change
- Undo/redo icons update enabled/disabled color after every `undo()`, `redo()`, and `saveSnapshot()` call.
- Only the small icon region needs partial e-ink refresh — minimal cost.

### On Undo/Redo Action
- **Phase 1**: No special feedback beyond the canvas redraw (e-ink partial refresh naturally shows the change). The icon state change confirms the action succeeded.
- **Phase 2 (future)**: Brief invert-flash of the bounding box of changed strokes using `REGAL_D` waveform. Draws the eye to where the change occurred.

### During Scrub (future)
- Counter overlay near drag point: "Undo 3" or "Redo 1".
- Canvas updates at each scrub step (may need throttling on e-ink).

---

## 4. Edge Cases

### Undo after text recognition
Already handled: `applySnapshot()` restores `lineTextCache` from the snapshot, so recognized text reverts correctly. No change needed.

### Undo diagram creation
Already handled: `diagramAreas` is part of the snapshot. Undoing restores the previous list without the new area.

### Undo during active pen-down
**Block undo/redo while pen is active.** The icons are already hidden during pen input (`onPenStateChanged`). Touch handler should additionally check pen state and reject undo/redo taps. Applying a snapshot mid-stroke would corrupt the Onyx raw drawing layer's state.

### Document switch
`reset()` should call `undoManager.clear()`. Undo history from document A must not carry over to document B. (This is currently a bug — `reset()` doesn't clear the undo manager.)

### Stack overflow (50 max)
With coalescing, 50 snapshots cover ~10-15 minutes of continuous writing instead of ~1-2 minutes. Keep `maxHistory = 50` for now.

### App restart
Do not persist undo history across sessions. The mental model of "undo within this session" is clear and matches user expectations. Version persistence (VISION.md Layer 2) is the future solution for cross-session history.

### Shape snap + coalescing interaction
If strokes S1, S2 coalesce, then S3 is shape-snapped: undo first restores S3-raw (the snap is always a separate snapshot), then undoing again removes S1+S2+S3 together. This is acceptable — "undo snap, then undo the writing burst."

---

## 5. Implementation Components

### New: `UndoCoalescer`
- Sits between mutation call sites and `UndoManager.saveSnapshot()`
- Tracks `lastActionType`, `lastSnapshotTime`, `lastLineIndex`
- Exposes `maybeSave(actionType, lineIndex)` — delegates to `UndoManager` when a new snapshot is warranted
- Replaces direct `saveUndoSnapshot()` calls in `WritingCoordinator`

### Modified: `RecognizedTextView`
- Draw undo/redo icons in `onDraw()` gutter area
- Add `isInUndoArea(x, y)` / `isInRedoArea(x, y)` checks in `handleFingerTouch()` and stylus `onTouchEvent()`
- Accept `canUndo`/`canRedo` state for icon rendering

### Modified: `WritingCoordinator`
- Replace `saveUndoSnapshot()` with `undoCoalescer.maybeSave(actionType, lineIndex)`
- Notify text view of undo/redo availability after state changes
- Clear undo history in `reset()`

### Modified: `UndoManager`
- No structural changes needed — already has the right API
- May add state-change listener for UI updates

### Key files
- `app/src/main/java/com/writer/ui/writing/UndoManager.kt`
- `app/src/main/java/com/writer/ui/writing/WritingCoordinator.kt`
- `app/src/main/java/com/writer/view/RecognizedTextView.kt`
- New: `app/src/main/java/com/writer/ui/writing/UndoCoalescer.kt`
- Tests: `app/src/test/java/com/writer/ui/writing/UndoCoalescerTest.kt`

### Implementation Order

1. **UndoCoalescer** — new class with tests, defines action types and coalescing logic
2. **Gutter buttons** — render icons in RecognizedTextView, wire tap detection
3. **Wire coalescer** — replace `saveUndoSnapshot()` calls with `undoCoalescer.maybeSave()`
4. **Bug fixes** — clear undo on document switch, block undo during pen-down
5. **Scrub gesture** (future) — wire vertical drag to existing scrub API
6. **Visual polish** (future) — flash-highlight changed region on undo/redo
