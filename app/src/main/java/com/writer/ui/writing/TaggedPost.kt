package com.writer.ui.writing

import android.os.Handler
import android.view.Choreographer

/**
 * Production-grade tagged-post telemetry for diagnosing main-thread queue
 * contention. Each call records two label-keyed [PerfCounters] entries:
 *
 *   queue.<tag>.wait — nanos between when the work was posted and when it
 *                      ran. A queue-depth proxy: high values mean the
 *                      runnable is stuck behind heavier work.
 *   queue.<tag>.run  — nanos the body took to execute.
 *
 * Tags must be compile-time constants so the label set stays bounded.
 *
 * Recordings flow into [PerfCounters.recordByLabel] and so appear
 * automatically in [BugReport]'s `perfCounters` JSON section and the
 * `PerfDump` logcat line via [PerfCounters.unifiedSnapshot].
 *
 * To preserve identity for [Handler.removeCallbacks] and
 * [Choreographer.removeFrameCallback], wrappers are reusable objects
 * ([TaggedRunnable], [TaggedFrameCallback]) created once and posted many
 * times. Each post stamps a fresh `postedAt` so the next run records
 * its own wait time.
 */
object TaggedPost {

    fun runnable(tag: String, body: () -> Unit): TaggedRunnable =
        TaggedRunnable(tag, body)

    fun frameCallback(tag: String, body: (frameTimeNanos: Long) -> Unit): TaggedFrameCallback =
        TaggedFrameCallback(tag, body)

    fun post(handler: Handler, runnable: TaggedRunnable) {
        runnable.postedAtNanos = System.nanoTime()
        runnable.delayMs = 0L
        handler.post(runnable)
    }

    fun postDelayed(handler: Handler, runnable: TaggedRunnable, delayMs: Long) {
        runnable.postedAtNanos = System.nanoTime()
        runnable.delayMs = delayMs
        handler.postDelayed(runnable, delayMs)
    }

    fun removeCallbacks(handler: Handler, runnable: TaggedRunnable) {
        handler.removeCallbacks(runnable)
    }

    fun postFrameCallback(choreographer: Choreographer, callback: TaggedFrameCallback) {
        callback.postedAtNanos = System.nanoTime()
        choreographer.postFrameCallback(callback)
    }

    fun removeFrameCallback(choreographer: Choreographer, callback: TaggedFrameCallback) {
        choreographer.removeFrameCallback(callback)
    }
}

/**
 * Reusable [Runnable] that records `queue.<tag>.{wait,run}` to
 * [PerfCounters] each time it runs. Subtracts the requested delay from
 * the wait so the metric reflects queue contention only.
 */
class TaggedRunnable internal constructor(
    private val tag: String,
    private val body: () -> Unit,
) : Runnable {
    @Volatile internal var postedAtNanos: Long = 0L
    @Volatile internal var delayMs: Long = 0L

    override fun run() {
        val ranAtNanos = System.nanoTime()
        val expectedRun = postedAtNanos + delayMs * 1_000_000L
        val waitNanos = (ranAtNanos - expectedRun).coerceAtLeast(0L)
        PerfCounters.recordByLabel("queue.$tag.wait", waitNanos)
        try {
            body()
        } finally {
            PerfCounters.recordByLabel("queue.$tag.run", System.nanoTime() - ranAtNanos)
        }
    }
}

/**
 * Reusable [Choreographer.FrameCallback] that records `queue.<tag>.{wait,run}`
 * to [PerfCounters] each time the frame fires.
 */
class TaggedFrameCallback internal constructor(
    private val tag: String,
    private val body: (frameTimeNanos: Long) -> Unit,
) : Choreographer.FrameCallback {
    @Volatile internal var postedAtNanos: Long = 0L

    override fun doFrame(frameTimeNanos: Long) {
        val ranAtNanos = System.nanoTime()
        PerfCounters.recordByLabel("queue.$tag.wait", ranAtNanos - postedAtNanos)
        try {
            body(frameTimeNanos)
        } finally {
            PerfCounters.recordByLabel("queue.$tag.run", System.nanoTime() - ranAtNanos)
        }
    }
}
