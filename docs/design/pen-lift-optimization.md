---
status: draft
author: spm@edwei.com
created: 2026-05-03
updated: 2026-05-03
revisions:
  - 2026-05-03: initial draft
  - 2026-05-03: redesign Step 0 as production tagged-post telemetry
    (was: build a one-off Handler post recorder)
  - 2026-05-03: drop the Kotlin sample from Step 0; describe the
    mechanism in prose only
---

# Pen-lift optimisation: reducing the next-stroke latency tax

> **Status: draft.** This doc is a working plan, not an approved design.
> Open questions are tracked at the bottom; resolve them before
> implementation begins.

## Problem

After pen-up, the host's pen-lift pipeline runs synchronously on the
main thread (≈ 69 ms p50 inside `end`), then the message queue drains
whatever observers posted (≈ 33 ms p50, 293 ms p95).

If the user starts a new stroke before the queue has drained, the new
stroke's pen-down event sits behind that posted work. The user
perceives this as the next stroke's first paint being late, even
though the live-ink pipeline itself is fast.

**Quantitatively** (Palma 2 Pro, 20 runs — see
[`stroke-lifecycle.md`](stroke-lifecycle.md)):

| Span | p50 | p95 |
|---|---|---|
| Sync pen-lift work (A → I) | 69 ms | 145 ms |
| Drain (I → J) | 33 ms | 293 ms |
| **Total tax on next stroke** (A → J) | **102 ms** | **433 ms** |

`pen.kernel_to_paint`'s headline target on a Bigme HiBreak Plus is
**5 ms p50**. The next-stroke tax is 6× that target at p50 and 86× at
p95. Even halving it would substantially improve "rapid succession of
short strokes" feel.

Note: live-ink rendering itself runs on a binder/daemon thread, so
the daemon-side paint isn't directly main-thread-blocked. The tax
applies to the *host's* per-stroke bookkeeping — `beginStroke` runs
`handler.removeCallbacks`, launches the dwell coroutine, and updates
view state. Anywhere the new stroke needs the main thread, drain
delays it.

See [`pen-lift-flame.svg`](../diagrams/pen-lift-flame.svg) for the
cost composition and
[`pen-lift-distribution.svg`](../diagrams/pen-lift-distribution.svg)
for the per-stage spread.

## Step 0 — Tagged-post telemetry (production, do this first)

We don't currently know *who* is posting work into drain. The 33 ms is
opaque; without the breakdown the rest of this plan is guesswork.

**Design constraint: this telemetry stays in the production binary.**
Verbose-debug-log style probes get stripped after the investigation,
which means each future perf regression has to rebuild the harness.
A small permanent layer pays for itself the first time someone files
a bug report saying "saving feels slow on my Note Air 5C".

### Mechanism

Reuse the existing `PerfCounters.recordByLabel` infrastructure (already
zero-allocation hot-path, already shows up in bug reports via
`unifiedSnapshot()`, already aggregated by the `PerfDump` logcat line).

Add one small `TaggedPost` helper that wraps `Handler.post*` and emits
two label-keyed PerfCounters per call:

- `queue.<tag>.wait` — nanos between when the runnable was posted and
  when it actually started running. A queue-depth proxy: high values
  mean the runnable is stuck behind heavier work.
- `queue.<tag>.run`  — nanos the runnable's body took to execute.

The helper accepts the target `Handler`, a stable tag, and the
runnable. It is implemented as an inline wrapper so the closure cost
is just the runnable allocation that already exists, plus the two
`recordByLabel` calls (≈ 50 ns each, same overhead as the existing
`PerfCounters.time`).

The tag MUST be a compile-time constant so the label set is bounded;
no per-stroke or per-document interpolation. Each call site picks a
stable hand-chosen tag like `save.schedule`, `undo.refresh`,
`recognition.trigger`, `sync_indicator.fade`. Dot-notation makes the
bug-report viewer group related counters at a glance.

A `postDelayed` variant exists with the same shape; its `wait`
measurement subtracts the requested delay so the metric still reflects
queue contention, not the intentional delay.

### Why this is production-grade

- **Zero-allocation hot path.** The lambda is `crossinline`; the
  closure capture is the `Runnable` itself. The `nanoTime + record`
  pair is the same overhead that `PerfCounters.time` already pays
  ~50 ns.
- **No conditional compilation.** Always on. No debug-only `#ifdef`
  to forget about.
- **Already wired into bug reports.** Anything `recordByLabel`
  records appears in `BugReport.kt`'s `perfCounters` JSON section
  automatically, no code changes needed downstream.
- **Bounded label cardinality.** Tags are compile-time constants, so
  `externalCounters` map size is bounded by the number of call sites
  (~10–20). No memory growth from runtime-generated label strings.
- **Disable-able later.** If we later decide a particular tag is no
  longer interesting, replace the call with a plain `handler.post`
  in one line. Or add a `samplingRate` parameter that drops every
  Nth recording — but in practice the cost is so small that always-
  recording is fine.
- **Optional `Trace.beginAsyncSection` markers** can be added on the
  same code path so systrace/perfetto users see the same boundaries.
  Keeps the telemetry in two places that don't drift.

### Migration approach

1. Implement `TaggedPost` (one file, ~30 lines).
2. Find every `Handler.post*` / `view.post*` call reachable from
   `onStrokeCompleted`, `onPenStateChanged`, `onRawStrokeCapture`, the
   save trigger, and the recognition trigger. Replace each with
   `TaggedPost.post(…, tag = "…")`.
3. The first bug-report after this lands gives us the histogram. From
   that point onward, every bug report contains it.

### What we expect to see

Three things, ranked by what we expect:

| Tag (guess) | Expected p50 | Why |
|---|---|---|
| `queue.save.schedule.run` | high | Save is the largest known cost in `onStrokeCompleted` |
| `queue.recognition.trigger.run` | medium | Recognition is async but the trigger isn't |
| `queue.undo.refresh.run` | low-medium | Cosmetic, but fires every stroke |
| `queue.*.wait` | varies | Wait time = queue-depth proxy. High `wait` on a low-`run` tag means it's stuck behind something heavier. |

If reality differs from this guess, the optimisation order in this
doc changes accordingly.

## Easy wins

These are low-risk, mostly mechanical, and individually each should
trim drain noticeably without changing observable behaviour.

### W1. Save schedule on a background dispatcher

`onStrokeCompleted` triggers a save. The save itself is presumably
already async, but the *scheduling* code (debounce timer setup,
document-version bump, dirty-bitmap rebuild request) likely runs on
the main thread before any background work begins.

- Move the scheduling to `Dispatchers.IO` or a single-thread
  coroutine dispatcher (preserves write ordering).
- The main-thread cost collapses to a single `launch` call.

**Open Q:** does mokke currently use a single save coroutine, or does
each `onStrokeCompleted` spawn a new one? Need to check
`coordinator`'s save path.

### W2. Debounce / coalesce save

Five strokes in 200 ms should produce one save, not five.

- Implement either as `MutableSharedFlow` → `.debounce(150)` →
  collector, or as a single `Handler.postDelayed(saveRunnable, 200)`
  that gets cancelled and re-posted on each new stroke.
- Pair with W1 so debounce timing happens off main.

**Open Q:** what's the right debounce window? Too long → user-visible
"didn't save my last stroke before I closed". Too short → not enough
coalescing. Likely 150–300 ms.

### W3. IdleHandler for cosmetic UI work

Things like undo-button enabled-state, status-bar repaint, "saved"
indicator updates don't need to fire synchronously with stroke commit.

- `Looper.myQueue().addIdleHandler { … doWork(); false }` runs only
  when the queue is empty.
- A new pen-down preempts it automatically — exactly the behaviour
  we want.

**Open Q:** are any of these observers actually safety-critical
(e.g. crash if they don't run before next stroke)? Need to audit.

### W4. Recognition trigger on background

ML Kit / sherpa-onnx recognition is async once it's running. But the
trigger — packaging stroke points, building the recognition request,
hashing for cache — often runs on main.

- Push the trigger work to `Dispatchers.Default` (CPU-bound).
- The recognition itself stays wherever it currently is.

**Open Q:** does the current trigger touch UI state synchronously
(e.g. updating a "recognising…" indicator)? Need to inspect.

### W5. Skip async-rebuild bumps when nothing visible changed

`bumpMutationGen()` invalidates any in-flight async bitmap rebuild.
If `onStrokeCompleted` observers don't actually mutate visible
content, the rebuild is wasted.

- Audit which observer paths reach `bumpMutationGen()`. Skip the
  bump on paths that only do save / recognition / indexing.

**Open Q:** is there a safe predicate for "this observer doesn't
mutate visible content"? Probably needs an explicit per-observer
opt-out flag.

## Medium-effort changes

Architectural but contained — each could ship independently after
W1–W5 land.

### M1. Two-phase commit for `onStrokeCompleted`

Split the observer interface into a "critical sync" half (must
finish before visible commit) and a "deferred async" half (save,
recognition, indexing). The deferred half fires on a background
thread; the sync half holds itself to a < 5 ms wall-clock budget.

In practice this means giving the existing observer interface two
methods — one for critical synchronous work, one for deferred work
that the host invokes from a background dispatcher — and migrating
each existing subscriber to whichever half it belongs in.

**Open Q:** which existing observers truly need to be sync? Probably
just "add to in-memory document" and "add to event log"; everything
else can be deferred.

### M2. Yield drain on input arrival

Each posted runnable starts with: "is the system showing input
pending?" If yes, reschedule self at the back of the queue and
return. New input gets to run first.

- Tricky because `MotionEvent` queue isn't directly inspectable from
  application code.
- Approximation: `Choreographer.postFrameCallback` returns a callback
  that fires once per vsync. If we're inside drain and a frame
  callback fires that wasn't there at drain-start, treat it as "yield
  signal" — the system has new work.
- Cruder approximation: just chunk drain into ≤ 4 ms slices and
  yield by reposting between slices. Lets input interleave naturally
  via the message-queue priority system.

**Open Q:** does Android's main-thread Looper already prioritise
input vs. message? My understanding is that it doesn't — it's strict
FIFO. If so, the chunking approach is actually necessary, not just
an approximation.

### M3. Single low-priority work-thread for observers

Replace every `Handler.post` call inside the observer fan-out with a
single dedicated `Executor` (single-thread, ordered). Main-thread
queue stays free for input/UI; observer work runs sequentially on
the bg thread.

- Stricter than M1 because it forbids any sync observer work; everything
  goes async.
- Simpler than M1 because it's a single mechanical refactor with no
  per-observer re-classification.

**Open Q:** would M3 supersede M1, or are they orthogonal? M1 lets
observers opt into sync; M3 forbids sync. Probably pick one.

### M4. Pre-warm next-stroke state during drain

Operations the *next* stroke needs at `beginStroke` time (cancelling
the idle runnable, clearing the stroke-points buffer, resetting the
dwell job) can run during drain on a bg thread. When DOWN arrives,
`beginStroke` becomes a flag flip plus a few null assignments.

**Open Q:** which of `beginStroke`'s state mutations are thread-safe
to do off-main? `handler.removeCallbacks` is — `currentStrokePoints`
probably is not without a mutex.

## Bigger levers

Higher impact, more invasive. Tackle after the diagnostic shows
they're worth it.

### B1. Scratch_check off main

`checkPostStrokeScratchOut` is the largest synchronous sub-stage —
31 ms p50 / 95 ms p95. Pure stroke-shape analysis, no UI dependency.

- Run it on `Dispatchers.Default` (CPU-bound).
- The visible commit happens immediately (assume not-scratch).
- If the bg analysis later concludes it *was* scratch-out, apply the
  erase one or two frames later.

**Trade-off:** a stroke that turns out to be scratch-out briefly
appears as ink before being erased. Probably acceptable on e-ink
given the existing refresh latency, but needs UX validation.

**Open Q:** does scratch-out detection cascade — does deciding "this
is scratch" require examining the *previous* stroke's points? If yes,
moving it off-main needs a stroke-history snapshot.

### B2. Defer the drawing-classifier

Same story for `DiagramStrokeClassifier.classifyStroke` (0–3 ms p95
— already cheap, but conceptually it's a "promote stroke to diagram
intent" decision that doesn't have to be synchronous).

Probably not worth doing alone; bundle with B1's refactor.

### B3. SharedFlow-based observer fan-out

Replace the imperative `for (o in observers) o.invoke(stroke)` with
a single `MutableSharedFlow.emit(stroke)`. Each observer collects on
its own dispatcher.

- Cleanest separation of concerns.
- Removes the per-stroke fan-out cost from the host code path
  entirely.
- Big refactor — touches every existing observer registration site.

**Open Q:** does the current observer set rely on synchronous
ordering (e.g. observer A must complete before observer B sees the
stroke)? If yes, SharedFlow's per-collector independence breaks
that.

## Out-of-scope but worth flagging

- **`IDLE_TIMEOUT_MS`**: the idle runnable posted by `endStroke`
  does its own work some milliseconds later. If it fires inside
  drain, it adds to the tax. Worth measuring when it actually runs
  and possibly making its body a no-op when the next stroke is
  already in flight.

- **Test budget vs. user perception**: even after these wins,
  `PEN_LIFT_BUDGET_MS = 50` measures a window that doesn't directly
  map to user perception. A more honest pair of budgets:
  - "Time to visible commit" (A → I): the user sees the stroke. p50
    target ≈ 30 ms.
  - "Time to next-stroke-ready" (A → J): the user can start the next
    stroke without delay. p50 target ≈ 40 ms.
  Today's numbers (69 / 102 ms p50) miss both.

- **Live-ink instrumentation parity**: pen-lift now has 9 sub-metrics
  but live-ink has just the inksdk family. We could mirror the
  pen-lift instrumentation for the live-ink JVM-side critical region
  (event.handler internals: drawLine vs. inValidate vs. dispatch
  overhead) — see `live-ink-timeline.svg`. Might inform B1/B3
  trade-offs.

## Suggested implementation order

1. **Step 0** — diagnostic harness. One afternoon. Output: histogram
   of what's actually in drain.
2. **W1 + W2 + W3** as a single PR — surgical, additive, likely
   covers 60–80 % of the drain since save is almost certainly the
   heaviest poster.
3. Re-run the 20-iteration loop. Compare drain p50/p95 before vs.
   after.
4. **W4 + W5** if Step 0 showed they matter; skip if not.
5. **B1 (scratch_check off main)** if drain reduction was big but
   the *sync* side is still hot. Big lever for time-to-visible-commit.
6. **M1 or M3 (pick one)** as the consolidating refactor. M3 is
   cleaner; M1 is more flexible. The diagnostic should make this
   choice obvious.
7. **B3** only if we're going to add many more observers in the
   future. Premature otherwise.

After each step we should re-measure against the same Palma 2 Pro
corpus. The implementation isn't done until drain p50 ≤ 5 ms (to
match the live-ink budget the user actually feels).

## Open questions for the next iteration

These need to be resolved before any implementation begins. They're
listed in roughly ascending order of how disruptive the answer is.

1. **What does the diagnostic show?** Step 0's histogram is the
   dependency for almost every concrete decision below. Step 0's
   *design* is now resolved (tagged-post telemetry, production-grade
   — see the section above); only the data is missing.

2. **Which existing observers must run synchronously?** An audit of
   `onStrokeCompleted`, `onPenStateChanged`, `onRawStrokeCapture`
   subscribers is needed. The audit decides whether M1's two-phase
   split is necessary or M3's "everything async" is sufficient.

3. **What's the right save debounce window?** 150–300 ms range — the
   exact value depends on user expectations about "did my last stroke
   make it to disk before I closed the app".

4. **Does scratch-out detection have a stroke-history dependency?**
   Determines whether B1 is a one-line dispatcher swap or a
   data-marshalling refactor.

5. **Is the main-thread Looper input-vs-message ordering really
   FIFO?** If yes, M2's chunking is necessary. If no (e.g. there's
   a priority hint in `Message`), M2 can be a one-line annotation.

6. **Is `bumpMutationGen()` safe to skip on save-only paths?** W5
   depends on this; needs an audit of what reads the gen counter.

7. **What's the user-acceptable latency between visible-commit and
   confirmed-scratch-erase?** B1's UX trade-off lives here. If users
   tolerate one frame of delay (≈ 30 ms on e-ink), B1 is free; if
   they don't, B1 needs a fast preview path.

8. **What budget should we hold ourselves to?** "Drain p50 ≤ 5 ms"
   feels right (matches live-ink target) but is aggressive. Confirm
   the target before optimising — premature optimisation if the bar
   is "drain p50 ≤ 50 ms".
