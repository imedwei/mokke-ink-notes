package com.writer.ui.writing

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView

/**
 * Manages a manual rotation button that appears when system auto-rotate is OFF.
 * Lets users toggle between portrait and landscape to access the Cornell Notes
 * dual-column view without changing system settings.
 *
 * Core logic is in [OrientationLogic] for testability.
 */
class OrientationManager(
    private val activity: Activity,
    private val rotateButton: ImageView
) {
    private val logic = OrientationLogic(
        host = object : OrientationLogic.Host {
            override val isAutoRotateOn: Boolean
                get() = Settings.System.getInt(
                    activity.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION, 0
                ) == 1

            override val isLandscape: Boolean
                get() = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            override fun setOrientation(orientation: Int) {
                activity.requestedOrientation = orientation
            }

            override fun setButtonVisible(visible: Boolean) {
                rotateButton.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    )

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            logic.onAutoRotateSettingChanged()
        }
    }

    fun start() {
        activity.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            settingsObserver
        )
        logic.updateButtonVisibility()
    }

    fun stop() {
        activity.contentResolver.unregisterContentObserver(settingsObserver)
    }

    fun toggleOrientation() = logic.toggleOrientation()
    fun updateButtonVisibility() = logic.updateButtonVisibility()
}

/**
 * Pure logic for orientation management, testable without Android dependencies.
 */
class OrientationLogic(private val host: Host) {

    interface Host {
        val isAutoRotateOn: Boolean
        val isLandscape: Boolean
        fun setOrientation(orientation: Int)
        fun setButtonVisible(visible: Boolean)
    }

    internal var isOrientationLocked = false
        private set

    fun toggleOrientation() {
        if (host.isLandscape) {
            host.setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        } else {
            host.setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        }
        isOrientationLocked = true
    }

    fun updateButtonVisibility() {
        host.setButtonVisible(!host.isAutoRotateOn)
    }

    fun onAutoRotateSettingChanged() {
        updateButtonVisibility()
        if (host.isAutoRotateOn && isOrientationLocked) {
            releaseOrientationLock()
        }
    }

    fun releaseOrientationLock() {
        host.setOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        isOrientationLocked = false
    }
}
