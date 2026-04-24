package com.writer.view.ink

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Low-latency ink for Bigme e-ink devices via the undocumented `com.xrz.HandwrittenClient`
 * API (in `framework.jar`'s classes5.dex; BOOTCLASSPATH-reachable). The client
 * connects to the native `/system/bin/handwrittenservice` daemon over binder,
 * binds a host view, and exposes an ION-backed Canvas the app draws to — the
 * daemon then refreshes the EPD for each `inValidate()` region.
 *
 * ## What we verified (Bigme Hibreak Plus, Android 14, daemon v1.4.0)
 *
 * - `HandwrittenClient` + `IHandwrittenService` are reflectively reachable
 *   from unprivileged (u0_a*) UIDs; no xrz permission required. Methods are
 *   marked `hiddenapi: BLOCKED` but reflection works anyway.
 * - `connect(int, int)` takes (width, height), NOT (screenType, format) as
 *   the `FORMAT_*`/`MODE_*` constant naming suggests. Passing zero dims hits
 *   `ion_alloc_mm ret -22` (EINVAL).
 * - The daemon's dispatcher internally calls `convertXY(int[])` before
 *   invoking the registered InputListener (unless `setUseRawInputEvent(true)`
 *   was called). So the (x, y) arriving at the listener is already view-local.
 * - `getCanvas()` returns a real, ION-backed Canvas after connect succeeds.
 *   `getContent()` stays null until first commit.
 * - Daemon doesn't auto-rasterize captured strokes — the app must draw on
 *   the Canvas and call `inValidate(rect, mode)` to trigger EPD refresh.
 * - `setOverlayEnabled(true)` is required for the daemon to actually paint.
 *   Without it, `mtk_commit` only emits `dirty(0,0,1,1)` stubs.
 * - `bindView()` synchronously fires `setRequestedFormat` → `surfaceCreated`,
 *   which re-enters this controller's attach. Needs a re-entrance guard.
 * - Event timestamps on the 6-arg `onInputTouch` aren't in `uptimeMillis`
 *   epoch; using them mixes epochs with Mokke's dwell heuristics and
 *   causes phantom arrowheads on text. Use `SystemClock.uptimeMillis()`.
 *
 * ## Full working sequence
 *
 *   HandwrittenClient(context)
 *   bindView(view)             // guarded against reentry
 *   registerInputListener(..)
 *   connect(view.width, view.height)
 *   updateLayout(); updateRotation()
 *   setInputEnabled(true)
 *   setOverlayEnabled(true)
 *
 *   // In InputListener.onInputTouch(action, x, y, pressure, tool, time):
 *   //   canvas.drawLine(lastX, lastY, x, y, paint)
 *   //   client.inValidate(dirtyRect, MODE_HANDWRITE)
 *
 * ## Why it's disabled
 *
 * Even with everything above, the daemon integration leaves EPD-refresh
 * artifacts we couldn't fully resolve:
 *
 *  1. "Train-track" ghost stripes along strokes (partial-refresh residue).
 *     Mitigations tried: coalesce `inValidate` at vsync cadence, non-AA
 *     paint, GC16 refresh on UP. Each helps but doesn't eliminate.
 *  2. Ghost lines from previously-erased shapes bleeding through new strokes
 *     over the same region. A full-buffer GC16 refresh clears them but
 *     slows the perceived latency enough to negate the win.
 *  3. Visual artifacts around text strokes — anti-alias quantization against
 *     EPD's discrete greyscale levels.
 *  4. Snap commits and diagram auto-recognition visibly lag until the next
 *     scroll; the daemon's overlay holds the pre-snap raw pixels until
 *     something clears it, and our own cycle-the-overlay attempts all
 *     produced their own flicker.
 *
 * xNote masks all of these via `com.xrz.handwrittenPlus.core.Painter`
 * (implements a `PainterListener` callback + BasePen configuration + dirty-
 * rect + refresh-mode scheduling). Replicating that is a larger project.
 *
 * The Canvas-fallback path with the bitmap cache + Choreographer coalescing +
 * StaticLayout + stroke-path caches delivers p50≈26 ms / p95≈42 ms event→paint
 * on this device — tight enough to ship as the default. Flip
 * [BIGME_INTEGRATION_DISABLED] to false to resume daemon experiments.
 *
 * ## Reference
 *
 * API surface (all reflective; classes exist only on xrz firmware):
 * ```
 * HandwrittenClient(Context)
 *   int bindView(View)
 *   boolean connect(int width, int height)
 *   void registerInputListener(InputListener)
 *   void setInputEnabled(boolean)
 *   void setOverlayEnabled(boolean)
 *   void setBlendEnabled(boolean)
 *   void setUseRawInputEvent(boolean)
 *   void inValidate(Rect, int mode)
 *   Canvas getCanvas()
 *   Bitmap getContent()
 *   Rect getViewLayout() / getPhyViewLayout()
 *   int getPhyRotation() / getCurViewRotation()
 *   boolean updateLayout()
 *   boolean updateRotation()
 *   void unBindView()
 *   void disconnect()
 *
 * HandwrittenClient.InputListener
 *   int onInputTouch(action, x, y, pressure, tool)
 *   int onInputTouch(action, x, y, pressure, tool, time)   // default
 *
 * Constants: ACTION_NEAR=0 DOWN=1 MOVE=2 UP=3 LEAVE=4
 *            TOOL_PEN=0 RUBBER=1 FINGER=2
 *            FORMAT_GRAY8=0 RGBA8888=1
 *            MODE_HANDWRITE=1029 MODE_RUBBER=1030 MODE_GU16=132 MODE_GC16=4
 * ```
 */
class BigmeInkController : InkController {

    override var isActive: Boolean = false
        private set

    /** Daemon consumes input events once connected (similar to Onyx TouchHelper). */
    override val consumesMotionEvents: Boolean get() = isActive

    private var client: Any? = null
    private var clientClass: Class<*>? = null
    private var attachedView: SurfaceView? = null
    // Guards against recursion — HandwrittenClient.bindView() calls
    // setRequestedFormat() on the SurfaceView, which synchronously fires
    // surfaceCreated → tryInitOnyx → attach(). Without this guard we'd build
    // a second client and crash.
    private var attaching: Boolean = false

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        // Daemon integration is reachable and partially working (see KDoc) but
        // the coordinate transform from the daemon's native panel space to our
        // View-local coords is unsolved without xNote's Painter class. Disable
        // the attach for now; keep the code below as reference for future work.
        if (BIGME_INTEGRATION_DISABLED) return false
        if (isActive) return true
        if (attaching) return false
        if (!isBigmeDevice()) return false
        attaching = true
        return try {
            val cls = Class.forName(HANDWRITTEN_CLIENT)
            val c = cls.getConstructor(android.content.Context::class.java).newInstance(view.context)

            // 1. bindView — this also calls setRequestedFormat on the SurfaceView,
            // which re-enters this method through surfaceCreated. The [attaching]
            // guard above makes the re-entry a no-op.
            cls.getMethod("bindView", android.view.View::class.java).invoke(c, view)

            // 2. Register InputListener via dynamic Proxy.
            val listenerCls = Class.forName(INPUT_LISTENER)
            val listener = Proxy.newProxyInstance(
                cls.classLoader,
                arrayOf(listenerCls),
                InputProxy(callback, view, getClient = { client }, getClientClass = { clientClass }),
            )
            cls.getMethod("registerInputListener", listenerCls).invoke(c, listener)

            // 3. connect(width, height) — despite the enum constants named
            // FORMAT_*/MODE_*, the bytecode locals show connect's two ints are
            // the buffer width and height. Passing 0,0 makes the daemon try to
            // ion_alloc(0) and fail with -22. Use the view's measured dimensions.
            val w = if (view.width > 0) view.width else limit.width()
            val h = if (view.height > 0) view.height else limit.height()
            val connected = cls.getMethod(
                "connect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(c, w, h) as Boolean
            if (!connected) {
                Log.w(TAG, "HandwrittenClient.connect returned false")
                cleanupClient(cls, c)
                return false
            }

            // 4. Let the client refresh its layout + rotation state — without
            // this, mViewLayout stays empty and convertXY produces wrong coords
            // in the rotation 90/270 cases (i.e. the Hibreak Plus's portrait).
            runCatching { cls.getMethod("updateLayout").invoke(c) }
            runCatching { cls.getMethod("updateRotation").invoke(c) }

            // 5. Enable input capture + overlay composition. setOverlayEnabled
            // tells the daemon to actually paint stroke pixels to our layer —
            // without it, mtk_commit only sends dirty(0,0,1,1) stubs.
            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            cls.getMethod("setOverlayEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            runCatching {
                cls.getMethod("setBlendEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            }
            val phyRot = runCatching { cls.getMethod("getPhyRotation").invoke(c) }.getOrNull()
            val viewLayout = runCatching { cls.getMethod("getViewLayout").invoke(c) }.getOrNull()
            val phyView = runCatching { cls.getMethod("getPhyViewLayout").invoke(c) }.getOrNull()
            Log.i(TAG, "post-connect: phyRot=$phyRot viewLayout=$viewLayout phyViewLayout=$phyView")

            client = c
            clientClass = cls
            attachedView = view
            isActive = true
            Log.i(TAG, "BigmeInkController attached — daemon engaged on $view (limit=$limit)")
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(TAG, "attach failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
            reset()
            false
        } finally {
            attaching = false
        }
    }

    private fun cleanupClient(cls: Class<*>, c: Any) {
        runCatching { cls.getMethod("disconnect").invoke(c) }
        runCatching { cls.getMethod("unBindView").invoke(c) }
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) {
        // HandwrittenClient doesn't expose a direct stroke style API in this
        // firmware — the daemon uses its configured paintbrush. Stroke width
        // is controlled via the pen's Painter on the app side, not here.
    }

    override fun setEnabled(enabled: Boolean) {
        val c = client ?: return
        val cls = clientClass ?: return
        try {
            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "setEnabled($enabled) failed: ${t.message}")
        }
    }

    override fun syncOverlay(bitmap: android.graphics.Bitmap, region: Rect?, force: Boolean) {
        val c = client ?: return
        val cls = clientClass ?: return
        val view = attachedView ?: return
        try {
            // Blit the host bitmap onto the daemon's ION canvas. The ION
            // buffer is sized to (view.width, view.height) at connect time;
            // the host's contentBitmap is sized to the same view dims, so
            // no translate is needed. SRC mode resets every pixel in one
            // pass so the daemon's accumulated stroke ink is replaced by
            // the host's canonical state.
            val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas ?: return
            val paint = android.graphics.Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (!force) return
            // Force-refresh: push the ION buffer to the EPD with a 16-level
            // ghost-tolerant grey-update waveform. No flash (unlike GC16),
            // transitions both directions cleanly (unlike MODE_HANDWRITE).
            // Limit to [region] when provided so the refresh is scoped to
            // the affected pixels — cheaper and less disruptive than a
            // full-view refresh.
            cls.getMethod("setOverlayEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            val rect = region ?: Rect(0, 0, view.width, view.height)
            cls.getMethod("inValidate", Rect::class.java, Int::class.javaPrimitiveType)
                .invoke(c, rect, MODE_GU16)
            Log.i(TAG, "syncOverlay: GU16 refresh $rect")
        } catch (t: Throwable) {
            Log.w(TAG, "syncOverlay failed: ${t.message}")
        }
    }

    override fun detach() {
        if (!isActive) return
        val c = client
        val cls = clientClass
        if (c != null && cls != null) {
            runCatching { cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, false) }
            runCatching { cls.getMethod("disconnect").invoke(c) }
            runCatching { cls.getMethod("unBindView").invoke(c) }
        }
        reset()
        Log.i(TAG, "BigmeInkController detached")
    }

    private fun reset() {
        client = null
        clientClass = null
        attachedView = null
        isActive = false
    }

    /** Dynamic-proxy handler for HandwrittenClient.InputListener.
     *
     *  The daemon fires callbacks on a binder thread with raw input. We do
     *  two things per event:
     *   1. Draw the stroke segment to the daemon's ION-backed Canvas and
     *      inValidate(rect, MODE_HANDWRITE) — that's what makes the EPD
     *      refresh at sub-16ms latency.
     *   2. Marshal to main thread and fire [StrokeCallback] so the app-level
     *      pipeline (stroke store, recognition) runs on the UI thread. */
    private class InputProxy(
        private val sink: StrokeCallback,
        view: SurfaceView,
        private val getClient: () -> Any?,
        private val getClientClass: () -> Class<*>?,
    ) : InvocationHandler {
        private val mainHandler = android.os.Handler(view.context.mainLooper)
        private val paint = android.graphics.Paint().apply {
            // EPD uses discrete greyscale levels; anti-aliased edges end up
            // dithered differently on each inValidate commit, producing
            // "train track" ghosting. Non-AA renders cleanly on e-ink.
            isAntiAlias = false
            color = android.graphics.Color.BLACK
            // Match the Canvas-fallback stroke width so the daemon-painted
            // ink and the bitmap-rendered ink don't look different sizes.
            strokeWidth = com.writer.view.CanvasTheme.DEFAULT_STROKE_WIDTH
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private var lastX = 0f
        private var lastY = 0f
        // Accumulates dirty region across multiple MOVE events so the daemon's
        // EPD commits batch up instead of fighting each other. One-rect-per-
        // MOVE produced "train track" refresh artifacts on long strokes.
        private val accumDirty = android.graphics.Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        // Union of the whole stroke's dirty area — used for the cleanup
        // refresh on UP so ghost residue over the full stroke path clears
        // without forcing a global GC16 that'd flash the whole page.
        private val strokeBbox = android.graphics.Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        private var lastCommitMs = 0L
        private val COMMIT_INTERVAL_MS = 16L  // one per vsync

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onInputTouch" && args != null && args.size >= 5) {
                val invokeStart = System.nanoTime()
                val action = args[0] as Int
                val x = (args[1] as Int).toFloat()
                val y = (args[2] as Int).toFloat()
                val pressure = (args[3] as Int).toFloat() / 4096f
                // The daemon's 6-arg onInputTouch passes a raw input-event
                // timestamp that isn't in Android's uptimeMillis epoch — and
                // Mokke's shape-snap dwell heuristic compares point timestamps
                // against uptimeMillis elsewhere, so the mixed epochs made
                // random text strokes register as "dwelled" and get arrows.
                // Use our own uptimeMillis everywhere.
                val ts = android.os.SystemClock.uptimeMillis()

                // Coords arriving here are ALREADY view-local: the daemon's
                // dispatcher calls HandwrittenClient.convertXY internally
                // before invoking the InputListener (unless mUseRawInputEvent
                // is true, which we never set). Double-conversion was the bug.
                val cls = getClientClass()
                val client = getClient()
                if (cls != null && client != null) {
                    try {
                        val canvas = cls.getMethod("getCanvas").invoke(client) as? android.graphics.Canvas
                        if (action == ACTION_DOWN) {
                            android.util.Log.i("BigmeInkController", "DOWN: canvas=$canvas view=($x,$y)")
                        }
                        if (canvas != null) {
                            when (action) {
                                ACTION_DOWN -> {
                                    lastX = x; lastY = y
                                    accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    strokeBbox.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    lastCommitMs = ts
                                }
                                ACTION_MOVE -> {
                                    val drawStart = System.nanoTime()
                                    canvas.drawLine(lastX, lastY, x, y, paint)
                                    com.writer.ui.writing.PerfCounters.recordDirect(
                                        com.writer.ui.writing.PerfMetric.INK_DAEMON_DRAW_LINE,
                                        System.nanoTime() - drawStart,
                                    )
                                    val pad = paint.strokeWidth.toInt() + 2
                                    val segL = minOf(lastX, x).toInt() - pad
                                    val segT = minOf(lastY, y).toInt() - pad
                                    val segR = maxOf(lastX, x).toInt() + pad
                                    val segB = maxOf(lastY, y).toInt() + pad
                                    accumDirty.union(segL, segT, segR, segB)
                                    strokeBbox.union(segL, segT, segR, segB)
                                    if (ts - lastCommitMs >= COMMIT_INTERVAL_MS) {
                                        val invStart = System.nanoTime()
                                        cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                                            .invoke(client, accumDirty, MODE_HANDWRITE)
                                        com.writer.ui.writing.PerfCounters.recordDirect(
                                            com.writer.ui.writing.PerfMetric.INK_DAEMON_INVALIDATE,
                                            System.nanoTime() - invStart,
                                        )
                                        accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                        lastCommitMs = ts
                                    }
                                    lastX = x; lastY = y
                                }
                                ACTION_UP, ACTION_LEAVE -> {
                                    // Flush pending partial-refresh segment.
                                    // No cleanup refresh here — ghosting is
                                    // addressed on erase/scratch-out instead,
                                    // which is when residue matters.
                                    if (accumDirty.left != Int.MAX_VALUE) {
                                        val invStart = System.nanoTime()
                                        cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                                            .invoke(client, accumDirty, MODE_HANDWRITE)
                                        com.writer.ui.writing.PerfCounters.recordDirect(
                                            com.writer.ui.writing.PerfMetric.INK_DAEMON_INVALIDATE,
                                            System.nanoTime() - invStart,
                                        )
                                        accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    }
                                    strokeBbox.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                                    lastX = x; lastY = y
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("BigmeInkController", "paint threw: ${t.message}", t)
                    }
                }

                // App-level stroke pipeline runs on main thread.
                mainHandler.post {
                    when (action) {
                        ACTION_DOWN -> sink.onStrokeBegin(x, y, pressure, ts)
                        ACTION_MOVE -> sink.onStrokeMove(x, y, pressure, ts)
                        ACTION_UP, ACTION_LEAVE -> sink.onStrokeEnd(x, y, pressure, ts)
                    }
                }
                com.writer.ui.writing.PerfCounters.recordDirect(
                    com.writer.ui.writing.PerfMetric.INK_DAEMON_INVOKE_TOTAL,
                    System.nanoTime() - invokeStart,
                )
                return 0
            }
            return when (method.name) {
                "toString" -> "BigmeInkController.InputProxy"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.getOrNull(0) === proxy
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "BigmeInkController"
        private const val HANDWRITTEN_CLIENT = "com.xrz.HandwrittenClient"
        private const val INPUT_LISTENER = "com.xrz.HandwrittenClient\$InputListener"

        // com.xrz.HandwrittenClient action constants (from reflection dump).
        private const val ACTION_NEAR = 0
        const val ACTION_DOWN = 1
        const val ACTION_MOVE = 2
        const val ACTION_UP = 3
        const val ACTION_LEAVE = 4
        const val MODE_HANDWRITE = 1029
        const val MODE_GC16 = 4
        const val MODE_GU16 = 132

        /** Guard for the daemon path. Set `true` to force the Canvas fallback
         *  (p50≈26 ms / p95≈42 ms event→paint via bitmap cache + Choreographer
         *  + StaticLayout/stroke-path caches). Default is `false` — daemon
         *  integration is on for direct-to-EPD sub-frame ink, accepting minor
         *  refresh ghosting over previously-erased content as the trade-off. */
        private const val BIGME_INTEGRATION_DISABLED = false

        fun isBigmeDevice(): Boolean =
            Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
                Build.BRAND.equals("Bigme", ignoreCase = true)
    }
}
