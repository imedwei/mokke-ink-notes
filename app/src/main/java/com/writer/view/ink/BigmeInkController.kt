package com.writer.view.ink

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import dalvik.system.PathClassLoader
import java.io.File

/**
 * Low-latency ink for Bigme e-ink devices via the undocumented `xrz` framework.
 *
 * Architecture: Bigme ships a root native daemon at `/system/bin/handwrittenservice`
 * that reads `/dev/input/event0` directly and commits stroke pixels to the EPD
 * framebuffer, bypassing the Android input/render pipelines. It activates for
 * any SurfaceView that the HWC composer has tagged with `HANDWRITTEN_FLAG`.
 *
 * The tag is applied when we call
 * `xrz.framework.manager.XrzEinkManager.setRefreshModeForSurfaceView(view, 1029)`
 * where 1029 = `EinkRefreshMode.EINK_HANDWRITE_MODE`. All of this is done
 * reflectively — the xrz classes exist only on Bigme firmware.
 *
 * Crucially, unlike Onyx's TouchHelper, the daemon does NOT swallow MotionEvents.
 * The app continues to receive ACTION_DOWN/MOVE/UP through normal
 * [android.view.View.onTouchEvent] — those are used for stroke capture and
 * recognition. The daemon just provides the visible ink.
 *
 * Caveat: empirically Bigme's HWC only tags non-translucent, non-fullscreen
 * surfaces with `HANDWRITTEN_FLAG`. Mokke's WritingActivity inherits a
 * MaterialComponents theme that forces `fmt=TRANSLUCENT` at the window level
 * (status-bar color + MaterialComponents defaults), and on-device tests showed
 * the refresh-mode call succeeds at the Java level but never cascades to
 * `HwcHal setLayerRefreshMode`. The attach still runs cleanly — it's a
 * no-visible-effect no-op in that case — and the app falls back to pure Canvas
 * rendering, same as every other non-Onyx device.
 */
class BigmeInkController : InkController {

    override var isActive: Boolean = false
        private set

    /** Bigme's daemon paints directly to the framebuffer; MotionEvents still flow
     *  through Android's normal pipeline and must be handled by the host View. */
    override val consumesMotionEvents: Boolean = false

    private var attachedView: SurfaceView? = null
    private var einkManager: Any? = null
    private var setRefreshModeForSurfaceView: java.lang.reflect.Method? = null

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        // Currently a no-op: setRefreshModeForSurfaceView(view, EINK_HANDWRITE_MODE)
        // had two undesirable effects on this Hibreak Plus firmware:
        //   1. The handwrittenservice daemon does not actually tag our layer with
        //      HANDWRITTEN_FLAG (third-party apps are format-gated — see KDoc).
        //   2. The mode implicitly enables stylus-only input filtering, which
        //      swallows finger MotionEvents for that surface — breaking scroll
        //      and tap-to-open-menu.
        // Return false so HandwritingCanvasView treats this as "no controller
        // available" and uses its normal Canvas-fallback path. The reflection
        // wiring is preserved below in case a future Bigme firmware removes the
        // finger-input side effect.
        return false
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) = Unit

    override fun setEnabled(enabled: Boolean) {
        // No-op on Bigme: the refresh-mode reflection call may be synchronous and
        // block the UI thread. Since the daemon isn't actually tagging our layer
        // (third-party-app policy gate — see class KDoc), toggling the mode on
        // every pause/resume is pure cost with no benefit.
    }

    override fun detach() {
        if (!isActive) return
        val view = attachedView
        val setter = setRefreshModeForSurfaceView
        if (view != null && setter != null) {
            try {
                setter.invoke(einkManager, view, EINK_DEFAULT_MODE)
            } catch (t: Throwable) {
                Log.w(TAG, "Bigme ink detach error: ${t.message}")
            }
        }
        clear()
        Log.i(TAG, "Bigme ink controller detached")
    }

    private fun clear() {
        attachedView = null
        einkManager = null
        setRefreshModeForSurfaceView = null
        isActive = false
    }

    private fun loadXrzClass(name: String): Class<*>? {
        runCatching { return Class.forName(name) }
        if (!File(XRZ_JAR).canRead()) return null
        return runCatching {
            val parent = this::class.java.classLoader
            Class.forName(name, true, PathClassLoader(XRZ_JAR, parent))
        }.getOrNull()
    }

    companion object {
        private const val TAG = "BigmeInkController"
        private const val XRZ_JAR = "/system/framework/xrz.framework.server.jar"

        // xrz.framework.manager.EinkRefreshMode constants
        private const val EINK_HANDWRITE_MODE = 1029
        private const val EINK_DEFAULT_MODE = 178

        fun isBigmeDevice(): Boolean =
            Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
                Build.BRAND.equals("Bigme", ignoreCase = true)
    }
}
