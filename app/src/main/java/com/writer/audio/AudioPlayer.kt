package com.writer.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Simple audio player wrapping [MediaPlayer] for TextBlock audio playback.
 *
 * Supports play from a timestamp, pause/resume, seek, and periodic
 * position callbacks for tracking which TextBlock is active.
 */
class AudioPlayer {

    private val tag = "AudioPlayer"
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var positionRunnable: Runnable? = null

    var isPlaying: Boolean = false
        private set

    /** Called periodically (~250ms) with the current playback position in ms. */
    var onPositionChanged: ((Long) -> Unit)? = null

    /** Called when playback reaches the end. */
    var onCompleted: (() -> Unit)? = null

    /**
     * Start playback from [startMs] in the given audio [file].
     * If already playing a different file, stops first.
     */
    fun play(file: File, startMs: Long = 0) {
        stop()

        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                isPlaying = false
                stopPositionUpdates()
                onCompleted?.invoke()
            }
            mp.prepare()
            if (startMs > 0) {
                // Seek before start using SEEK_CLOSEST for precise positioning (API 26+)
                mp.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                // Wait for seek to complete before starting playback
                mp.setOnSeekCompleteListener { seekMp ->
                    seekMp.setOnSeekCompleteListener(null)
                    seekMp.start()
                    player = seekMp
                    isPlaying = true
                    startPositionUpdates()
                    Log.i(tag, "Playing ${file.name} from ${startMs}ms (seeked, duration=${seekMp.duration}ms)")
                }
            } else {
                mp.start()
                player = mp
                isPlaying = true
                startPositionUpdates()
                Log.i(tag, "Playing ${file.name} from 0ms (duration=${mp.duration}ms)")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to play ${file.name}", e)
            mp.release()
        }
    }

    fun pause() {
        player?.pause()
        isPlaying = false
        stopPositionUpdates()
    }

    fun resume() {
        player?.start()
        isPlaying = true
        startPositionUpdates()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
        onPositionChanged?.invoke(ms)
    }

    fun stop() {
        stopPositionUpdates()
        player?.stop()
        player?.release()
        player = null
        isPlaying = false
    }

    /** Current playback position in ms, or 0 if not playing. */
    val currentPositionMs: Long
        get() = try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        val runnable = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    onPositionChanged?.invoke(currentPositionMs)
                    handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
                }
            }
        }
        positionRunnable = runnable
        handler.postDelayed(runnable, POSITION_UPDATE_INTERVAL_MS)
    }

    private fun stopPositionUpdates() {
        positionRunnable?.let { handler.removeCallbacks(it) }
        positionRunnable = null
    }

    fun release() {
        stop()
        onPositionChanged = null
        onCompleted = null
    }

    companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 250L
    }
}
