package com.writer.view.ink

import android.graphics.Rect
import android.view.SurfaceView

/**
 * Placeholder controller used when no hardware ink pipeline is available
 * (emulator, generic Android, Bigme without a vendor SDK). The host View
 * falls back to MotionEvent + Canvas rendering.
 */
object NoopInkController : InkController {
    override val isActive: Boolean = false
    override val consumesMotionEvents: Boolean = false
    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean = false
    override fun setStrokeStyle(widthPx: Float, color: Int) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
    override fun detach() = Unit
}
