package com.writer.ui.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PerfCountersTest {

    @Before
    fun setUp() {
        PerfCounters.reset()
    }

    @Test
    fun `time increments count`() {
        PerfCounters.time(PerfMetric.PREVIEW_FORK) { Thread.sleep(1) }
        PerfCounters.time(PerfMetric.PREVIEW_FORK) { Thread.sleep(1) }
        PerfCounters.time(PerfMetric.PREVIEW_FORK) { Thread.sleep(1) }

        val snap = PerfCounters.get(PerfMetric.PREVIEW_FORK)
        assertEquals(3, snap.count)
    }

    @Test
    fun `time records elapsed`() {
        PerfCounters.time(PerfMetric.PREVIEW_READ) { Thread.sleep(10) }

        val snap = PerfCounters.get(PerfMetric.PREVIEW_READ)
        assertTrue("lastMs should be >= 10 (was ${snap.lastMs})", snap.lastMs >= 10)
    }

    @Test
    fun `time returns block result`() {
        val result = PerfCounters.time(PerfMetric.SAVE_SYNC) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `p50 and p95 computed correctly`() {
        // Record 100 samples with values 1..100 ms (simulated via nanos)
        for (i in 1..100) {
            PerfCounters.recordDirect(PerfMetric.PREVIEW_DRAW, i * 1_000_000L)
        }

        val snap = PerfCounters.get(PerfMetric.PREVIEW_DRAW)
        assertEquals(100, snap.count)
        // p50 should be around 50ms
        assertTrue("p50 should be ~50ms (was ${snap.p50Ms})", snap.p50Ms in 45..55)
        // p95 should be around 95ms
        assertTrue("p95 should be ~95ms (was ${snap.p95Ms})", snap.p95Ms in 90..100)
        assertEquals(100, snap.maxMs)
    }

    @Test
    fun `ring buffer wraps at capacity`() {
        // Record 150 samples into a 100-capacity buffer
        for (i in 1..150) {
            PerfCounters.recordDirect(PerfMetric.SAVE_INCREMENTAL, i * 1_000_000L)
        }

        val snap = PerfCounters.get(PerfMetric.SAVE_INCREMENTAL)
        assertEquals(150, snap.count) // total count tracks all
        // Window should only have last 100 samples (51..150)
        assertEquals(100, snap.samples.size)
        assertEquals(51, snap.samples.first().elapsedMs)
    }

    @Test
    fun `concurrent access is safe`() {
        val threads = 4
        val samplesPerThread = 1000
        val latch = CountDownLatch(threads)

        for (t in 0 until threads) {
            Thread {
                for (i in 0 until samplesPerThread) {
                    PerfCounters.time(PerfMetric.SAVE_SYNC) { /* no-op */ }
                }
                latch.countDown()
            }.start()
        }

        latch.await(10, TimeUnit.SECONDS)
        val snap = PerfCounters.get(PerfMetric.SAVE_SYNC)
        assertEquals(threads.toLong() * samplesPerThread, snap.count)
    }

    @Test
    fun `snapshot returns all metrics`() {
        PerfCounters.time(PerfMetric.PREVIEW_FORK) {}
        PerfCounters.time(PerfMetric.SAVE_SYNC) {}

        val all = PerfCounters.snapshot()
        assertTrue(all.containsKey(PerfMetric.PREVIEW_FORK))
        assertTrue(all.containsKey(PerfMetric.SAVE_SYNC))
        assertEquals(1, all[PerfMetric.PREVIEW_FORK]!!.count)
        assertEquals(1, all[PerfMetric.SAVE_SYNC]!!.count)
    }

    @Test
    fun `reset clears all counters`() {
        PerfCounters.time(PerfMetric.PREVIEW_FORK) {}
        PerfCounters.reset()

        val snap = PerfCounters.get(PerfMetric.PREVIEW_FORK)
        assertEquals(0, snap.count)
    }

    @Test
    fun `recordByLabel stores under that label`() {
        PerfCounters.recordByLabel("ink.pen.kernel_to_paint", 5_000_000L)
        PerfCounters.recordByLabel("ink.pen.kernel_to_paint", 7_000_000L)

        val rows = PerfCounters.unifiedSnapshot().filter { it.label == "ink.pen.kernel_to_paint" }
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0].count)
    }

    @Test
    fun `unifiedSnapshot includes both enum-keyed and label-keyed metrics`() {
        PerfCounters.time(PerfMetric.PREVIEW_FORK) {}
        PerfCounters.recordByLabel("ink.event.handler", 5_000_000L)

        val labels = PerfCounters.unifiedSnapshot().map { it.label }.toSet()
        assertTrue("enum-keyed missing: $labels", labels.contains(PerfMetric.PREVIEW_FORK.label))
        assertTrue("label-keyed missing: $labels", labels.contains("ink.event.handler"))
    }

    @Test
    fun `unifiedSnapshot omits zero-count metrics`() {
        // Touch one enum-keyed metric and one label-keyed metric; everything else stays at zero.
        PerfCounters.time(PerfMetric.PREVIEW_FORK) {}
        PerfCounters.recordByLabel("ink.event.handler", 1_000L)

        val rows = PerfCounters.unifiedSnapshot()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.count > 0L })
    }

    @Test
    fun `reset clears label-keyed metrics too`() {
        PerfCounters.recordByLabel("ink.pen.kernel_to_paint", 5_000_000L)
        PerfCounters.reset()

        assertTrue(PerfCounters.unifiedSnapshot().isEmpty())
    }

    @Test
    fun `installing inksdk PerfSink routes recordings into mokke`() {
        // This is the production wiring (set up in WriterApplication.onCreate).
        // Once installed, every inksdk PerfMetric recording flows through to
        // mokke's label-keyed storage and appears in unifiedSnapshot.
        com.inksdk.ink.PerfCounters.sink = com.inksdk.ink.PerfSink { label, nanos ->
            PerfCounters.recordByLabel(label, nanos)
        }
        try {
            com.inksdk.ink.PerfCounters.recordDirect(com.inksdk.ink.PerfMetric.PEN_KERNEL_TO_PAINT, 5_000_000L)

            val labels = PerfCounters.unifiedSnapshot().map { it.label }.toSet()
            assertTrue("inksdk metric missing: $labels", labels.contains("ink.pen.kernel_to_paint"))
        } finally {
            com.inksdk.ink.PerfCounters.sink = com.inksdk.ink.DefaultSink
        }
    }
}
