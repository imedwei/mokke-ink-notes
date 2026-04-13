package com.writer.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [PlaybackController] state machine.
 *
 * Verification:
 * 1. Tap word → plays, bar visible
 * 2. Tap another word same block → seeks
 * 3. Tap word different block same recording → seeks
 * 4. Tap pause → pauses, bar stays
 * 5. Tap play → resumes
 * 6. Tap outside → stops, bar gone
 * 7. Playback ends → stops, bar gone
 * 8. Progress tracked correctly
 */
class PlaybackControllerTest {

    private lateinit var controller: PlaybackController
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        controller = PlaybackController(
            onPlay = { file, seekMs -> events.add("play:$file@${seekMs}ms") },
            onSeek = { seekMs -> events.add("seek:${seekMs}ms") },
            onPause = { events.add("pause") },
            onResume = { events.add("resume") },
            onStop = { events.add("stop") }
        )
        events.clear()
    }

    // ── 1. Tap word → plays, bar appears ────────────────────────────────

    @Test
    fun `tap word from IDLE starts playback`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1500, blockId = "b1")
        assertEquals(PlaybackController.State.PLAYING, controller.state)
        assertEquals("play:rec-001.ogg@1500ms", events.last())
    }

    @Test
    fun `playing block id is set after tap`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1500, blockId = "b1")
        assertEquals("b1", controller.playingBlockId)
    }

    @Test
    fun `bar is visible when playing`() {
        assertFalse(controller.isBarVisible)
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        assertTrue(controller.isBarVisible)
    }

    // ── 2. Tap another word same block → seeks ──────────────────────────

    @Test
    fun `tap different word in same block seeks without stopping`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1500, blockId = "b1")
        events.clear()

        controller.onWordTapped("rec-001.ogg", seekMs = 3000, blockId = "b1")
        assertEquals(PlaybackController.State.PLAYING, controller.state)
        assertEquals("seek:3000ms", events.last())
        assertFalse(events.any { it.startsWith("stop") })
    }

    @Test
    fun `seek in same block keeps bar visible`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1500, blockId = "b1")
        controller.onWordTapped("rec-001.ogg", seekMs = 3000, blockId = "b1")
        assertTrue(controller.isBarVisible)
        assertEquals("b1", controller.playingBlockId)
    }

    // ── 3. Tap word different block same recording → seeks ──────────────

    @Test
    fun `tap word in different block same recording seeks`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1500, blockId = "b1")
        events.clear()

        controller.onWordTapped("rec-001.ogg", seekMs = 5000, blockId = "b2")
        assertEquals(PlaybackController.State.PLAYING, controller.state)
        assertEquals("seek:5000ms", events.last())
        assertEquals("b2", controller.playingBlockId)
    }

    @Test
    fun `tap word in different block different recording restarts`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1500, blockId = "b1")
        events.clear()

        controller.onWordTapped("rec-002.ogg", seekMs = 500, blockId = "b3")
        assertEquals(PlaybackController.State.PLAYING, controller.state)
        assertTrue(events.any { it == "stop" })
        assertTrue(events.any { it == "play:rec-002.ogg@500ms" })
    }

    // ── 4. Tap pause → pauses ───────────────────────────────────────────

    @Test
    fun `pause from PLAYING transitions to PAUSED`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        events.clear()

        controller.onPauseToggle()
        assertEquals(PlaybackController.State.PAUSED, controller.state)
        assertEquals("pause", events.last())
    }

    @Test
    fun `bar stays visible when paused`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onPauseToggle()
        assertTrue(controller.isBarVisible)
    }

    @Test
    fun `isPaused is true when paused`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        assertFalse(controller.isPaused)
        controller.onPauseToggle()
        assertTrue(controller.isPaused)
    }

    // ── 5. Tap play (when paused) → resumes ─────────────────────────────

    @Test
    fun `resume from PAUSED transitions to PLAYING`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onPauseToggle()
        events.clear()

        controller.onPauseToggle()
        assertEquals(PlaybackController.State.PLAYING, controller.state)
        assertEquals("resume", events.last())
    }

    @Test
    fun `tap word while paused seeks and resumes`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onPauseToggle()
        events.clear()

        controller.onWordTapped("rec-001.ogg", seekMs = 4000, blockId = "b1")
        assertEquals(PlaybackController.State.PLAYING, controller.state)
        assertTrue(events.any { it == "seek:4000ms" })
    }

    // ── 6. Tap outside → stops, bar disappears ──────────────────────────

    @Test
    fun `tap outside while PLAYING stops`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        events.clear()

        controller.onTapOutside()
        assertEquals(PlaybackController.State.IDLE, controller.state)
        assertEquals("stop", events.last())
    }

    @Test
    fun `tap outside hides bar`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onTapOutside()
        assertFalse(controller.isBarVisible)
        assertNull(controller.playingBlockId)
    }

    @Test
    fun `tap outside while PAUSED stops`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onPauseToggle()
        events.clear()

        controller.onTapOutside()
        assertEquals(PlaybackController.State.IDLE, controller.state)
        assertEquals("stop", events.last())
        assertFalse(controller.isBarVisible)
    }

    @Test
    fun `tap outside while IDLE is no-op`() {
        controller.onTapOutside()
        assertEquals(PlaybackController.State.IDLE, controller.state)
        assertTrue(events.isEmpty())
    }

    // ── 7. Playback ends → stops, bar disappears ────────────────────────

    @Test
    fun `playback completion transitions to IDLE`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        events.clear()

        controller.onPlaybackCompleted()
        assertEquals(PlaybackController.State.IDLE, controller.state)
        assertFalse(controller.isBarVisible)
        assertNull(controller.playingBlockId)
    }

    // ── 8. Progress tracked correctly ───────────────────────────────────

    @Test
    fun `progress updates are tracked`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onProgressUpdate(positionMs = 3200, durationMs = 8100)
        assertEquals(3200L, controller.positionMs)
        assertEquals(8100L, controller.durationMs)
    }

    @Test
    fun `progress is zero when idle`() {
        assertEquals(0L, controller.positionMs)
        assertEquals(0L, controller.durationMs)
    }

    @Test
    fun `progress resets on stop`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 0, blockId = "b1")
        controller.onProgressUpdate(3200, 8100)
        controller.onTapOutside()
        assertEquals(0L, controller.positionMs)
        assertEquals(0L, controller.durationMs)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `pause toggle from IDLE is no-op`() {
        controller.onPauseToggle()
        assertEquals(PlaybackController.State.IDLE, controller.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `rapid word taps only seek, no stop-restart churn`() {
        controller.onWordTapped("rec-001.ogg", seekMs = 1000, blockId = "b1")
        events.clear()

        controller.onWordTapped("rec-001.ogg", seekMs = 2000, blockId = "b1")
        controller.onWordTapped("rec-001.ogg", seekMs = 3000, blockId = "b1")
        controller.onWordTapped("rec-001.ogg", seekMs = 4000, blockId = "b1")

        assertEquals(3, events.size)
        assertTrue(events.all { it.startsWith("seek:") })
    }
}
