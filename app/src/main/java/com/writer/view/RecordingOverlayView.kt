package com.writer.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import com.writer.R

/**
 * Bottom overlay bar shown during recording.
 * Contains an auto-stop checkbox that lets the user toggle between
 * memo mode (auto-stops after silence) and lecture mode (continuous recording).
 */
class RecordingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val autoStopCheckbox: CheckBox

    var onAutoStopChanged: ((Boolean) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_recording_overlay, this, true)
        autoStopCheckbox = findViewById(R.id.autoStopCheckbox)
        autoStopCheckbox.setOnCheckedChangeListener { _, isChecked ->
            onAutoStopChanged?.invoke(isChecked)
        }
        visibility = View.GONE
    }

    fun show(autoStopEnabled: Boolean) {
        autoStopCheckbox.isChecked = autoStopEnabled
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }
}
