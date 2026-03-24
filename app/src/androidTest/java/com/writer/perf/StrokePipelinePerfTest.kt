package com.writer.perf

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.writer.model.StrokePoint
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

    @Test
    fun singleStrokePenLiftUnderBudget() {
        val points = makeStrokePoints(50f, 200f, 300f, 200f)
        val elapsed = injectAndMeasure(points)

        assertTrue(
            "Pen-lift latency was ${elapsed}ms, budget is ${PEN_LIFT_BUDGET_MS}ms",
            elapsed < PEN_LIFT_BUDGET_MS
        )
    }

    @Test
    fun fiftyStrokesAllUnderBudget() {
        var maxMs = 0L

        for (i in 0 until 50) {
            val y = 150f + (i / 10) * 80f
            val startX = 30f + (i % 10) * 30f
            val points = makeStrokePoints(startX, y, startX + 25f, y + 5f, pointCount = 15)
            val elapsed = injectAndMeasure(points)

            if (elapsed > maxMs) maxMs = elapsed
        }

        assertTrue(
            "Worst-case pen-lift latency was ${maxMs}ms over 50 strokes, budget is ${PEN_LIFT_BUDGET_MS}ms",
            maxMs < PEN_LIFT_BUDGET_MS
        )
    }

    @Test
    fun eventLogTimingConsistent() {
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
