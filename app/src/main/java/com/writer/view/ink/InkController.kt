package com.writer.view.ink

import android.graphics.Rect
import android.view.SurfaceView

/**
 * Abstracts a low-latency ink overlay — a hardware pen pipeline that paints
 * strokes directly at the e-ink controller's native refresh rate, bypassing
 * the Android view system.
 *
 * On Onyx Boox devices this wraps [com.onyx.android.sdk.pen.TouchHelper].
 * On devices without a supported vendor SDK (emulator, generic Android, and
 * currently Bigme) a no-op implementation is selected and the host View
 * falls back to MotionEvent + Canvas rendering.
 */
interface InkController {

    /**
     * True iff the overlay is attached and currently painting pen strokes.
     * When true, the host View must NOT draw the in-progress stroke itself —
     * the overlay owns that pixel budget.
     */
    val isActive: Boolean

    /**
     * True iff the overlay swallows MotionEvents and delivers strokes via
     * [StrokeCallback] instead. Onyx's TouchHelper does this; Bigme's xrz
     * pipeline does not — on Bigme the daemon rasterizes at the framebuffer
     * while MotionEvents still flow through [android.view.View.onTouchEvent].
     */
    val consumesMotionEvents: Boolean

    /**
     * Attach the overlay to [view] with [limit] as the visible/allowed drawing
     * rect (in view-local pixels). Returns true iff the overlay is now active.
     * On false, the caller should use its MotionEvent fallback.
     */
    fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean

    /** Pen stroke style — safe to call before or after [attach]. */
    fun setStrokeStyle(widthPx: Float, color: Int)

    /**
     * Pause/resume delivery without detaching. Use before surface-repaint
     * operations (finger scroll, full redraws, screen-mode refreshes).
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Invalidate any daemon-held overlay pixels so the next host compose
     * is visible on the EPD. Needed after a stroke is REPLACED client-side
     * (snap, auto-classify) — the daemon's overlay still holds the original
     * raw pixels until something forces it to release. Default: no-op.
     */
    fun invalidateOverlay() = Unit

    /**
     * Detach — release the raw-drawing session. After this, [isActive] is
     * false. [attach] must be called again to resume low-latency ink.
     */
    fun detach()
}

/**
 * Pen-event sink, delivered in view-local coordinates. The host View owns
 * scroll-offset translation to document space.
 */
interface StrokeCallback {
    fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long)
    fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long)
    fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long)
}
