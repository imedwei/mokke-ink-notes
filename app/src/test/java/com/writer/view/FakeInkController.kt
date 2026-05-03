package com.writer.view

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceView
import com.inksdk.ink.InkController
import com.inksdk.ink.StrokeCallback

/**
 * Test double for [InkController] that records every [syncOverlay] call with a
 * pixel-level snapshot of the bitmap as it appeared at the moment of the call.
 * The most recent snapshot represents the "ION buffer" state — tests assert
 * that it matches the host's [com.writer.view.HandwritingCanvasView] content
 * bitmap after any mutation.
 *
 * Unlike [com.inksdk.ink.BigmeInkController], which writes into an ION-backed
 * Canvas via reflection, this controller just deep-copies the bitmap so
 * assertions are stable and side-effect-free.
 */
class FakeInkController(
    override val isActive: Boolean = true,
    override val consumesMotionEvents: Boolean = true,
) : InkController {

    data class SyncCall(
        val bitmap: Bitmap,
        val region: Rect?,
        val force: Boolean,
    )

    private val _calls = mutableListOf<SyncCall>()
    val calls: List<SyncCall> get() = _calls.toList()

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean = true

    override fun setStrokeStyle(widthPx: Float, color: Int) = Unit

    override fun setEnabled(enabled: Boolean) = Unit

    override fun syncOverlay(bitmap: Bitmap, region: Rect?, force: Boolean) {
        _calls.add(SyncCall(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false), region?.let { Rect(it) }, force))
    }

    override fun detach() = Unit

    /** Latest blitted bitmap, i.e. the current ION buffer contents. */
    fun ionBuffer(): Bitmap? = _calls.lastOrNull()?.bitmap

    /** Any sync call since [mark] with the given predicate. */
    fun anySyncSince(markIndex: Int, predicate: (SyncCall) -> Boolean): Boolean =
        _calls.subList(markIndex, _calls.size).any(predicate)

    fun mark(): Int = _calls.size

    fun clearCalls() { _calls.clear() }
}
