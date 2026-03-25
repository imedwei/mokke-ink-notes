# InkUp

> A note-taking system that respects the physicality of handwriting while unlocking the composability of digital.

Every note-taking app forces a choice: capture naturally with a stylus, or capture in a format that's useful later. Handwriting apps produce dead-end image files. Structured apps demand you type and organize in real time, breaking the flow of thought.

InkUp bridges the gap. Write and draw freely on an e-ink tablet. InkUp recognizes your handwriting, classifies your diagrams, and produces clean, structured text — without modes, without menus, without interrupting your thinking.

![InkUp showing handwriting recognition and diagram areas](docs/screenshot.png)

## Features

### Capture

- **Live handwriting recognition** — write naturally and see formatted text appear as you scroll
- **Two instruments** — stylus always writes, finger always navigates. No mode switching.
- **E-ink optimized** — designed for Boox devices with low-latency Onyx Pen SDK integration

### Diagrams

- **Auto-detect diagrams** — draw a shape and InkUp automatically creates a diagram area around it. No gesture or mode switch required.
- **Shape snap** — hold at the end of a stroke to snap to rectangles, ellipses, triangles, diamonds, arrows, elbows, and arcs
- **Magnetic connectors** — arrow endpoints snap to nearby shapes for clean flowcharts
- **Diagram text recognition** — freehand labels inside diagram areas are recognized and displayed

### Editing

- **Scratch-out to erase** — scribble over any content to remove it
- **Strikethrough to delete** — draw a horizontal line through text to delete the line
- **Insert/remove space** — tap the gutter button, then drag to push content apart or close gaps
- **Undo/redo** — gutter buttons with smart coalescing (rapid strokes group into one undo step)

### Organization

- **Multi-document** — create, open, and rename documents from the menu
- **Heading detection** — underline a line to mark it as a heading
- **Markdown export** — share notes as markdown with diagrams embedded as SVG
- **Sync folder** — export to a folder via Android Storage Access Framework

### Onboarding

- **Interactive tutorial** — 4-step guided tour: write, draw, erase, scroll. Uses Hershey single-stroke fonts for realistic demo content with progressive reveal animations.

## Building

```bash
./gradlew assembleDebug
```

### Install to a connected device

```bash
./gradlew installDebug
```

### Run tests

```bash
# Unit tests (no device required)
./gradlew testDebugUnitTest

# Connected device tests (requires Boox tablet)
./gradlew connectedDebugAndroidTest
```

## Architecture

See [docs/VISION.md](docs/VISION.md) for the product vision, interaction philosophy, and design principles.

## License

MIT
