package com.writer.perf

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.writer.model.StrokePoint
import com.writer.ui.writing.WritingActivity
import com.writer.view.HandwritingCanvasView
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App-level stroke pipeline integration tests. Verifies that strokes injected
 * via [HandwritingCanvasView.injectStrokeForTest] (which bypasses the Onyx SDK)
 * are correctly processed through the full beginStroke/addStrokePoint/endStroke
 * pipeline after various operations.
 *
 * NOTE: These tests do NOT verify the Onyx SDK's hardware input region (limitRect).
 * SDK input region bugs (e.g., clipped strokes after orientation change) can only
 * be detected by actual pen input on a Boox device — no programmatic injection
 * (including adb shell input stylus) reaches the Onyx SDK's hardware driver.
 * Manual test: rotate portrait→landscape→portrait, write on right side of screen.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.perf.StrokePipelineIntegrationTest
 */
@RunWith(AndroidJUnit4::class)
class StrokePipelineIntegrationTest {

    @get:Rule
    val activityRule = ActivityTestRule(WritingActivity::class.java)

    private lateinit var activity: WritingActivity

    @Before
    fun setUp() {
        activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun getCanvas(): HandwritingCanvasView =
        activity.findViewById(com.writer.R.id.inkCanvas)

    private fun makeStrokePoints(
        startX: Float, startY: Float, endX: Float, endY: Float, pointCount: Int = 10
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
     * Inject a stroke and verify it was captured by checking that
     * completedStrokes contains it (by checking the last stroke's bounds
     * overlap with the injected region).
     */
    private fun injectAndVerify(points: List<StrokePoint>, label: String) {
        val canvas = getCanvas()
        activityRule.runOnUiThread { canvas.injectStrokeForTest(points) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val strokes = canvas.getStrokes()
        val minX = points.minOf { it.x } - 5f
        val maxX = points.maxOf { it.x } + 5f
        val minY = points.minOf { it.y } - 5f
        val maxY = points.maxOf { it.y } + 5f

        val found = strokes.any { s ->
            s.points.any { p -> p.x in minX..maxX && p.y in minY..maxY }
        }
        assertTrue("$label: stroke not found in canvas (${strokes.size} total strokes)", found)
    }

    @Test
    fun strokesAcceptedAcrossFullCanvasWidth() {
        val canvas = getCanvas()
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // Left edge
        injectAndVerify(makeStrokePoints(10f, h / 2f, 50f, h / 2f), "left edge")
        // Center
        injectAndVerify(makeStrokePoints(w / 2f - 20f, h / 2f, w / 2f + 20f, h / 2f), "center")
        // Right edge
        injectAndVerify(makeStrokePoints(w - 60f, h / 2f, w - 10f, h / 2f), "right edge")
    }

    @Test
    fun strokesAcceptedAfterSpaceInsertMode() {
        val canvas = getCanvas()
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // Inject a stroke first
        injectAndVerify(makeStrokePoints(50f, 100f, 200f, 100f), "initial stroke")

        // Enter and exit space-insert mode
        activityRule.runOnUiThread {
            canvas.spaceInsertMode = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.runOnUiThread {
            canvas.spaceInsertMode = false
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify strokes accepted across full width after exiting mode
        injectAndVerify(makeStrokePoints(10f, h * 0.3f, 50f, h * 0.3f), "left after space-insert")
        injectAndVerify(makeStrokePoints(w / 2f, h * 0.3f, w / 2f + 40f, h * 0.3f), "center after space-insert")
        injectAndVerify(makeStrokePoints(w - 60f, h * 0.3f, w - 10f, h * 0.3f), "right after space-insert")
    }

    @Test
    fun strokesAcceptedAfterMultiplePauseResumeCycles() {
        val canvas = getCanvas()
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // Simulate multiple pause/resume cycles (like rapid undo/redo or recognition refreshes)
        activityRule.runOnUiThread {
            for (i in 0 until 5) {
                canvas.pauseRawDrawing()
                canvas.drawToSurface()
                canvas.resumeRawDrawing()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify strokes still accepted everywhere
        injectAndVerify(makeStrokePoints(10f, h * 0.4f, 50f, h * 0.4f), "left after pause cycles")
        injectAndVerify(makeStrokePoints(w - 60f, h * 0.4f, w - 10f, h * 0.4f), "right after pause cycles")
    }

    @Test
    fun strokesAcceptedAfterReinitialize() {
        val canvas = getCanvas()
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        activityRule.runOnUiThread { canvas.reinitializeRawDrawing() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        injectAndVerify(makeStrokePoints(10f, h * 0.5f, 50f, h * 0.5f), "left after reinit")
        injectAndVerify(makeStrokePoints(w - 60f, h * 0.5f, w - 10f, h * 0.5f), "right after reinit")
    }
}
