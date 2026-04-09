package com.writer.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.File

/**
 * Audio player using Media3 ExoPlayer for TextBlock playback.
 *
 * ExoPlayer supports seeking in WebM/Opus files via constant-bitrate
 * seeking, unlike MediaPlayer which requires Cues in the container.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlayer(private val context: Context) {

    private val tag = "AudioPlayer"
    private val handler = Handler(Looper.getMainLooper())
    private var positionRunnable: Runnable? = null
    private var player: ExoPlayer? = null

    var isPlaying: Boolean = false
        private set

    /** Called periodically (~250ms) with the current playback position in ms. */
    var onPositionChanged: ((Long) -> Unit)? = null

    /** Called when playback reaches the end. */
    var onCompleted: (() -> Unit)? = null

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    isPlaying = false
                    stopPositionUpdates()
                    onCompleted?.invoke()
                }
            }
        })
        player = p
        return p
    }

    /**
     * Start playback from [startMs] in the given audio [file].
     */
    fun play(file: File, startMs: Long = 0) {
        val p = ensurePlayer()
        val mediaItem = MediaItem.fromUri(file.toURI().toString())
        p.setMediaItem(mediaItem, startMs)
        p.prepare()
        p.playWhenReady = true
        isPlaying = true
        startPositionUpdates()
        Log.i(tag, "Playing ${file.name} from ${startMs}ms")
    }

    fun pause() {
        player?.pause()
        isPlaying = false
        stopPositionUpdates()
    }

    fun resume() {
        player?.play()
        isPlaying = true
        startPositionUpdates()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        onPositionChanged?.invoke(ms)
    }

    fun stop() {
        stopPositionUpdates()
        player?.stop()
        isPlaying = false
    }

    /** Current playback position in ms, or 0 if not playing. */
    val currentPositionMs: Long
        get() = try { player?.currentPosition ?: 0L } catch (_: Exception) { 0L }

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
        player?.release()
        player = null
        onPositionChanged = null
        onCompleted = null
    }

    companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 250L
    }
}
