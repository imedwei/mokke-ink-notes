package com.writer.view

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.writer.R
import com.writer.storage.SessionGrouping
import com.writer.storage.VersionHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom overlay for browsing version history. Shows a timeline SeekBar
 * for scrubbing and a session-grouped list of checkpoints.
 */
class VersionHistoryOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onCheckpointSelected: ((VersionHistory.Checkpoint) -> Unit)? = null
    var onRestoreConfirmed: ((VersionHistory.Checkpoint) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val seekBar: SeekBar
    private val timestampLabel: TextView
    private val restoreButton: TextView
    private val sessionContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var checkpoints: List<VersionHistory.Checkpoint> = emptyList()
    private var currentCheckpoint: VersionHistory.Checkpoint? = null
    private var currentIndex = -1
    private var pendingSeek: Runnable? = null
    private var touchDownX = 0f

    init {
        LayoutInflater.from(context).inflate(R.layout.view_version_history_overlay, this, true)
        seekBar = findViewById(R.id.timelineSeekBar)
        timestampLabel = findViewById(R.id.timestampLabel)
        restoreButton = findViewById(R.id.restoreButton)
        sessionContainer = findViewById(R.id.sessionContainer)
        visibility = View.GONE

        findViewById<View>(R.id.closeButton).setOnClickListener { onDismiss?.invoke() }

        restoreButton.setOnClickListener {
            val cp = currentCheckpoint ?: return@setOnClickListener
            AlertDialog.Builder(context)
                .setTitle("Restore version?")
                .setMessage("Restore to ${cp.label}? A new checkpoint will be saved first.")
                .setPositiveButton("Restore") { _, _ -> onRestoreConfirmed?.invoke(cp) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || checkpoints.isEmpty()) return
                val cp = checkpoints.getOrNull(progress) ?: return
                timestampLabel.text = formatTimestamp(cp.timestamp)
                // Debounce the preview callback
                pendingSeek?.let { handler.removeCallbacks(it) }
                val runnable = Runnable { selectCheckpoint(cp) }
                pendingSeek = runnable
                handler.postDelayed(runnable, 100)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Fire immediately on release if pending
                pendingSeek?.let {
                    handler.removeCallbacks(it)
                    it.run()
                    pendingSeek = null
                }
            }
        })

        // Tap-to-step: tap left of thumb = previous, tap right = next
        seekBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { touchDownX = event.x; false }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(event.x - touchDownX)
                    if (moved < dp(20) && checkpoints.size > 1) {
                        val thumbX = seekBar.thumb?.bounds?.centerX()?.toFloat()
                            ?: (seekBar.width / 2f)
                        if (event.x < thumbX) navigateTo(currentIndex - 1)
                        else navigateTo(currentIndex + 1)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    fun bind(checkpoints: List<VersionHistory.Checkpoint>) {
        this.checkpoints = checkpoints.sortedBy { it.timestamp }
        currentCheckpoint = null
        restoreButton.isEnabled = false
        restoreButton.setTextColor(0xFF999999.toInt())

        if (checkpoints.isEmpty()) {
            seekBar.max = 0
            seekBar.isEnabled = false
            timestampLabel.text = ""
            sessionContainer.removeAllViews()
            return
        }

        seekBar.isEnabled = true
        seekBar.max = this.checkpoints.size - 1
        currentIndex = this.checkpoints.size - 1
        seekBar.progress = currentIndex
        timestampLabel.text = formatTimestamp(this.checkpoints.last().timestamp)

        buildSessionList()
    }

    private fun navigateTo(index: Int) {
        val clamped = index.coerceIn(0, checkpoints.size - 1)
        currentIndex = clamped
        seekBar.progress = clamped
        timestampLabel.text = formatTimestamp(checkpoints[clamped].timestamp)
        selectCheckpoint(checkpoints[clamped])
    }

    private fun selectCheckpoint(cp: VersionHistory.Checkpoint) {
        currentCheckpoint = cp
        currentIndex = checkpoints.indexOf(cp).coerceAtLeast(0)
        restoreButton.isEnabled = true
        restoreButton.setTextColor(0xFF000000.toInt())
        onCheckpointSelected?.invoke(cp)
    }

    private fun buildSessionList() {
        sessionContainer.removeAllViews()
        val sessions = SessionGrouping.group(checkpoints)

        for ((sessionIdx, session) in sessions.withIndex()) {
            // Session header
            val header = TextView(context).apply {
                text = session.label
                textSize = 20f
                setTextColor(0xFF000000.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(24), dp(12), dp(24), dp(12))
                isClickable = true
                isFocusable = true
            }

            // Expandable checkpoint items container
            val itemsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

            // Populate individual checkpoint items
            for (cp in session.checkpoints) {
                val item = TextView(context).apply {
                    text = formatTimestamp(cp.timestamp)
                    textSize = 18f
                    setTextColor(0xFF333333.toInt())
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(48), dp(8), dp(24), dp(8))
                    setBackgroundResource(android.R.attr.selectableItemBackground.let {
                        val attrs = intArrayOf(it)
                        val ta = context.obtainStyledAttributes(attrs)
                        val res = ta.getResourceId(0, 0)
                        ta.recycle()
                        res
                    })
                    setOnClickListener {
                        val idx = checkpoints.indexOf(cp)
                        if (idx >= 0) seekBar.progress = idx
                        selectCheckpoint(cp)
                    }
                }
                itemsContainer.addView(item)
            }

            // Header click: toggle expand, or jump to end of session
            header.setOnClickListener {
                if (itemsContainer.visibility == View.GONE) {
                    itemsContainer.visibility = View.VISIBLE
                } else {
                    itemsContainer.visibility = View.GONE
                }
            }

            // Long press: jump seekbar to end of session
            header.setOnLongClickListener {
                val lastCp = session.checkpoints.last()
                val idx = checkpoints.indexOf(lastCp)
                if (idx >= 0) seekBar.progress = idx
                selectCheckpoint(lastCp)
                true
            }

            sessionContainer.addView(header)
            sessionContainer.addView(itemsContainer)

            // Divider between sessions
            if (sessionIdx < sessions.size - 1) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    }
                    setBackgroundColor(0xFFAAAAAA.toInt())
                }
                sessionContainer.addView(divider)
            }
        }
    }

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(ms))

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
