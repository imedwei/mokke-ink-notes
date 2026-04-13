package com.writer.audio

/**
 * State machine for audio playback of transcribed text blocks.
 *
 * Manages transitions between IDLE, PLAYING, and PAUSED states.
 * Delegates actual audio operations to callbacks — no Android dependencies,
 * fully testable.
 *
 * UX contract:
 * - Tap any word → instant play/seek (no pause toggle)
 * - Bottom overlay bar shows progress while playing/paused
 * - Tap outside text blocks → stop
 */
class PlaybackController(
    private val onPlay: (file: String, seekMs: Long) -> Unit,
    private val onSeek: (seekMs: Long) -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit
) {

    enum class State { IDLE, PLAYING, PAUSED }

    var state: State = State.IDLE
        private set

    var playingBlockId: String? = null
        private set

    var positionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    private var currentFile: String? = null

    val isBarVisible: Boolean get() = state != State.IDLE
    val isPaused: Boolean get() = state == State.PAUSED

    /**
     * User tapped a word in a text block with audio.
     * From any state: start or seek to the word's position.
     */
    fun onWordTapped(audioFile: String, seekMs: Long, blockId: String) {
        when {
            // IDLE → start playing
            state == State.IDLE -> {
                currentFile = audioFile
                playingBlockId = blockId
                state = State.PLAYING
                onPlay(audioFile, seekMs)
            }
            // PLAYING or PAUSED, same file → seek
            currentFile == audioFile -> {
                playingBlockId = blockId
                state = State.PLAYING
                onSeek(seekMs)
                if (isPaused) onResume()
            }
            // PLAYING or PAUSED, different file → stop old, play new
            else -> {
                onStop()
                currentFile = audioFile
                playingBlockId = blockId
                state = State.PLAYING
                onPlay(audioFile, seekMs)
            }
        }
    }

    /** User tapped the pause/play toggle in the overlay bar. */
    fun onPauseToggle() {
        when (state) {
            State.PLAYING -> {
                state = State.PAUSED
                onPause()
            }
            State.PAUSED -> {
                state = State.PLAYING
                onResume()
            }
            State.IDLE -> {} // no-op
        }
    }

    /** User tapped outside any text block or playback bar. */
    fun onTapOutside() {
        if (state == State.IDLE) return
        reset()
        onStop()
    }

    /** Audio reached the end naturally. */
    fun onPlaybackCompleted() {
        reset()
    }

    /** Update progress from the audio player. */
    fun onProgressUpdate(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        this.durationMs = durationMs
    }

    private fun reset() {
        state = State.IDLE
        currentFile = null
        playingBlockId = null
        positionMs = 0L
        durationMs = 0L
    }
}
