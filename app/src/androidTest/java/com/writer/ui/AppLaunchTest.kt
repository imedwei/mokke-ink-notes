package com.writer.ui

import android.content.Context
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests that verify the app launches without crashing.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.ui.AppLaunchTest
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val activityRule = object : ActivityTestRule<WritingActivity>(WritingActivity::class.java) {
        override fun beforeActivityLaunched() {
            // Ensure a document with strokes exists so the startup load path is exercised
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val docName = "launch-test"
            val data = DocumentData(
                main = ColumnData(
                    strokes = listOf(
                        InkStroke(
                            points = listOf(
                                StrokePoint(10f, 20f, 0.5f, 1000L),
                                StrokePoint(30f, 40f, 0.8f, 2000L)
                            )
                        )
                    )
                )
            )
            DocumentStorage.save(context, docName, data)
            context.getSharedPreferences("writer_prefs", Context.MODE_PRIVATE)
                .edit().putString("current_doc", docName).commit()
        }
    }

    @Test
    fun activityLaunches_withExistingDocument_doesNotCrash() {
        val activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNotNull("Activity should not be null", activity)
        assertNotNull("inkCanvas should exist",
            activity.findViewById<HandwritingCanvasView>(com.writer.R.id.inkCanvas))
        assertTrue("Activity should not be finishing", !activity.isFinishing)
    }

    /** Verify loadStrokes works even before the view is attached (handler may be null). */
    @Test
    fun loadStrokes_onDetachedView_doesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Create a HandwritingCanvasView that is NOT attached to any window
        val detachedCanvas = HandwritingCanvasView(context)
        val strokes = listOf(
            InkStroke(
                points = listOf(
                    StrokePoint(10f, 20f, 0.5f, 1000L),
                    StrokePoint(30f, 40f, 0.8f, 2000L)
                )
            )
        )
        // This must not throw NPE even though handler is null
        detachedCanvas.loadStrokes(strokes)
    }
}
