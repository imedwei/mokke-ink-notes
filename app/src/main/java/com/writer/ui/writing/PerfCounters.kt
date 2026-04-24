package com.writer.ui.writing

/**
 * Named performance metrics. Each maps to a pre-allocated ring buffer
 * in [PerfCounters] indexed by [ordinal] — no HashMap lookup.
 */
enum class PerfMetric(val label: String) {
    PREVIEW_FORK("preview.fork"),
    PREVIEW_READ("preview.read"),
    PREVIEW_DRAW("preview.draw"),
    SAVE_SYNC("save.sync"),
    SAVE_INCREMENTAL("save.incremental"),
    // Ink-latency metrics — recorded by HandwritingCanvasView.
    INK_RENDER_STATIC("ink.render_static"),       // rebuildContentBitmap wall time
    INK_COMPOSE_OVERLAY("ink.compose_overlay"),   // drawOverlayOnlyToSurface wall time
    INK_MOVE_LATENCY("ink.move_latency"),         // MotionEvent.eventTime → overlay committed
    // Bigme daemon-path metrics — recorded by BigmeInkController.InputProxy
    // (per-MOVE/UP events on the binder thread) and HandwritingCanvasView
    // (mutation commits on the main thread).
    INK_DAEMON_DRAW_LINE("ink.daemon.draw_line"),       // Canvas.drawLine into ION buffer
    INK_DAEMON_INVALIDATE("ink.daemon.invalidate"),     // inValidate(rect, mode) round-trip
    INK_DAEMON_INVOKE_TOTAL("ink.daemon.invoke_total"), // full InputProxy.invoke hot path
    INK_DAEMON_DOWN_TO_PAINT("ink.daemon.down_to_paint"),// ACTION_DOWN → first inValidate (end-to-end)
    INK_DAEMON_DOWN_TO_FIRST_MOVE("ink.daemon.down_to_first_move"), // ACTION_DOWN → first MOVE arrival (daemon delivery + user pen speed)
    INK_DAEMON_FIRST_MOVE_TO_PAINT("ink.daemon.first_move_to_paint"), // first MOVE arrival → first inValidate (our processing only)
    INK_DAEMON_DISPATCH_LATENCY("ink.daemon.dispatch_latency"), // daemon event timestamp → our InputProxy.invoke entry (if clocks align)
    INK_COMMIT_MUTATION("ink.commit_mutation"),         // rebuild + sync + compose for snap/delete
}

/**
 * Lightweight performance counters with zero-allocation hot path.
 *
 * Each [PerfMetric] gets a ring buffer of the last [WINDOW_SIZE] timing
 * samples. The hot path ([time]) does one `nanoTime()` + two array writes.
 * Percentiles are computed lazily on [snapshot] (bug report time only).
 *
 * Thread-safe via synchronized blocks on each counter.
 */
object PerfCounters {

    private const val WINDOW_SIZE = 100

    @PublishedApi
    internal val counters = Array(PerfMetric.entries.size) { RingCounter(WINDOW_SIZE) }

    /**
     * Time a block and record the elapsed time. Returns the block's result.
     * Hot path: ~50ns overhead (nanoTime + array write).
     */
    inline fun <T> time(metric: PerfMetric, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsed = System.nanoTime() - start
        counters[metric.ordinal].record(elapsed)
        return result
    }

    /** Record a timing directly (for testing). */
    fun recordDirect(metric: PerfMetric, elapsedNanos: Long) {
        counters[metric.ordinal].record(elapsedNanos)
    }

    fun get(metric: PerfMetric): CounterSnapshot = counters[metric.ordinal].snapshot()

    fun snapshot(): Map<PerfMetric, CounterSnapshot> =
        PerfMetric.entries.associateWith { counters[it.ordinal].snapshot() }

    fun reset() {
        for (counter in counters) counter.reset()
    }
}

data class TimingSample(val elapsedMs: Long, val timestampMs: Long)

data class CounterSnapshot(
    val count: Long,
    val lastMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val samples: List<TimingSample>,
)

/**
 * Zero-allocation ring buffer for timing samples.
 * Uses primitive [LongArray] — no object creation on [record].
 */
@PublishedApi
internal class RingCounter(private val capacity: Int) {

    private val elapsedNanos = LongArray(capacity)
    private val timestampsMs = LongArray(capacity)
    private var writeIdx = 0
    private var totalCount = 0L

    @Synchronized
    fun record(elapsedNanos: Long) {
        val idx = writeIdx % capacity
        this.elapsedNanos[idx] = elapsedNanos
        this.timestampsMs[idx] = System.currentTimeMillis()
        writeIdx++
        totalCount++
    }

    @Synchronized
    fun snapshot(): CounterSnapshot {
        if (totalCount == 0L) return CounterSnapshot(0, 0, 0, 0, 0, emptyList())

        val size = minOf(totalCount.toInt(), capacity)
        val startIdx = if (totalCount <= capacity) 0 else writeIdx % capacity

        // Extract samples in chronological order
        val samples = ArrayList<TimingSample>(size)
        val sortedMs = LongArray(size)
        for (i in 0 until size) {
            val idx = (startIdx + i) % capacity
            val ms = elapsedNanos[idx] / 1_000_000
            samples.add(TimingSample(ms, timestampsMs[idx]))
            sortedMs[i] = ms
        }
        sortedMs.sort()

        return CounterSnapshot(
            count = totalCount,
            lastMs = samples.last().elapsedMs,
            p50Ms = sortedMs[size / 2],
            p95Ms = sortedMs[(size * 95L / 100).toInt().coerceAtMost(size - 1)],
            maxMs = sortedMs[size - 1],
            samples = samples,
        )
    }

    @Synchronized
    fun reset() {
        elapsedNanos.fill(0)
        timestampsMs.fill(0)
        writeIdx = 0
        totalCount = 0
    }
}
