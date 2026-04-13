package com.writer.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.writer.R

/**
 * Bottom overlay bar for audio playback controls.
 *
 * Shows play/pause button, progress bar, and elapsed/total time.
 * Lives in screen space as an overlay — doesn't affect canvas layout
 * or stroke coordinates.
 *
 * Controlled by [com.writer.audio.PlaybackController] via WritingActivity.
 */
class PlaybackOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val playPauseButton: ImageView
    private val progressBar: ProgressBar
    private val timeLabel: TextView

    var onPauseToggle: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_playback_overlay, this, true)
        playPauseButton = findViewById(R.id.playPauseButton)
        progressBar = findViewById(R.id.progressBar)
        timeLabel = findViewById(R.id.timeLabel)

        playPauseButton.setOnClickListener { onPauseToggle?.invoke() }
        visibility = View.GONE
    }

    fun show() {
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    fun setPaused(paused: Boolean) {
        playPauseButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        playPauseButton.contentDescription = if (paused) "Play" else "Pause"
    }

    fun updateProgress(positionMs: Long, durationMs: Long) {
        if (durationMs > 0) {
            progressBar.progress = ((positionMs * 1000) / durationMs).toInt()
        } else {
            progressBar.progress = 0
        }
        timeLabel.text = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
