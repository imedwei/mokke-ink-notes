package com.writer.ui.writing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the debounce pattern used by diagram text recognition.
 *
 * The actual WritingCoordinator is too tightly coupled to Android to test directly,
 * so we test the debounce mechanism in isolation — same pattern, same constants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagramRecognizeDebounceTest {

    companion object {
        private const val DELAY_MS = 600L
    }

    /** Simulates the debounce wrapper from WritingCoordinator. */
    private class Debouncer(private val delayMs: Long = DELAY_MS) {
        val jobs = mutableMapOf<Int, Job>()
        var executionCount = 0
        val executedAreas = mutableListOf<Int>()

        fun schedule(areaKey: Int, immediate: Boolean = false, scope: kotlinx.coroutines.CoroutineScope) {
            jobs[areaKey]?.cancel()
            if (immediate) {
                execute(areaKey)
                return
            }
            jobs[areaKey] = scope.launch {
                delay(delayMs)
                execute(areaKey)
            }
        }

        private fun execute(areaKey: Int) {
            executionCount++
            executedAreas.add(areaKey)
        }
    }

    @Test
    fun `rapid calls are debounced to single execution`() = runTest {
        val debouncer = Debouncer()

        // 5 rapid calls for the same area
        repeat(5) {
            debouncer.schedule(areaKey = 3, scope = this)
        }

        // Before delay expires — nothing executed
        advanceTimeBy(500)
        assertEquals("Should not have executed yet", 0, debouncer.executionCount)

        // After delay
        advanceTimeBy(200)
        assertEquals("Should execute exactly once", 1, debouncer.executionCount)
        assertEquals("Should be for area 3", 3, debouncer.executedAreas[0])
    }

    @Test
    fun `immediate flag skips delay`() = runTest {
        val debouncer = Debouncer()

        debouncer.schedule(areaKey = 5, immediate = true, scope = this)

        // Should execute immediately without advancing time
        assertEquals("Should execute immediately", 1, debouncer.executionCount)
        assertEquals("Should be for area 5", 5, debouncer.executedAreas[0])
    }

    @Test
    fun `new call cancels previous pending`() = runTest {
        val debouncer = Debouncer()

        // First call
        debouncer.schedule(areaKey = 2, scope = this)
        advanceTimeBy(300) // halfway

        // Second call — cancels first
        debouncer.schedule(areaKey = 2, scope = this)
        advanceTimeBy(300) // 300ms into second call, first would have fired at 600ms
        assertEquals("First should have been cancelled", 0, debouncer.executionCount)

        advanceTimeBy(400) // 700ms into second call — past its 600ms delay
        assertEquals("Second should have fired", 1, debouncer.executionCount)
    }

    @Test
    fun `different areas are independent`() = runTest {
        val debouncer = Debouncer()

        debouncer.schedule(areaKey = 1, scope = this)
        advanceTimeBy(200)
        debouncer.schedule(areaKey = 2, scope = this)

        // Area 1 fires at 600ms, area 2 fires at 800ms
        advanceTimeBy(500) // t=700
        assertEquals("Area 1 should have fired", 1, debouncer.executionCount)
        assertEquals("Should be area 1", 1, debouncer.executedAreas[0])

        advanceTimeBy(200) // t=900
        assertEquals("Area 2 should have fired", 2, debouncer.executionCount)
        assertEquals("Should be area 2", 2, debouncer.executedAreas[1])
    }

    @Test
    fun `cancelling area does not affect other areas`() = runTest {
        val debouncer = Debouncer()

        debouncer.schedule(areaKey = 1, scope = this)
        debouncer.schedule(areaKey = 2, scope = this)

        // Cancel area 1 by re-scheduling then cancelling the job
        debouncer.jobs[1]?.cancel()

        advanceTimeBy(700)
        assertEquals("Only area 2 should fire", 1, debouncer.executionCount)
        assertEquals("Should be area 2", 2, debouncer.executedAreas[0])
    }
}
