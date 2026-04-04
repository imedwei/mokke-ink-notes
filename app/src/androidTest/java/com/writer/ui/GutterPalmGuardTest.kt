package com.writer.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.writer.R
import com.writer.ui.writing.WritingActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumented tests verifying that the gutter palm guard is wired into
 * all gutter buttons in [WritingActivity].
 *
 * Simulates palm-sized and long-hold touch events and verifies that
 * click listeners do NOT fire, while normal fingertip taps still work.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.ui.GutterPalmGuardTest
 */
@RunWith(AndroidJUnit4::class)
class GutterPalmGuardTest {

    @get:Rule
    val activityRule = ActivityTestRule(WritingActivity::class.java)

    /** All gutter button IDs that must have the palm guard attached. */
    private val gutterButtonIds = listOf(
        R.id.menuButton,
        R.id.undoButton,
        R.id.redoButton,
        R.id.spaceInsertButton,
        R.id.viewToggleButton,
        R.id.rotateButton,
    )

    // --- Helpers ---

    /** Dispatch a synthetic touch event to a view on the UI thread. Returns true if consumed. */
    private fun dispatchTouch(view: View, action: Int, touchMajor: Float,
                              downTime: Long, eventTime: Long): Boolean {
        val props = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply {
            x = view.width / 2f
            y = view.height / 2f
            this.touchMajor = touchMajor
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action,
            1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        val consumed = view.dispatchTouchEvent(event)
        event.recycle()
        return consumed
    }

    /** Simulate a full tap (DOWN + UP) with given touch size and hold duration. */
    private fun simulateTap(view: View, touchMajor: Float, holdMs: Long = 50L) {
        val now = SystemClock.uptimeMillis()
        activityRule.runOnUiThread {
            dispatchTouch(view, MotionEvent.ACTION_DOWN, touchMajor, now, now)
            dispatchTouch(view, MotionEvent.ACTION_UP, touchMajor, now, now + holdMs)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // --- Tests: palm guard is wired to all buttons ---

    @Test
    fun allGutterButtons_haveOnTouchListener() {
        val activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.runOnUiThread {
            for (id in gutterButtonIds) {
                val btn = activity.findViewById<ImageView>(id)
                // OnTouchListener is private, but we can verify by sending a palm-sized
                // DOWN event — if the guard is attached it returns true (consumed).
                val now = SystemClock.uptimeMillis()
                val consumed = dispatchTouch(btn, MotionEvent.ACTION_DOWN, 200f, now, now)
                assertTrue(
                    "Button ${activity.resources.getResourceEntryName(id)} should reject palm touch",
                    consumed
                )
                // Send UP to clean up state
                dispatchTouch(btn, MotionEvent.ACTION_UP, 200f, now, now + 10)
            }
        }
    }

    @Test
    fun palmTouch_doesNotTriggerClick_onUndoButton() {
        val activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val clicked = AtomicBoolean(false)
        activityRule.runOnUiThread {
            activity.findViewById<ImageView>(R.id.undoButton)
                .setOnClickListener { clicked.set(true) }
        }

        simulateTap(activity.findViewById(R.id.undoButton), touchMajor = 200f)
        assertFalse("Palm touch should NOT trigger undo click", clicked.get())
    }

    @Test
    fun palmTouch_doesNotTriggerClick_onMenuButton() {
        val activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val clicked = AtomicBoolean(false)
        activityRule.runOnUiThread {
            activity.findViewById<ImageView>(R.id.menuButton)
                .setOnClickListener { clicked.set(true) }
        }

        simulateTap(activity.findViewById(R.id.menuButton), touchMajor = 200f)
        assertFalse("Palm touch should NOT trigger menu click", clicked.get())
    }

    @Test
    fun longHold_doesNotTriggerClick_onUndoButton() {
        val activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val clicked = AtomicBoolean(false)
        activityRule.runOnUiThread {
            activity.findViewById<ImageView>(R.id.undoButton)
                .setOnClickListener { clicked.set(true) }
        }

        // Small touch (not palm) but held for 1 second
        simulateTap(activity.findViewById(R.id.undoButton), touchMajor = 10f, holdMs = 1000L)
        assertFalse("Long hold should NOT trigger undo click", clicked.get())
    }

    @Test
    fun normalTap_triggersClick_onUndoButton() {
        val activity = activityRule.activity
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val clicked = AtomicBoolean(false)
        activityRule.runOnUiThread {
            activity.findViewById<ImageView>(R.id.undoButton)
                .setOnClickListener { clicked.set(true) }
        }

        // Small touch, quick tap
        simulateTap(activity.findViewById(R.id.undoButton), touchMajor = 10f, holdMs = 50L)
        assertTrue("Normal fingertip tap should trigger undo click", clicked.get())
    }
}
