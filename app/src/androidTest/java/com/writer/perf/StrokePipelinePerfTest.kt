package com.writer.perf

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.writer.model.StrokePoint
import com.writer.ui.writing.PerfCounters
import com.writer.ui.writing.PerfMetric
import com.writer.ui.writing.StrokeEventLog
import com.writer.ui.writing.WritingActivity
import com.writer.view.HandwritingCanvasView
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 2 + 3 performance tests: measure the full stroke processing pipeline
 * on a real device through WritingActivity.
 *
 * Uses [HandwritingCanvasView.injectStrokeForTest] to simulate the full SDK
 * callback path (onBeginRawDrawing → moves → onEndRawDrawing), including
 * onPenStateChanged callbacks and posted runnables like updateUndoRedoButtons.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.perf.StrokePipelinePerfTest
 */
@RunWith(AndroidJUnit4::class)
class StrokePipelinePerfTest {

    @get:Rule
    val activityRule = ActivityTestRule(WritingActivity::class.java)

    private lateinit var activity: WritingActivity

    /** Budget: maximum allowed total pen-lift latency in ms.
     *  Includes finishStroke() + all posted callbacks (onPenStateChanged, updateUndoRedoButtons). */
    private val PEN_LIFT_BUDGET_MS = 50L

    /** Tail budget for the warmed-distribution test. The 50 ms median budget
     *  is the user-acceptability threshold; p95 may exceed it occasionally
     *  due to GC / scheduler / EPD-refresh contention. We tolerate up to
     *  this much in the worst 1-in-20 stroke. */
    private val PEN_LIFT_P95_BUDGET_MS = 100L

    /** Budget: maximum allowed event-log latency (stroke record → ADDED event) in ms. */
    private val EVENT_LOG_BUDGET_MS = 150L

    @Before
    fun setUp() {
        activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun getCanvas(): HandwritingCanvasView =
        activity.findViewById(com.writer.R.id.inkCanvas)

    /** Create a simple horizontal stroke at the given Y position. */
    private fun makeStrokePoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        pointCount: Int = 20
    ): List<StrokePoint> {
        val baseTime = SystemClock.uptimeMillis()
        return (0 until pointCount).map { i ->
            val t = i.toFloat() / (pointCount - 1)
            StrokePoint(
                x = startX + (endX - startX) * t,
                y = startY + (endY - startY) * t,
                pressure = 0.5f,
                timestamp = baseTime + i * 5L
            )
        }
    }

    /**
     * Inject a stroke and return wall-clock time in ms including all
     * posted callbacks (waitForIdleSync drains the message queue).
     */
    private fun injectAndMeasure(points: List<StrokePoint>): Long {
        val canvas = getCanvas()
        val t0 = SystemClock.elapsedRealtime()
        activityRule.runOnUiThread { canvas.injectStrokeForTest(points) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return SystemClock.elapsedRealtime() - t0
    }

    /**
     * Cold-start single-stroke pen-lift latency. Measures the **first stroke
     * after process launch** with no JIT warmup. Captures the worst-case
     * "user just opened the app and made one mark" latency, which includes
     * ART bytecode interpretation cost (~3× the steady-state algorithmic
     * cost on this device).
     *
     * Use [warmedPenLiftDistributionUnderBudgets] as the steady-state gate;
     * this test catches regressions in launch-time responsiveness.
     */
    @Test
    fun coldStartSingleStrokePenLiftUnderBudget() {
        // Reset so the breakdown reflects only this stroke. The test runs
        // once per gradle invocation; fresh counters give us count=1 per
        // stage, which the driver script aggregates across runs.
        PenLiftBreakdown.reset()
        val points = makeStrokePoints(50f, 200f, 300f, 200f)
        val elapsed = injectAndMeasure(points)
        PenLiftBreakdown.dump(elapsed)

        assertTrue(
            "Cold-start pen-lift latency was ${elapsed}ms, budget is ${PEN_LIFT_BUDGET_MS}ms",
            elapsed < PEN_LIFT_BUDGET_MS
        )
    }

    /**
     * Single-stroke pen-lift latency after JIT warmup. Pre-injects 10
     * throwaway strokes to give ART time to compile the hot path, then
     * measures one stroke.
     *
     * Useful for one-shot debugging (single PenLiftBreakdown line per
     * invocation, easy to read in logcat). For a regression gate use
     * [warmedPenLiftDistributionUnderBudgets] instead — single-shot
     * measurement is too noisy for a stable assertion.
     */
    @Test
    fun warmedSingleStrokePenLiftUnderBudget() {
        // Pre-warm the host pipeline. Each injected stroke flows through the
        // full beginStroke / addStrokePoint / endStroke / finishTextStroke /
        // observer-fan-out / appendLastStrokeToBitmap path. After ~10 strokes
        // ART has profile data and JIT-compiles the hot methods.
        // Warmup strokes use short diagonal segments — long horizontal lines
        // would be detected as strikethrough gestures by the host pipeline,
        // triggering removeStrokes → commit_mutation → forced_refresh
        // cascades that pollute the measured stroke's drain window.
        for (i in 0 until 10) {
            val y = 150f + i * 50f
            injectAndMeasure(makeStrokePoints(50f, y, 80f, y + 30f, pointCount = 12))
        }
        PenLiftBreakdown.reset()
        val points = makeStrokePoints(50f, 50f, 300f, 50f)
        val elapsed = injectAndMeasure(points)
        PenLiftBreakdown.dump(elapsed)

        assertTrue(
            "Warmed pen-lift latency was ${elapsed}ms, budget is ${PEN_LIFT_BUDGET_MS}ms",
            elapsed < PEN_LIFT_BUDGET_MS
        )
    }

    /**
     * Steady-state pen-lift latency distribution — the **production-
     * relevant gate**. Pre-warms with 10 strokes to let ART JIT-compile
     * the hot path, then measures 30 plain-text cursive-letter-length
     * strokes and asserts BOTH:
     *   - p50 < PEN_LIFT_BUDGET_MS       (50 ms — typical user case)
     *   - p95 < PEN_LIFT_P95_BUDGET_MS  (100 ms — tail tolerance)
     *
     * Two distinct assertions so a regression points at WHICH
     * characteristic broke (typical vs occasional-spike).
     *
     * Stroke pattern: short diagonal segments — long horizontals would
     * be detected as strikethrough gestures, triggering removeStrokes →
     * commit_mutation → forced_refresh cascades that inflate drain (see
     * pen-lift-optimization.md, Step 0 follow-up #2).
     *
     * Logs the full 30-sample distribution so a CI failure shows the
     * exact data — no mystery about whether one outlier or a systemic
     * shift caused it.
     */
    @Test
    fun warmedPenLiftDistributionUnderBudgets() {
        // Warmup — let ART JIT-compile the hot path.
        for (i in 0 until 10) {
            val y = 150f + i * 50f
            injectAndMeasure(makeStrokePoints(50f, y, 80f, y + 30f, pointCount = 12))
        }

        // Reset PerfCounters so the per-stage distribution dump below
        // reflects only the measured strokes, not the warmup ones.
        PenLiftBreakdown.reset()

        // Measure 30 strokes spread across the canvas. Each stroke is a short
        // diagonal of 30 points — representative of a single cursive letter.
        // Avoids long horizontals that the strikethrough detector treats as
        // erase gestures.
        val n = 30
        val totals = LongArray(n)
        for (i in 0 until n) {
            val row = i / 6                // 5 rows
            val col = i % 6                // 6 columns
            val x = 50f + col * 60f
            val y = 200f + row * 80f
            totals[i] = injectAndMeasure(
                makeStrokePoints(x, y, x + 40f, y + 30f, pointCount = 30)
            )
        }

        val sorted = totals.copyOf().also { it.sort() }
        val p50 = sorted[n / 2]
        val p95 = sorted[((n - 1) * 95) / 100]
        val maxMs = sorted[n - 1]
        val minMs = sorted[0]

        Log.i(
            "PenLiftDistribution",
            "n=$n min=$minMs p50=$p50 p95=$p95 max=$maxMs samples=${totals.joinToString(",")}",
        )
        // Per-stage breakdown across all 30 measured strokes.
        PenLiftBreakdown.dumpDistribution()

        // Two assertions — each gives a distinct failure message so a regression
        // points at WHICH characteristic broke.
        assertTrue(
            "Warmed p50 pen-lift was ${p50}ms over $n strokes, budget is ${PEN_LIFT_BUDGET_MS}ms (samples: ${totals.toList()})",
            p50 < PEN_LIFT_BUDGET_MS
        )
        assertTrue(
            "Warmed p95 pen-lift was ${p95}ms over $n strokes, tail budget is ${PEN_LIFT_P95_BUDGET_MS}ms (samples: ${totals.toList()})",
            p95 < PEN_LIFT_P95_BUDGET_MS
        )
    }

    /**
     * Cold-start worst-case pen-lift latency over 50 short strokes,
     * starting from a fresh process. No JIT pre-warmup — all strokes
     * run against partially-interpreted bytecode. Each stroke must
     * individually be under [PEN_LIFT_BUDGET_MS]; one outlier fails
     * the test.
     *
     * This is a stricter, noisier sibling of
     * [coldStartSingleStrokePenLiftUnderBudget]: it catches regressions
     * that show up as a single bad stroke among many.
     */
    @Test
    fun coldStart50StrokesWorstCaseUnderBudget() {
        var maxMs = 0L

        for (i in 0 until 50) {
            val y = 150f + (i / 10) * 80f
            val startX = 30f + (i % 10) * 30f
            val points = makeStrokePoints(startX, y, startX + 25f, y + 5f, pointCount = 15)
            val elapsed = injectAndMeasure(points)

            if (elapsed > maxMs) maxMs = elapsed
        }

        assertTrue(
            "Cold-start worst-case pen-lift latency was ${maxMs}ms over 50 strokes, budget is ${PEN_LIFT_BUDGET_MS}ms",
            maxMs < PEN_LIFT_BUDGET_MS
        )
    }

    /**
     * Event-log latency: time between a stroke being recorded and the
     * `ADDED` event arriving in the [StrokeEventLog]. Different metric
     * from pen-lift — measures host-side post-stroke event propagation.
     */
    @Test
    fun eventLogLatencyUnderBudget() {
        for (i in 0 until 10) {
            val points = makeStrokePoints(50f, 200f + i * 80f, 300f, 200f + i * 80f)
            injectAndMeasure(points)
        }

        val coordinator = activity.coordinator ?: return
        val eventLog = coordinator.eventLog

        val snapshot = eventLog.snapshot()
        if (snapshot.strokes.isEmpty()) return

        for (stroke in snapshot.strokes) {
            val addedEvent = snapshot.events.find {
                it.strokeIndex == stroke.index && it.type == StrokeEventLog.EventType.ADDED
            } ?: continue

            val latencyMs = addedEvent.timestampMs - stroke.timestampMs
            assertTrue(
                "Stroke ${stroke.index}: event log latency ${latencyMs}ms exceeds budget ${EVENT_LOG_BUDGET_MS}ms",
                latencyMs < EVENT_LOG_BUDGET_MS
            )
        }
    }
}

/**
 * Helper for [coldStartSingleStrokePenLiftUnderBudget] that captures the per-stage
 * breakdown of `injectStrokeForTest` into a single logcat line. The driver
 * script in `scripts/aggregate-pen-lift.sh` greps for `PenLiftBreakdown`
 * lines across N test runs, then aggregates and produces the SVG.
 */
private object PenLiftBreakdown {
    private val STAGES = listOf(
        PerfMetric.INK_PEN_LIFT_BEGIN,
        PerfMetric.INK_PEN_LIFT_ADD_POINTS,
        PerfMetric.INK_PEN_LIFT_END,
        PerfMetric.INK_PEN_LIFT_SCRATCH_CHECK,
        PerfMetric.INK_PEN_LIFT_SHAPE_SNAP,
        PerfMetric.INK_PEN_LIFT_CLASSIFY,
        PerfMetric.INK_PEN_LIFT_OBSERVERS,
        PerfMetric.INK_PEN_LIFT_APPEND_BITMAP,
    )

    fun reset() = PerfCounters.reset()

    fun dump(totalMs: Long) {
        val parts = STAGES.joinToString(" ") { m ->
            val s = PerfCounters.get(m)
            "${m.label}=${s.lastMs}"
        }
        // BEGIN, ADD_POINTS, END run synchronously inside injectStrokeForTest;
        // the difference between total and their sum is "drain" — the time
        // waitForIdleSync spends draining message-queue work that the
        // observers / handlers posted during the synchronous phase.
        val sync = STAGES
            .filter { it == PerfMetric.INK_PEN_LIFT_BEGIN ||
                      it == PerfMetric.INK_PEN_LIFT_ADD_POINTS ||
                      it == PerfMetric.INK_PEN_LIFT_END ||
                      it == PerfMetric.INK_PEN_LIFT_APPEND_BITMAP }
            .sumOf { PerfCounters.get(it).lastMs }
        val drain = (totalMs - sync).coerceAtLeast(0L)
        // Append every queue.<tag>.{wait,run} label-keyed counter so this
        // single line carries both the named pen-lift breakdown and the
        // tagged-post diagnostic (Step 0 of pen-lift-optimization.md).
        // Counts are summed across the test run since each TaggedFrameCallback
        // can fire multiple times during waitForIdleSync drain.
        val queueParts = PerfCounters.unifiedSnapshot()
            .filter { it.label.startsWith("queue.") }
            .joinToString(" ") { row ->
                "${row.label}=${row.lastMs}/${row.count}"
            }
        Log.i("PenLiftBreakdown", "total=${totalMs} drain=${drain} $parts ${queueParts}")
    }

    /**
     * Dump aggregated per-stage statistics across all PerfCounters samples
     * collected since the last [reset]. Use after a multi-sample test loop —
     * each PerfMetric ring buffer holds the full sample window so we get
     * proper p50 / p95 / max per stage, not just the last value.
     *
     * Emits two logcat lines:
     *   PenLiftStages — per-stage counts and percentiles for the
     *     ink.pen_lift.* family.
     *   PenLiftQueue  — accumulated queue.<tag>.* tagged-post counters
     *     (suppressed if no tagged callbacks fired).
     */
    fun dumpDistribution() {
        val stages = STAGES.joinToString(" ") { m ->
            val s = PerfCounters.get(m)
            "${m.label}=count=${s.count}/p50=${s.p50Ms}/p95=${s.p95Ms}/max=${s.maxMs}"
        }
        Log.i("PenLiftStages", stages)

        val queueParts = PerfCounters.unifiedSnapshot()
            .filter { it.label.startsWith("queue.") }
            .joinToString(" ") { row ->
                "${row.label}=count=${row.count}/p50=${row.p50Ms}/p95=${row.p95Ms}/max=${row.maxMs}"
            }
        if (queueParts.isNotEmpty()) {
            Log.i("PenLiftQueue", queueParts)
        }
    }
}
