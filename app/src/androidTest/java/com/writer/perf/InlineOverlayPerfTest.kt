package com.writer.perf

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.storage.DocumentStorage
import com.writer.ui.writing.WritingActivity
import com.writer.view.HandwritingCanvasView
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected-device performance tests for the inline text overlay system.
 * These catch ANR-causing regressions that only manifest on real hardware:
 * - Scratch-out on documents with 200+ strokes
 * - Overlay rebuilds during scroll
 * - Startup with large documents
 * - Word popup display cycle
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.perf.InlineOverlayPerfTest
 */
@RunWith(AndroidJUnit4::class)
class InlineOverlayPerfTest {

    companion object {
        /** Maximum allowed time for a single scratch-out (detection + erase + refresh). */
        private const val SCRATCH_OUT_BUDGET_MS = 100L
        /** Maximum allowed time for a stroke on a document with 200+ strokes. */
        private const val STROKE_WITH_LARGE_DOC_BUDGET_MS = 80L
        /** Maximum allowed time for startup with a pre-loaded document.
         *  Includes cold Activity creation, Onyx SDK init (~500ms), document load,
         *  and recognizer initialization. E-ink devices are inherently slower. */
        private const val STARTUP_BUDGET_MS = 8000L
        /** Maximum allowed time for a single scroll frame.
         *  E-ink SurfaceView rendering (lockCanvas + draw 280 strokes + unlockAndPost)
         *  has inherent overhead of ~60-80ms. Budget accounts for this. */
        private const val SCROLL_FRAME_BUDGET_MS = 100L
    }

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    /** Create a document with many strokes across multiple lines. */
    private fun createLargeDocument(context: Context, name: String, lineCount: Int, strokesPerLine: Int = 5) {
        val strokes = mutableListOf<InkStroke>()
        val textCache = mutableMapOf<Int, String>()
        for (line in 0 until lineCount) {
            for (s in 0 until strokesPerLine) {
                val y = tm + line * ls + ls * 0.5f
                strokes.add(InkStroke(
                    strokeId = "s${line}_$s",
                    points = listOf(
                        StrokePoint(10f + s * 50f, y, 0.5f, (line * 5000 + s * 500).toLong()),
                        StrokePoint(50f + s * 50f, y + 2f, 0.5f, (line * 5000 + s * 500 + 200).toLong()),
                        StrokePoint(80f + s * 50f, y, 0.5f, (line * 5000 + s * 500 + 400).toLong())
                    )
                ))
            }
            textCache[line] = "Line $line has some recognized text here"
        }
        val data = DocumentData(
            main = ColumnData(
                strokes = strokes,
                lineTextCache = textCache
            ),
            highestLineIndex = lineCount - 1,
            currentLineIndex = lineCount - 1
        )
        DocumentStorage.save(context, name, data)
        context.getSharedPreferences("writer_prefs", Context.MODE_PRIVATE)
            .edit().putString("current_doc", name).commit()
    }

    /** Create points simulating a scratch-out gesture (rapid back-and-forth). */
    private fun makeScratchOutPoints(
        centerX: Float, centerY: Float,
        width: Float = 200f, reversals: Int = 5, pointsPerPass: Int = 30
    ): List<StrokePoint> {
        val points = mutableListOf<StrokePoint>()
        val baseTime = SystemClock.uptimeMillis()
        var t = 0
        for (rev in 0 until reversals) {
            val leftToRight = rev % 2 == 0
            for (i in 0 until pointsPerPass) {
                val frac = i.toFloat() / (pointsPerPass - 1)
                val x = if (leftToRight) centerX - width / 2 + width * frac
                        else centerX + width / 2 - width * frac
                val jitterY = (Math.random() * 6 - 3).toFloat()
                points.add(StrokePoint(x, centerY + jitterY, 0.5f, baseTime + t * 3L))
                t++
            }
        }
        return points
    }

    private fun makeStrokePoints(
        startX: Float, startY: Float, endX: Float, endY: Float,
        pointCount: Int = 20
    ): List<StrokePoint> {
        val baseTime = SystemClock.uptimeMillis()
        return (0 until pointCount).map { i ->
            val frac = i.toFloat() / (pointCount - 1)
            StrokePoint(
                x = startX + (endX - startX) * frac,
                y = startY + (endY - startY) * frac,
                pressure = 0.5f,
                timestamp = baseTime + i * 5L
            )
        }
    }

    // ── Test: Startup with large document ─────────────────────────────────

    @get:Rule
    val activityRule = object : ActivityTestRule<WritingActivity>(WritingActivity::class.java, false, false) {
        override fun beforeActivityLaunched() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            createLargeDocument(context, "perf-test-large", lineCount = 40, strokesPerLine = 7)
        }
    }

    @org.junit.After
    fun tearDown() {
        // Clean up test documents so they don't pollute the dev app
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DocumentStorage.delete(context, "perf-test-large")
    }

    @Test
    fun startup_withLargeDocument_isResponsiveWithinBudget() {
        val t0 = SystemClock.elapsedRealtime()
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val elapsed = SystemClock.elapsedRealtime() - t0

        assertTrue(
            "Startup took ${elapsed}ms with large document (budget: ${STARTUP_BUDGET_MS}ms)",
            elapsed < STARTUP_BUDGET_MS
        )
        assertTrue("Activity should not be finishing", !activityRule.activity.isFinishing)
    }

    // ── Test: Stroke processing with large document ───────────────────────

    @Test
    fun strokeOnLargeDocument_completesWithinBudget() {
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val canvas = activityRule.activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas)

        // Write 10 additional strokes on the large document
        var maxMs = 0L
        for (i in 0 until 10) {
            val y = tm + 41 * ls + ls * 0.5f  // line 41 (after pre-existing content)
            val points = makeStrokePoints(30f + i * 30f, y, 60f + i * 30f, y + 3f)

            val t0 = SystemClock.elapsedRealtime()
            activityRule.runOnUiThread { canvas.injectStrokeForTest(points) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val elapsed = SystemClock.elapsedRealtime() - t0

            if (elapsed > maxMs) maxMs = elapsed
        }

        assertTrue(
            "Worst stroke processing was ${maxMs}ms on large doc (budget: ${STROKE_WITH_LARGE_DOC_BUDGET_MS}ms)",
            maxMs < STROKE_WITH_LARGE_DOC_BUDGET_MS
        )
    }

    // ── Test: Scratch-out on large document ────────────────────────────────

    @Test
    fun scratchOutOnLargeDocument_completesWithinBudget() {
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val canvas = activityRule.activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas)

        // First add some strokes on a visible line to scratch out
        val targetLine = 38
        val targetY = tm + targetLine * ls + ls * 0.5f
        for (i in 0 until 3) {
            val points = makeStrokePoints(30f + i * 80f, targetY, 90f + i * 80f, targetY + 3f)
            activityRule.runOnUiThread { canvas.injectStrokeForTest(points) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        // Now scratch out those strokes
        val scratchPoints = makeScratchOutPoints(centerX = 160f, centerY = targetY, width = 250f)

        val t0 = SystemClock.elapsedRealtime()
        activityRule.runOnUiThread { canvas.injectStrokeForTest(scratchPoints) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val elapsed = SystemClock.elapsedRealtime() - t0

        assertTrue(
            "Scratch-out took ${elapsed}ms on large doc (budget: ${SCRATCH_OUT_BUDGET_MS}ms)",
            elapsed < SCRATCH_OUT_BUDGET_MS
        )
    }

    // ── Test: Multiple rapid scratch-outs don't ANR ───────────────────────

    @Test
    fun multipleRapidScratchOuts_doNotHang() {
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val canvas = activityRule.activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas)

        // Add strokes on several lines
        for (line in 35..39) {
            val y = tm + line * ls + ls * 0.5f
            for (i in 0 until 3) {
                val points = makeStrokePoints(30f + i * 80f, y, 90f + i * 80f, y + 3f)
                activityRule.runOnUiThread { canvas.injectStrokeForTest(points) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
        }

        // Rapid-fire 3 scratch-outs in sequence
        var totalMs = 0L
        for (line in 37..39) {
            val y = tm + line * ls + ls * 0.5f
            val scratchPoints = makeScratchOutPoints(centerX = 160f, centerY = y, width = 250f)

            val t0 = SystemClock.elapsedRealtime()
            activityRule.runOnUiThread { canvas.injectStrokeForTest(scratchPoints) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            totalMs += SystemClock.elapsedRealtime() - t0
        }

        assertTrue(
            "3 rapid scratch-outs took ${totalMs}ms total (budget: ${SCRATCH_OUT_BUDGET_MS * 3}ms)",
            totalMs < SCRATCH_OUT_BUDGET_MS * 3  // 300ms for 3 scratch-outs
        )
    }

    // ── Test: Scroll with consolidated overlays ────────────────────────────

    @Test
    fun scrollWithConsolidatedOverlays_isSmooth() {
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val canvas = activityRule.activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas)

        // Cold frame: first scroll triggers Path cache build for consolidated lines
        val coldT0 = SystemClock.elapsedRealtime()
        activityRule.runOnUiThread {
            canvas.scrollOffsetY += ls
            canvas.drawToSurface()
            canvas.onManualScroll?.invoke()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val coldMs = SystemClock.elapsedRealtime() - coldT0

        // Warm frames: subsequent scrolls hit Path cache
        var maxWarmMs = 0L
        var totalWarmMs = 0L
        val warmFrames = 10
        for (step in 0 until warmFrames) {
            val t0 = SystemClock.elapsedRealtime()
            activityRule.runOnUiThread {
                canvas.scrollOffsetY += ls
                canvas.drawToSurface()
                canvas.onManualScroll?.invoke()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val elapsed = SystemClock.elapsedRealtime() - t0
            totalWarmMs += elapsed
            if (elapsed > maxWarmMs) maxWarmMs = elapsed
        }
        val avgWarmMs = totalWarmMs / warmFrames

        assertTrue(
            "Worst warm scroll frame was ${maxWarmMs}ms (budget: ${SCROLL_FRAME_BUDGET_MS}ms)",
            maxWarmMs < SCROLL_FRAME_BUDGET_MS
        )
        // Warm frames should be faster than cold frame (Path cache working)
        assertTrue(
            "Avg warm frame (${avgWarmMs}ms) should be faster than cold frame (${coldMs}ms)",
            avgWarmMs <= coldMs || coldMs < 20  // skip check if cold is already fast
        )
    }
}
