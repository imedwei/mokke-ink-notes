---
status: approved
author: spm@edwei.com
created: 2026-05-03
updated: 2026-05-03
---

# Stroke lifecycle: live-ink → pen up → pen-lift

A single user stroke runs through two distinct phases, each owned by a
different layer and instrumented with a different metric system:

```
[ Live-ink phase (during stroke) ]  →  pen up  →  [ Pen-lift phase (after pen up) ]  →  Idle
       inksdk metrics                                  mokke metrics
       pen.* · event.* · paint.*                       ink.pen_lift.*  ·  drain
```

Together they cover everything the user perceives as "the system reacting
to my pen". Knowing where each metric fires — and where each one *doesn't*
— is the difference between optimising the right thing and optimising a
ghost.

The visual companion to this doc is
[`docs/diagrams/pen-lift-timeline.svg`](../diagrams/pen-lift-timeline.svg),
which draws every metric bracket on a single time axis. This doc is the
prose walkthrough.

## Why two phases

Live-ink is **vendor-driven**. The pen-down → ink-on-screen path goes
through firmware, kernel input, the vendor's pen daemon (Bigme `xrz` or
Onyx `TouchHelper`), and finally back to the JVM. inksdk wraps that path
behind one `InkController` interface and instruments every visible
boundary with its `pen.* / event.* / paint.*` metric family.

Pen-lift is **host-driven**. Once the pen leaves the glass, the host app
runs its own post-stroke pipeline (scratch-out detection, shape snap,
classifier, observer fan-out, bitmap commit) on the main thread. The
host's mokke instrumentation covers this with `ink.pen_lift.*`.

The two phases are sequential and never overlap. inksdk's metrics finish
the moment its `InkController` delivers `onStrokeEnd` to the host; mokke's
`ink.pen_lift.*` metrics start when the host enters `endStroke`.

## Live-ink phase

The user touches the pen to the screen. The vendor pipeline reports
events; inksdk renders ink to the EPD overlay; the user perceives a line
following the pen with low latency. Throughout, inksdk records.

### Stages

Inksdk's documented timeline (see
[`third_party/inksdk/docs/metrics.md`](../../third_party/inksdk/docs/metrics.md)
and [`metrics-timeline.svg`](../../third_party/inksdk/docs/metrics-timeline.svg))
defines seven stages, of which two are unmeasurable from the JVM:

| Stage | Description | JVM-visible? |
|---|---|---|
| A. pen tip touches glass | physical contact | no |
| B. kernel writes /dev/input | input event in kernel | no (but timestamped by daemon) |
| C. JVM receives DOWN | InputProxy.invoke entered | yes |
| D. JVM receives first MOVE | first MOVE through InputProxy | yes |
| E. drawLine into ION | Canvas.drawLine on the daemon's ION buffer | yes |
| F. inValidate returns | first daemon refresh round-trip done | yes |
| G. EPD ink visible | EPD has fully painted | no |

The stretch from C onwards repeats once per binder input event for the
duration of the stroke. The headline first-paint metric covers B → F.

### Metrics

| Metric | Span | Cadence | Notes |
|---|---|---|---|
| `pen.kernel_to_paint` | B → F | once per stroke | **Headline first-paint latency** |
| `pen.kernel_to_jvm`   | B → C | once per stroke | DOWN-only kernel-to-JVM dispatch |
| `pen.jvm_to_paint`    | C → F | once per stroke | JVM-monotonic side of first-paint |
| `pen.jvm_to_first_move` | C → D | once per stroke | DOWN entry → first MOVE entry (includes user pen-movement speed) |
| `pen.move_to_paint`   | D → F | once per stroke | First MOVE entry → first inValidate; pure JVM processing |
| `event.kernel_to_jvm` | B → C | **per event** | Repeats for every binder input event |
| `event.handler`       | inside C | per event | Wall time of each `InputProxy.invoke` |
| `paint.draw_segment`  | around E | per MOVE | `Canvas.drawLine` into the ION buffer |
| `paint.invalidate_call` | around F | per draw | `inValidate(rect, mode)` round-trip |

### Decomposition

The headline metric decomposes cleanly:

```
pen.kernel_to_paint = pen.kernel_to_jvm + pen.jvm_to_paint
pen.jvm_to_paint    = pen.jvm_to_first_move + pen.move_to_paint
```

If `pen.kernel_to_paint` is high, look at the two children to see whether
the cost is in cross-clock dispatch (`pen.kernel_to_jvm`) or in JVM-side
processing (`pen.jvm_to_paint`). If the latter, `pen.jvm_to_first_move`
is "user pen-movement + dispatch", `pen.move_to_paint` is "our code".

### Per-controller coverage

| Metric | Bigme | Onyx |
|---|---|---|
| `pen.kernel_to_*` (DOWN-side) | ✅ | ✅ |
| `event.*` | ✅ | ✅ |
| `pen.*_to_paint`, `paint.*` | ✅ | ❌ |

The paint-side metrics need a JVM-visible "first paint issued" boundary.
Bigme exposes one (the host calls `inValidate` itself); Onyx hides
painting inside `TouchHelper`'s native code, so `paint.*` and the
paint-side `pen.*` metrics report `count=0` on Onyx. See
[inksdk metrics.md § Per-controller coverage](../../third_party/inksdk/docs/metrics.md#per-controller-coverage).

### Routing into the host

inksdk records into its own `PerfCounters` by default. mokke installs a
[`PerfSink`](../../third_party/inksdk/inkcontroller/src/main/java/com/inksdk/ink/PerfCounters.kt)
in
[`WriterApplication.onCreate`](../../app/src/main/java/com/writer/WriterApplication.kt)
that routes every recording into mokke's label-keyed
[`PerfCounters.recordByLabel`](../../app/src/main/java/com/writer/ui/writing/PerfCounters.kt).
After installation, `inksdk.PerfCounters.snapshot()` reports empty (host
owns the ring buffer) and bug reports / `PerfDump` see all metrics under
one unified table prefixed `ink.*`.

## Pen up

The vendor pipeline reports the kernel pen-up event. inksdk's
`OnStrokeEnd` callback fires. The host's `HandwritingCanvasView` receives
it and enters `endStroke`. From this point, inksdk's metrics are quiet —
nothing else fires for this stroke.

In our perf test, `injectStrokeForTest` is the synthetic equivalent: it
replaces the live-ink path with a direct sequence of `beginStroke` /
`addStrokePoint` / `endStroke` calls so we can measure the host pipeline
deterministically without the vendor SDK in the loop.

## Pen-lift phase

The host runs the post-stroke pipeline synchronously on the main thread.
It is **fully user-observable** — every millisecond on the main thread
between pen-up and queue-empty either delays the visible commit (sync
work) or delays the next input event (drain).

### Stages

| Stage | Code location | Description |
|---|---|---|
| A. injectStroke called | test entry / `onStrokeEnd` arrival | Pen-lift pipeline begins |
| B. endStroke entered | `HandwritingCanvasView.endStroke` | wrapper code: cancelDwell, callbacks |
| C. finishTextStroke entered | `HandwritingCanvasView.finishTextStroke` | sub-stage instrumentation begins |
| D. scratch_check returned | `checkPostStrokeScratchOut` | scribble-erase detection |
| E. shape_snap returned | `checkShapeSnap` | rectangle/ellipse/etc. snap detection |
| F. classify returned | `DiagramStrokeClassifier.classifyStroke` | drawing-vs-text heuristic |
| G. observers returned | `onStrokeCompleted?.invoke(stroke)` | host fan-out (save, recognition, UI) |
| H. endStroke returned | back up the call stack | resetStrokeState + idleRunnable post |
| I. append_bitmap returned | `appendLastStrokeToBitmap` | stroke drawn into contentBitmap |
| J. waitForIdleSync returned | message queue drained | system idle, ready for next pen-down |

### Metrics

All metrics share the `ink.pen_lift.` prefix (dropped on diagram labels
for readability).

| Metric | Span | Notes |
|---|---|---|
| `ink.pen_lift.begin` | A→ tiny start | `beginStroke` (callbacks, dwell-job start) |
| `ink.pen_lift.add_points` | up to B | full `addStrokePoint` loop |
| `ink.pen_lift.end` | B → H | `endStroke` incl. `finishTextStroke` |
| `ink.pen_lift.scratch_check` | C → D | inside end |
| `ink.pen_lift.shape_snap` | D → E | inside end |
| `ink.pen_lift.classify` | E → F | inside end |
| `ink.pen_lift.observers` | F → G | inside end (`onStrokeCompleted` fan-out) |
| `ink.pen_lift.append_bitmap` | H → I | `appendLastStrokeToBitmap` |
| `drain` (synthetic) | I → J | computed as `total − sync`; not a `PerfMetric` |
| `total` (synthetic) | A → J | the `PEN_LIFT_BUDGET_MS` window the test asserts on |

### What runs in the unmetered B → C gap

Wrapper code inside `endStroke` and the entry of `finishStroke`. From
[`HandwritingCanvasView.kt:498-528`](../../app/src/main/java/com/writer/view/HandwritingCanvasView.kt):

```
endStroke:
  cancelDwellJob()                          ← stops the dwell coroutine
  touchFilter.penActive = false; …          ← palm-rejection state
  onPenStateChanged?.invoke(false)          ← observer chain (UI state)
  currentStrokePoints.add(lastPoint)
  finishStroke()
finishStroke entry:
  onRawStrokeCapture?.invoke(points)        ← raw-capture observer chain
  → finishTextStroke()
finishTextStroke entry:
  scratch_check ← measurement starts at C
```

The G → H gap on the right has the symmetric story: after `observers`
returns, `finishTextStroke` calls `resetStrokeState`, `finishStroke`
closes its `lastFinishStrokeMs` timer, `endStroke` posts the idle
runnable, and the call stack unwinds.

The combined unmetered overhead is small (~6 ms p50) but watch for
expensive observers: `onPenStateChanged` and `onRawStrokeCapture` both
fire in this region and are not broken out individually.

### What runs in drain (I → J)

`waitForIdleSync` blocks the test thread until the main-thread message
queue is empty. Anything observers posted via `Handler.post` during
their synchronous body runs here. In production this includes:

- `onStrokeCompleted` observers' downstream save schedule
- recognition kick-offs (ML Kit / sherpa-onnx)
- redraws and undo-button state refreshes
- coalesced bitmap rebuilds posted via Choreographer

This is a real cost: even though it doesn't block the visible commit
(that already happened at I), it delays the next user input event by
the same amount of wall-clock time the queue takes to drain.

## Measured pen-lift values (Palma 2 Pro, 20 runs)

See [`docs/diagrams/pen-lift-distribution.svg`](../diagrams/pen-lift-distribution.svg)
and [`pen-lift-flame.svg`](../diagrams/pen-lift-flame.svg).

| Stage | min | p50 | p95 | max |
|---|---|---|---|---|
| total | 72 | 102 | 433 | 433 |
| begin · add_points · shape_snap · classify · append_bitmap | 0 | 0 | ≤ 3 | ≤ 3 |
| end | 42 | 69 | 145 | 145 |
| scratch_check | 19 | **31** | **95** | 95 |
| observers | 21 | **32** | 47 | 47 |
| drain | 22 | **33** | **293** | 293 |

p50 = 102 ms is already 2× the 50 ms `PEN_LIFT_BUDGET`. The cost centres
are `scratch_check`, `observers`, and `drain`. `scratch_check` and
`drain` are also where p50 → p95 amplification lives (5× and 13×
respectively); other stages stay roughly constant.

The `injectStroke`-direct stages (`begin`, `add_points`,
`append_bitmap`) and three of the four sub-stages inside `end`
(`shape_snap`, `classify`, plus the leftover overhead) are essentially
free — optimising them yields nothing.

## Diagrams

- **[`pen-lift-timeline.svg`](../diagrams/pen-lift-timeline.svg)** —
  integrated structural timeline. One time axis, both phases, every
  metric bracket. Spacing is logical, not measured.
- **[`pen-lift-distribution.svg`](../diagrams/pen-lift-distribution.svg)** —
  same structural layout as the timeline, with measured p50 / p95
  annotated under each pen-lift bracket.
- **[`pen-lift-flame.svg`](../diagrams/pen-lift-flame.svg)** —
  flame graph for the pen-lift portion only (live-ink had no
  measurements in this test corpus). p50 stack on top, p95 stack below
  at the same horizontal scale; user-observable bracket spans the root.

## Where to look in code

| Layer | File |
|---|---|
| inksdk `PerfMetric` enum | `third_party/inksdk/inkcontroller/src/main/java/com/inksdk/ink/PerfCounters.kt` |
| inksdk recording sites | `third_party/inksdk/inkcontroller/src/main/java/com/inksdk/ink/BigmeInkController.kt`, `OnyxInkController.kt` |
| Mokke `PerfSink` install | `app/src/main/java/com/writer/WriterApplication.kt` |
| Mokke `PerfMetric.INK_PEN_LIFT_*` enum | `app/src/main/java/com/writer/ui/writing/PerfCounters.kt` |
| Pen-lift instrumentation sites | `app/src/main/java/com/writer/view/HandwritingCanvasView.kt` (`injectStrokeForTest`, `finishTextStroke`) |
| Test harness + breakdown dump | `app/src/androidTest/java/com/writer/perf/StrokePipelinePerfTest.kt` |

## Open questions

- **Should the pen-lift budget be tightened or the implementation
  changed?** The 50 ms budget is unreachable on the Palma even with
  scratch_check + observers as the only contributors (p50 ≈ 63 ms inside
  `end` alone). Either the budget needs raising or the test should
  exclude `drain` and measure only the visible-commit window
  (A → I, p50 ≈ 69 ms).
- **scratch_check 5× variance** is the largest non-async cost. Worth
  profiling whether it's input-dependent (number of points, geometry)
  or system-noise-dependent (GC, scheduler).
- **`drain` is currently unmetered.** If observers' posted work becomes
  a recurring problem, instrument the individual posted-task costs the
  same way `INK_PEN_LIFT_*` instruments the synchronous path.
