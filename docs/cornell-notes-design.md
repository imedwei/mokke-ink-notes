# Cornell Notes — Two-Column Design

## Context

The Palma 2 Pro in landscape orientation provides roughly double the width of portrait mode (1648 × 824 px, or ~879 × 439 dp). In portrait the full width is used for note-taking; in landscape, half the screen sits unused. Cornell Notes is a well-established note-taking method that maps naturally to a two-column layout: main notes on the left, cue/summary annotations on the right.

This feature turns the landscape real estate into a structurally meaningful capture+composition format, aligned with VISION.md's core thesis: bridging fast capture and structured-enough-to-use-later.

---

## Vision alignment

| VISION.md concept | How Cornell Notes serves it |
|---|---|
| Semantic classification | Cues are a distinct semantic type — post-capture annotations with spatial references to main content regions |
| Spatial fidelity | Cue-to-content positional anchoring preserves the "where" relationship |
| Version persistence | Cues are a composition-phase layer, not a destructive overwrite of main content |
| Surface independence | The fold/unfold model adapts the same data to portrait (single column) and landscape (two columns) |
| Learner journey | Capture → add cues → export. Cues are study aids that travel with the note |
| Principle #2 (discoverable) | Cue indicator strip is always visible; tap to fold/unfold |
| Principle #3 (minimize cognitive state) | No mode switch — stylus writes in whichever column has focus. Orientation is the mode toggle, and it's physical |
| Principle #4 (don't cannibalize creation) | No gestures consumed — column switch is tap or rotation |

---

## States and layout

The feature has three visual states driven by device orientation and user action.

### State table

| State | Left region | Main area | Right region |
|---|---|---|---|
| **Portrait: Notes** (default) | — | Full-width main content (handwriting canvas + recognized text above) | Cue indicator strip (~16 dp) |
| **Portrait: Cues** (folded) | Context rail (~24 dp): scaled-down main content minimap | Full-width cue column (handwriting canvas + recognized cue text above) | — |
| **Landscape** (unfolded) | Main content (50%) | Divider (~4 dp) | Cue column (50%) |

### Orientation transitions

```
Portrait (Notes) ──rotate──▶ Landscape (both columns visible)
                                    │
Portrait (Cues)  ──rotate──▶ Landscape (both columns visible)
                                    │
Landscape        ──rotate──▶ Portrait (Notes — always returns to main content)
```

When rotating from landscape to portrait, the app always returns to the Notes view. The cue column is a composition-phase tool — the default portrait state should be capture-ready.

---

## Portrait: Notes view (default)

The current portrait layout is unchanged except for the addition of a **cue indicator strip** on the right edge.

### Cue indicator strip

| Property | Value |
|---|---|
| Width (visual) | 16 dp (~30 px on Palma) |
| Width (touch target) | 40 dp (~75 px on Palma) — touch-receptive area extends inward from the screen edge |
| Position | Right edge of screen, full height of canvas area |
| Content | Small dots/marks at vertical positions where cues exist |
| Color | Subtle gray, same as `CanvasTheme.LINE_COLOR` |
| Interaction | Tap anywhere on the strip → fold to Cues view |
| Scroll | Scrolls in lockstep with the main canvas |

The strip is a passive indicator during capture — it doesn't compete for attention. Its primary purpose is discoverability: the user sees marks appear as they add cues in landscape mode, and tapping the strip reveals them in portrait.

If no cues exist yet, the strip shows a faint column-line affordance indicating the cue region is available.

### First-use hint

On the first landscape rotation for a new user, a one-time contextual hint appears over the cue column: "Write cues here to annotate your notes." Dismissed on tap or after first stylus stroke in the cue column. Never shown again. This satisfies Principle #2 (discoverable essentials) without becoming a persistent tutorial.

---

## Portrait: Cues view (folded)

Tapping the cue indicator strip (or a toolbar button) folds the view: the main content slides left and the cue column takes over the main area.

### Context rail (left gutter)

| Property | Value |
|---|---|
| Width | 24 dp (~45 px on Palma) |
| Position | Left edge of screen, full height of canvas area |
| Content | Scaled-down rendering of main content — recognized text and diagram strokes at ~30-40% scale |
| Purpose | Shape/texture recognition aid, not readable text. The visual rhythm (dense paragraph, bullet list, diagram) orients the user within the document |
| Scroll | Locked to cue column scroll position (content-anchored, not pixel-anchored) |
| Interaction | Tap anywhere on the rail → unfold back to Notes view, preserving current scroll position so the user sees the main content corresponding to the cues they were just viewing |
| Rendering | Grayscale, reduced contrast to avoid competing with cue content |

The context rail is a minimap — recognizable patterns, not readable content. At 300 PPI on the Palma, even 30% text would be ~3-4pt (illegible). The value is in distinguishing content types and density by visual texture.

### Cue column (main area)

When folded to cues, the cue column occupies the remaining width (screen width minus context rail). It behaves identically to the main content area:

- Same `HandwritingCanvasView` rendering pipeline
- Same line spacing and grid system
- Same `RecognizedTextView` panel above (showing recognized cue text)
- Stylus writes cues; finger scrolls

The cue column uses the same `lineSegmenter` grid as the main content, so cue lines correspond 1:1 to main content lines. This is what enables spatial anchoring.

---

## Landscape: Unfolded view

In landscape, both columns are visible side by side with a 50/50 split.

### Layout

```
┌──────────────────────┬────┬──────────────────────┐
│   Recognized text    │    │   Recognized cue text │
│   (main content)     │    │   (cue content)       │
├──────────────────────┤ D  ├──────────────────────┤
│                      │ I  │                       │
│   Main canvas        │ V  │   Cue canvas          │
│   (handwriting)      │ I  │   (handwriting)       │
│                      │ D  │                       │
│                      │ E  │                       │
│                      │ R  │                       │
└──────────────────────┴────┴──────────────────────┘
```

Each column has its own `RecognizedTextView` panel above its canvas. The existing vertical `SplitLayout` divider (between text and canvas) applies independently to each column — both columns share the same text/canvas height ratio but each renders its own recognized text.

| Property | Value |
|---|---|
| Main content width | 50% of screen width minus half divider |
| Cue column width | 50% of screen width minus half divider |
| Divider | 4 dp visual, 24 dp touch target (same pattern as existing `SplitLayout` vertical divider) |
| Divider draggable | No — fixed 50/50 split. The Palma's landscape width doesn't have room for meaningful adjustment |

### Split rationale

The Palma 2 Pro in landscape is 1648 × 824 px (~879 × 439 dp). A 50/50 split gives each column ~824 px width — the same as the full portrait width. Each column is essentially a portrait-width writing surface. This makes the landscape experience feel like **two portrait pages side by side**, reinforcing the fold/unfold metaphor.

On larger devices (Go 7, Note Air 5C, Tab X C), the split ratio could shift to 60/40 or 65/35 favoring main content, since those screens have more total width. This is a future consideration — the Palma's 50/50 is the design target.

### Linked scrolling

Both columns share a vertical scroll position anchored to the main content's line index system:

- Scrolling the main content column → cue column scrolls to show cues for the visible content region
- Scrolling the cue column → main content scrolls to show the content that the visible cues refer to
- Scroll anchoring is **line-based**: the top visible line index in one column determines the top visible line index in the other

This uses the existing `lineSegmenter` infrastructure. Both columns share the same line index space — line 5 in the main content corresponds to line 5 in the cue column.

---

## Shared line index model

The key architectural insight: cues are not a separate document. They are a **parallel layer** on the same line grid.

```
Line index    Main content strokes    Cue strokes
─────────     ─────────────────────   ────────────────
0             "Introduction to..."    "KEY CONCEPT"
1             "The three types..."
2             [diagram: Venn]         "compare A vs B"
3             [diagram: Venn]
4             "In summary..."         "EXAM TOPIC"
5             ...                     ...
```

Each `InkStroke` already stores absolute Y coordinates. Cue strokes live in the cue `ColumnModel` and share the same Y-coordinate space as main content strokes — no per-stroke column property needed.

This means:
- Line recognition works identically for both columns
- Scroll position maps directly between columns via line index
- Diagram areas can exist in either column
- Undo/redo operates on whichever column the stroke was drawn in

---

## Data model

### Naming: `ColumnModel` / `ColumnData`

The existing `DocumentModel` and `DocumentData` are renamed to reflect a two-level hierarchy:

| Class | Role |
|---|---|
| `DocumentModel` | The full document — holds two columns and shared state |
| `ColumnModel` | Runtime state for one column (strokes, diagram areas) — extracted from what was previously `DocumentModel` |
| `DocumentData` | Serializable snapshot of the full document — holds two `ColumnData` instances and shared fields |
| `ColumnData` | Serializable snapshot for one column (strokes, lineTextCache, diagramAreas) — extracted from what was previously `DocumentData` |

### Structure

```kotlin
// --- Runtime ---

class DocumentModel {
    val main: ColumnModel = ColumnModel()
    val cue: ColumnModel = ColumnModel()
    var scrollOffsetY: Float = 0f
    // ... other shared state (language, etc.)
}

class ColumnModel {
    val activeStrokes: MutableList<InkStroke> = mutableListOf()
    val diagramAreas: MutableList<DiagramArea> = mutableListOf()
    val diagram: DiagramModel = DiagramModel()
}

// --- Persistence ---

data class DocumentData(
    val main: ColumnData,
    val cue: ColumnData = ColumnData(),      // default empty for existing docs
    val scrollOffsetY: Float = 0f,
    val highestLineIndex: Int = 0,
    val currentLineIndex: Int = 0,
    val userRenamed: Boolean = false
)

data class ColumnData(
    val strokes: List<InkStroke> = emptyList(),
    val lineTextCache: Map<Int, String> = emptyMap(),
    val everHiddenLines: Set<Int> = emptySet(),
    val diagramAreas: List<DiagramArea> = emptyList()
)
```

Existing documents deserialize with an empty `cue` column (all defaults). No migration needed.

### What's shared (coordinator responsibilities)

These concerns span both columns and are managed by the coordinator, not the column models:

| Shared concern | Coordination strategy |
|---|---|
| Scroll position | Single `scrollOffsetY` on `DocumentModel`. Scrolling either column updates the shared offset |
| Line grid | Both columns use the same `lineSegmenter` — same line spacing, same top margin, same Y-coordinate space |
| Line space insertion/removal | Coordinator applies the same shift to both columns' strokes when space is inserted/removed in either column |
| Save/load | `DocumentStorage` serializes `DocumentData` with both `ColumnData` instances. Backward-compatible: legacy JSON (no `main`/`cue` wrapper) loads into `main` column |

### What's independent (per-column)

Each `ColumnModel` independently manages:
- Stroke collection and bounds
- Text recognition and `lineTextCache`
- Diagram areas and classification
- Undo/redo history

This separation means the cue column gets full diagram support, recognition, and undo for free — no feature parity work needed.

---

## Interaction model

### Stylus behavior

The stylus always writes — no mode switch (Principle #3). Which column receives the stroke depends on where the stylus touches:

| State | Stylus on main area | Stylus on cue area |
|---|---|---|
| Portrait: Notes | Writes main content | N/A (indicator strip only) |
| Portrait: Cues | N/A (context rail only) | Writes cue content |
| Landscape | Writes main content | Writes cue content |

In landscape, the divider acts as the boundary. Strokes do not cross the divider — a stroke that starts in the main column stays in the main column even if the stylus drifts across. When a stroke reaches the divider boundary, it is visually clipped at the divider edge, providing clear feedback that the stroke is confined to its starting column.

### Line space insertion/removal

The space insert tool operates on the shared line grid. Inserting or removing space in either column affects both columns — lines shift in lockstep. This preserves the 1:1 line correspondence that linked scrolling depends on.

**Cue block anchoring:** When space is inserted at a line that falls within a contiguous cue block (consecutive lines with cue strokes), the space is inserted before or after the cue block rather than splitting it. The cue block stays anchored at its original starting line. This prevents the disorienting experience of a cue annotation being torn apart by a space insertion in the other column.

For example: if cue strokes span lines 5-7 and the user inserts space at line 6 in the main content, the space opens after line 7 (below the cue block) rather than between lines 5-6 and 7 of the cue. The main content shifts as expected; the cue block remains intact.

This is a direct consequence of the shared line index model. The columns are two views of the same vertical grid, not independent documents.

### Finger behavior

The finger navigates — no mode switch (Principle #3):

| Action | Effect |
|---|---|
| Vertical swipe on either column | Scrolls both columns (linked) |
| Tap cue indicator strip (portrait notes) | Fold to cues view |
| Tap context rail (portrait cues) | Unfold to notes view |
| Tap divider area (landscape) | No effect — divider is not interactive beyond being a visual boundary |

### Gutter overlay

The existing gutter overlay (menu, undo, redo, space insert) appears in whichever column the user last wrote in. In landscape, this means the gutter floats over the active column. In portrait, it behaves as today.

---

## Rendering

### Context rail rendering

The context rail in the Portrait: Cues state renders a minimap of the main content:

1. Take the recognized text and diagram strokes for the visible line range
2. Render into an off-screen bitmap at ~30% scale
3. Draw the bitmap into the 24 dp rail, clipped to bounds
4. Apply reduced contrast (e.g., alpha 0.4) so it reads as background texture

The minimap re-renders when:
- The scroll position changes (new lines become visible)
- Main content changes (new strokes or recognition results)

Performance: the minimap is a simple bitmap blit — no real-time stroke rendering. It can be cached and only re-rendered on content changes.

### Cue indicator strip rendering

The indicator strip in the Portrait: Notes state:

1. For each line index that has cue strokes, draw a small dot at the corresponding Y position
2. Dots are 3 dp diameter, centered horizontally in the strip
3. Dot color matches `CanvasTheme.LINE_COLOR`

The strip re-renders when the scroll position changes or cue strokes are added/removed.

---

## E-ink considerations

E-ink partial refresh is critical for writing latency. The two-column layout must not introduce additional refresh overhead:

- **During active writing**: Only the active column's region is refreshed. The other column and the divider/strip/rail are static
- **On scroll**: Both columns refresh, but this is already a full-region operation
- **On fold/unfold**: Full screen refresh (acceptable — this is a deliberate view change, not a mid-writing operation)
- **Context rail updates**: Deferred — the minimap updates after a debounce period, not during active writing

The existing `pause()`/`drawToSurface()`/`resume()` pattern for forcing e-ink refresh applies to each column independently.

---

## Screen metrics additions

New constants in `ScreenMetrics`:

| Property | Standard | Compact (Palma 2 Pro) | Rationale |
|---|---|---|---|
| Cue indicator strip width | 16 dp | 16 dp | Minimal — just enough for dot indicators |
| Context rail width | 24 dp | 24 dp | Enough for minimap texture, not readable text |
| Column divider visual width | 4 dp | 4 dp | Visible separator in landscape |
| Column divider touch target | 24 dp | 24 dp | Consistent with existing split divider |

---

## Implementation phases

### Phase 1: Data model refactor + landscape split
- Extract `ColumnModel` / `ColumnData` from existing `DocumentModel` / `DocumentData`
- `DocumentModel` composes two `ColumnModel` instances (main + cue) plus shared state
- Backward-compatible deserialization: legacy JSON loads into `main` column
- Create horizontal `ColumnSplitLayout` for landscape orientation
- Route strokes to correct column based on touch X position
- Implement linked scrolling between columns
- Recognition pipeline processes cue strokes identically to main strokes

### Phase 2: Portrait fold/unfold
- Add cue indicator strip to portrait layout
- Implement fold animation (main → cues transition)
- Render context rail minimap
- Always return to Notes view on rotation to portrait

### Phase 3: Polish
- Smooth rotation transitions (unfold animation from portrait to landscape)
- Context rail content rendering and caching
- Gutter overlay positioning per active column
- Undo/redo scoping per column

---

## Markdown export

The existing `getMarkdownText()` groups main content lines into paragraphs (headings, lists, body text) and embeds diagrams as base64 SVG. Cornell Notes extends this by appending cue blockquotes after each paragraph group.

### Rule

For each main content paragraph (a group of consecutive lines), collect all cue text from the corresponding line indices. If any cues exist, append them as a single blockquote after the paragraph. If no cues exist for that group, nothing is appended.

### Example output

```markdown
## Introduction to Category Theory

The three types of morphisms are isomorphisms,
epimorphisms, and monomorphisms. Each preserves
different structural properties.

> **Cue:**
> KEY CONCEPT — three morphism types
> compare A vs B

![diagram](data:image/svg+xml;base64,...)

> **Cue:** compare A vs B

In summary, functors preserve composition
and identity. Natural transformations relate
functors to each other.

> **Cue:** EXAM TOPIC — functors & natural transformations
```

### Grouping behavior

- Paragraph boundaries are determined by the existing paragraph detection logic (blank lines, heading/list transitions)
- All cue lines within a paragraph's line range are merged into one blockquote
- Single-cue paragraphs produce a single-line blockquote: `> **Cue:** KEY CONCEPT`
- Multi-cue paragraphs produce a multi-line blockquote with each cue on its own line
- Paragraphs with no cues have no blockquote appended
- Diagram SVG embeds appear at their existing positions; cues adjacent to diagrams follow the same grouping rule

---

## Resolved decisions

1. **Cue line spacing**: Same as main content. This keeps the 1:1 line correspondence that enables simple linked scrolling and shared line space insertion/removal.

2. **Per-document opt-in**: Always available. No mode toggle — an empty cue column is simply a document with no cues yet. This avoids adding cognitive state (Principle #3). Existing documents display normally with an empty cue column in landscape; no migration needed since default empty collections handle this.

3. **Landscape → portrait transition**: Always returns to Notes view. The cue column is a composition-phase tool; portrait default should be capture-ready.

4. **Auto-scroll**: Applies to both columns in lockstep. (Future consideration: may remove auto-scroll entirely.)

5. **Cue indicator touch target**: 40 dp touch-receptive area (visual indicator remains 16 dp).

6. **Fold/unfold scroll preservation**: Transitioning between Notes and Cues view preserves the current scroll position, so the user sees corresponding content in whichever column they switch to.

7. **Cross-divider strokes**: Strokes are confined to their starting column and visually clipped at the divider.

8. **Line insertion near cue blocks**: Space insertion does not split a contiguous cue block; the block stays anchored and space opens before or after it.

9. **Context rail**: Design specifies 24 dp, but final width needs hardware prototype validation before committing.

## Open questions

1. **Empty cue column affordance**: When a document has no cues yet, what does the cue column show in landscape? Options: faint ruled lines (inviting writing), a centered hint text ("Add cues here"), or nothing (clean but undiscoverable).
