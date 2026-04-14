package com.writer.storage

import org.automerge.ChangeHashFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {

    private fun checkpoint(label: String, timestampMs: Long) = VersionHistory.Checkpoint(
        label = label,
        timestamp = timestampMs,
        heads = arrayOf(ChangeHashFactory.create(ByteArray(32) { timestampMs.toByte() })),
    )

    @Test
    fun `empty checkpoints returns empty sessions`() {
        val sessions = SessionGrouping.group(emptyList())
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `single checkpoint returns one session`() {
        val cp = checkpoint("cp1", 1000L)
        val sessions = SessionGrouping.group(listOf(cp))
        assertEquals(1, sessions.size)
        assertEquals(1, sessions[0].checkpoints.size)
    }

    @Test
    fun `consecutive checkpoints within 30min are one session`() {
        val base = System.currentTimeMillis()
        val cps = (0 until 10).map { i ->
            checkpoint("cp$i", base + i * 60_000L) // 1 min apart
        }
        val sessions = SessionGrouping.group(cps)
        assertEquals(1, sessions.size)
        assertEquals(10, sessions[0].checkpoints.size)
    }

    @Test
    fun `gap over 30min splits into two sessions`() {
        val base = System.currentTimeMillis()
        val session1 = (0 until 5).map { i ->
            checkpoint("s1-cp$i", base + i * 60_000L)
        }
        val session2 = (0 until 3).map { i ->
            checkpoint("s2-cp$i", base + 45 * 60_000L + i * 60_000L) // 45 min gap
        }
        val sessions = SessionGrouping.group(session1 + session2)
        assertEquals(2, sessions.size)
        assertEquals(5, sessions[0].checkpoints.size)
        assertEquals(3, sessions[1].checkpoints.size)
    }

    @Test
    fun `three sessions with varying gaps`() {
        val base = System.currentTimeMillis()
        val cps = listOf(
            checkpoint("a", base),
            checkpoint("b", base + 5 * 60_000L),       // 5 min
            checkpoint("c", base + 120 * 60_000L),      // 2 hour gap
            checkpoint("d", base + 125 * 60_000L),      // 5 min
            checkpoint("e", base + 300 * 60_000L),      // 3 hour gap
        )
        val sessions = SessionGrouping.group(cps)
        assertEquals(3, sessions.size)
        assertEquals(2, sessions[0].checkpoints.size)
        assertEquals(2, sessions[1].checkpoints.size)
        assertEquals(1, sessions[2].checkpoints.size)
    }

    @Test
    fun `session label includes checkpoint count`() {
        val base = System.currentTimeMillis()
        val cps = (0 until 47).map { i ->
            checkpoint("cp$i", base + i * 5_000L) // 5 sec apart
        }
        val sessions = SessionGrouping.group(cps)
        assertEquals(1, sessions.size)
        assertTrue(
            "label should contain count: ${sessions[0].label}",
            sessions[0].label.contains("47")
        )
    }

    @Test
    fun `checkpoints at exact 30min boundary stay in same session`() {
        val base = System.currentTimeMillis()
        val cps = listOf(
            checkpoint("a", base),
            checkpoint("b", base + 30 * 60_000L), // exactly 30 min
        )
        val sessions = SessionGrouping.group(cps)
        assertEquals(1, sessions.size)
        assertEquals(2, sessions[0].checkpoints.size)
    }

    @Test
    fun `checkpoints just over 30min boundary split`() {
        val base = System.currentTimeMillis()
        val cps = listOf(
            checkpoint("a", base),
            checkpoint("b", base + 30 * 60_000L + 1), // 30 min + 1ms
        )
        val sessions = SessionGrouping.group(cps)
        assertEquals(2, sessions.size)
    }
}
