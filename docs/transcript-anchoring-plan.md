# Transcript Anchoring Plan

## Context

The transcript column owns all `AudioRecording`s and `TextBlock`s. Each
`TextBlock` carries an anchor — `(anchor_target ∈ {MAIN, CUE},
anchor_line_index, anchor_mode ∈ {AUTO, MANUAL})` — that points at the row
in the writing column whose strokes were laid down while that audio was
captured. The anchor is the spatial bridge that lets main and cue render
audio strips in their gutter without owning audio data themselves.

**State today:**

- `TextBlock.anchorLineIndex / anchorTarget / anchorMode` exist in
  `app/src/main/java/com/writer/model/TextBlock.kt` and round-trip through
  proto + Automerge.
- `TextBlockAnchorComputer` (pure JVM) computes
  `(target, lineIndex)` from audio↔stroke time correlation —
  `app/src/main/java/com/writer/ui/writing/TextBlockAnchorComputer.kt`,
  17 unit tests in `TextBlockAnchorComputerTest`.
- Nothing yet calls `computeAnchor`. Every TextBlock in production is still
  written with the default anchor (`-1, MAIN, AUTO`).
- No manual-drag handle, no audio strip in main/cue gutters.

This plan covers the work to wire the computer into the live app and add the
manual-drag affordance. The downstream Phase 5 (audio strip + popup in
main/cue gutters) is intentionally out of scope here — it ships as a
separate plan once anchors are populated.

## Decisions (locked in)

- **AUTO recompute is bounded.** Recompute only on TextBlock create, on
  document load, and on explicit "Re-auto-anchor". Stroke edits never
  trigger recompute (avoids surprising drift while the user is writing).
- **MANUAL is sticky.** A user-dragged anchor never recomputes, even on
  reload. Long-press handle → "Re-auto-anchor" reverts to AUTO and triggers
  one recomputation.
- **Fallback is `(MAIN, highestLineIndex + 1)`.** When the computer returns
  null (no overlapping strokes in either column), the caller places the
  block at the bottom of main — same UX as today's "after highest content"
  behavior. The fallback lives at the call site, not inside the computer,
  so the computer stays a pure function of its inputs.
- **Tie-break prefers MAIN.** Equal main/cue hit count → `target = MAIN`.
  Locked into the computer; tested.
- **Median is upper-middle.** `lines[lines.size / 2]` for even counts.
  Locked; tested.
- **Strokes match the window by `startTime`.** A stroke is "in window" iff
  its `startTime ∈ [recordingStart + audioStartMs, recordingStart +
  audioEndMs]`. Endpoints inclusive. Wall-clock conversion handled inside
  the computer.

## Slices

Each slice is one TDD cycle: write tests red → minimum implementation
green → `./gradlew testDebugUnitTest` → commit. Slices are ordered so the
visible behavior unblocks earliest.

### Slice 1 — Caller integration + fallback *(unblocks visible behavior)*

Make every TextBlock created at the end of recording carry a real anchor.

**Tests** (`app/src/test/java/com/writer/ui/writing/`):

- `TextBlockAnchorComputerFallbackTest` — JVM
  - `nullAnchor_callerFallsBackToMainAfterHighest`
  - `recordingStartedBeforeAnyStrokes_returnsNull` *(documents the
    null path the caller must handle)*
- `WritingActivityAnchorWiringTest` — Robolectric
  - `recordingStop_computesAnchor_storesOnTextBlock`
  - `recordingStop_noOverlappingStrokes_fallsBackToMainBottom`
  - `recordingStop_anchorModeIsAuto`

**Implementation:**

- Wire `TextBlockAnchorComputer` into the recording-stop path in
  `WritingActivity.startSherpaLectureRecognition` (or whichever call
  site invokes `transcriptCoordinator.insertTextBlock`).
- On computer-null: write `(MAIN, mainSegmenter.getBottomOccupiedLine + 1,
  AUTO)`.
- All new TextBlocks land with `anchor_mode = AUTO` and a populated
  `anchor_line_index`.

**Files:** `WritingActivity.kt`, plus the two new test files.

### Slice 2 — Anchor recompute on load (legacy + AUTO blocks)

Documents created before this plan have `anchor_line_index = -1`. On load,
fill in anchors for all AUTO blocks; leave MANUAL blocks alone.

**Tests:**

- `AnchorRecomputeRulesTest` — JVM
  - `autoAnchor_recomputesOnLoad_whenLineIndexIsMinusOne`
  - `autoAnchor_recomputesOnLoad_evenWhenLineIndexAlreadySet`
  - `manualAnchor_neverRecomputes_onLoad`
  - `autoAnchor_doesNotRecomputeOnStrokeCommit`
  - `autoAnchor_doesNotRecomputeOnStrokeErase`
  - `reAutoAnchor_changesModeToAuto_andRecomputes`
- `AnchorPersistenceTest` — JVM *(thin coverage on top of existing proto
  round-trip)*
  - `anchorLineIndex_negativeOne_decodes_asNull`
  - `manualAnchor_roundTrips_unchanged`

**Implementation:**

- New `AnchorRecomputer` (or method on the coordinator) that walks
  `transcript.textBlocks`, computes anchors for any AUTO block, and writes
  the result back. Called from the document-load path.
- Manual recompute trigger plumbed in for the future "Re-auto-anchor" menu
  item (Slice 4 wires it to UI).

**Files:** new helper in `ui/writing/`, hook in `WritingActivity` /
`WritingCoordinator` load path.

### Slice 3 — Anchor handle render in transcript gutter

Visual affordance for the anchor — a small handle in the transcript
column's left margin at each TextBlock's anchor row. No drag yet, just
render and persistence-driven position.

**Tests:**

- `AnchorHandleRenderTest` — Robolectric
  - `handleRendersInGutter_atAnchorLineIndex`
  - `handleScrollsWithCanvas`
  - `multipleBlocks_eachGetTheirOwnHandle`
  - `manualAnchorHandle_visuallyDistinct_fromAuto` *(small visual cue
    so the user knows which anchors are sticky)*

**Implementation:**

- Render path inside the transcript-instance `HandwritingCanvasView`'s
  gutter pass (or a sibling view; tradeoff TBD when the slice starts).

**Files:** `view/HandwritingCanvasView.kt` or new
`view/AnchorHandleRenderer.kt`.

### Slice 4 — Manual drag + Re-auto-anchor menu

Cross-gutter drag changes `anchor_target`; vertical drag changes
`anchor_line_index`; drop commits and sets `anchor_mode = MANUAL`.
Long-press opens a small menu with "Re-auto-anchor".

**Tests:**

- `AnchorHandleGestureHandlerTest` — Robolectric
  - `verticalDrag_changesAnchorLineIndex`
  - `verticalDrag_clampedToValidLineRange`
  - `horizontalDrag_acrossGutter_changesAnchorTarget_toCue`
  - `horizontalDrag_backAcrossGutter_changesAnchorTarget_toMain`
  - `dropCommits_setsAnchorModeManual`
  - `cancelDrag_revertsToOriginalAnchor`
  - `longPress_opensReAutoAnchorMenu`
  - `dragGhostPreview_followsFinger`
  - `dragHandle_inLeftGutter_doesNotInterceptStrokes`
- `AnchorHandleCrossGutterDragTest` — Robolectric
  - `dragFromTranscript_toMain_then_toCue_inOneMotion_landsAtCue`
  - `dragOutsideAnyColumn_returnsToOriginalAnchor`

**Implementation:**

- New `view/AnchorHandleGestureHandler.kt` — mirrors the existing
  `GutterTouchGuard` / `StrokeRouter` patterns for finger-only gestures
  in the gutter.
- Re-auto trigger calls into Slice 2's recomputer for the single block.

**Files:** `view/AnchorHandleGestureHandler.kt` *(new)*,
`ui/writing/WritingActivity.kt` *(wire menu action)*.

## Out of Scope

- Audio strip render in main/cue gutters (Phase 5 of the upstream design).
- TextBlock popup on strip tap (Phase 5).
- Per-word interactions and tap-to-pick alternatives (Phase 6).

These are tracked separately and depend on this plan's slices landing
first — strips and popups need anchors to be populated and movable.

## Verification

After every slice:

1. `./gradlew testDebugUnitTest` — full JVM suite green.

After Slice 1 lands:

2. `./gradlew installDebug` on Palma 2 Pro.
3. Record audio while writing in main → stop → confirm new TextBlock has
   `anchor_target = MAIN`, `anchor_line_index ≈` median main row in the
   recording window. Inspect via bug report (`adb shell "am broadcast …
   GENERATE_BUG_REPORT && content read --uri … bugreport"`).
4. Record audio while writing only in cue → confirm
   `anchor_target = CUE`.
5. Record audio with no strokes → confirm fallback to
   `(MAIN, highestLineIndex + 1)`.

After Slice 2 lands:

6. Open a pre-anchoring `.inkup` file → confirm AUTO blocks gain populated
   `anchor_line_index` on load; save → reload → values stable.

After Slice 4 lands:

7. Drag a handle vertically → strip y-position changes (once Phase 5 is
   in; until then, verify via bug-report dump).
8. Drag a handle horizontally across gutters → `anchor_target` flips.
9. Long-press → "Re-auto-anchor" → mode flips back to AUTO and
   line-index recomputes.

## Files Touched (cumulative)

| Slice | New | Modified |
|-------|-----|----------|
| 1 | `WritingActivityAnchorWiringTest.kt`, `TextBlockAnchorComputerFallbackTest.kt` | `WritingActivity.kt` |
| 2 | `AnchorRecomputeRulesTest.kt`, `AnchorPersistenceTest.kt`, possibly `AnchorRecomputer.kt` | `WritingActivity.kt` or `WritingCoordinator.kt` |
| 3 | `AnchorHandleRenderTest.kt`, possibly `AnchorHandleRenderer.kt` | `HandwritingCanvasView.kt` |
| 4 | `AnchorHandleGestureHandlerTest.kt`, `AnchorHandleCrossGutterDragTest.kt`, `AnchorHandleGestureHandler.kt` | `WritingActivity.kt`, `HandwritingCanvasView.kt` |
