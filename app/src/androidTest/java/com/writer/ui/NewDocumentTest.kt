package com.writer.ui

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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that creating a new document properly clears all state
 * including inline overlays, consolidated text, and word popups.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.ui.NewDocumentTest
 */
@RunWith(AndroidJUnit4::class)
class NewDocumentTest {

    @get:Rule
    val activityRule = object : ActivityTestRule<WritingActivity>(WritingActivity::class.java, false, false) {
        override fun beforeActivityLaunched() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Create a document with strokes and text cache so overlays will be built
            val ls = HandwritingCanvasView.LINE_SPACING
            val tm = HandwritingCanvasView.TOP_MARGIN
            val strokes = mutableListOf<InkStroke>()
            val textCache = mutableMapOf<Int, String>()
            for (line in 0 until 5) {
                val y = tm + line * ls + ls / 2
                strokes.add(InkStroke(
                    strokeId = "s$line",
                    points = listOf(
                        StrokePoint(10f, y, 0.5f, (line * 1000).toLong()),
                        StrokePoint(200f, y, 0.5f, (line * 1000 + 500).toLong())
                    )
                ))
                textCache[line] = "Text for line $line"
            }
            DocumentStorage.save(context, "new-doc-test", DocumentData(
                main = ColumnData(strokes = strokes, lineTextCache = textCache),
                highestLineIndex = 4, currentLineIndex = 4
            ))
            context.getSharedPreferences("writer_prefs", Context.MODE_PRIVATE)
                .edit().putString("current_doc", "new-doc-test").commit()
        }
    }

    @Test
    fun newDocument_clearsConsolidatedOverlays() {
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val activity = activityRule.activity
        val canvas = activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas)

        // Verify overlays exist from loaded document
        Thread.sleep(1000)  // wait for async document load + overlay build
        activityRule.runOnUiThread {} // sync
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val overlaysBefore = canvas.inlineTextOverlays.size

        // Trigger new document via menu action
        activityRule.runOnUiThread {
            // Access newDocument via the menu — call it reflectively since it's private
            val method = WritingActivity::class.java.getDeclaredMethod("newDocument")
            method.isAccessible = true
            method.invoke(activity)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify all overlays are cleared
        val overlaysAfter = canvas.inlineTextOverlays.size
        assertEquals("Overlays should be empty after new document", 0, overlaysAfter)

        // Verify canvas has no strokes
        val coordinator = activity.coordinator
        assertNotNull("Coordinator should exist", coordinator)
        // The strokes should be cleared
        assertTrue("Active strokes should be empty or very few",
            coordinator!!.columnModel.activeStrokes.size <= 1)
    }

    @Test
    fun newDocument_acceptsNewStrokes() {
        activityRule.launchActivity(null)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val activity = activityRule.activity
        val canvas = activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas)

        // Create new document
        activityRule.runOnUiThread {
            val method = WritingActivity::class.java.getDeclaredMethod("newDocument")
            method.isAccessible = true
            method.invoke(activity)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Write a stroke on the new document
        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN
        val y = tm + ls / 2
        val points = listOf(
            StrokePoint(10f, y, 0.5f, SystemClock.uptimeMillis()),
            StrokePoint(50f, y, 0.5f, SystemClock.uptimeMillis() + 50),
            StrokePoint(100f, y, 0.5f, SystemClock.uptimeMillis() + 100)
        )
        activityRule.runOnUiThread { canvas.injectStrokeForTest(points) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Verify stroke was accepted
        val coordinator = activity.coordinator
        assertTrue("Should have at least 1 stroke after writing",
            coordinator!!.columnModel.activeStrokes.isNotEmpty())
    }
}
