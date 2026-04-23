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
 * Integration status (2026-04-22): **partially working, not yet production-ready.**
 * Verified on a Bigme Hibreak Plus (Android 14, v1.4.0 handwrittenservice):
 *  - `HandwrittenClient` and `IHandwrittenService` are reflectively reachable
 *    from an unprivileged (u0_a*) UID without any xrz permission.
 *  - `connect(width, height)` (NOT `(screenType, format)` as the constants
 *    naming suggests) allocates the ION buffer. Calling connect(0,1) asks for
 *    a 0×1 buffer → EINVAL. Passing real view dimensions works.
 *  - `registerInputListener` delivers raw stylus events on a binder thread.
 *  - `getCanvas()` returns a real Canvas after connect succeeds.
 *
 * Unsolved blocker: the daemon emits input coordinates in the panel's NATIVE
 * space (1648×824 landscape on Hibreak Plus), not our portrait view space.
 * `HandwrittenClient` has a private `convertXY(int[])` plus fields `mMatrix`,
 * `mInverse`, `mPhyRotation`, `mCurViewRotation` — we don't call it. Drawing
 * our strokes with the raw coords puts them off-screen (observed x=-792 for
 * a tap at ~view-center). xNote gets this right via
 * `com.xrz.handwrittenPlus.core.Painter` which we don't have a copy of.
 *
 * To finish: either (a) reflectively invoke `convertXY` on each event, or
 * (b) compute the view↔panel transform ourselves from `mPhyRotation`.
 *
 * API surface we use (all reflective — classes exist only on xrz firmware):
 * ```
 * HandwrittenClient(Context)
 *   int  bindView(View)
 *   boolean connect(int screenType, int format)
 *   void registerInputListener(InputListener)
 *   void setInputEnabled(boolean)
 *   void setOverlayEnabled(boolean)
 *   void unBindView()
 *   void disconnect()
 *
 * HandwrittenClient.InputListener (dynamic Proxy target)
 *   int onInputTouch(int action, int x, int y, int pressure, int tool)
 * ```
 *
 * Constants copied from the class metadata:
 *   ACTION_NEAR=0 DOWN=1 MOVE=2 UP=3 LEAVE=4
 *   TOOL_PEN=0 RUBBER=1 FINGER=2
 *   FORMAT_GRAY8=0 RGBA8888=1
 *   MODE_HANDWRITE=1029 MODE_RUBBER=1030 MODE_GU16=132
 *
 * Because the daemon takes over both input capture and rendering, this
 * controller declares `consumesMotionEvents = true` — the host View should
 * suppress its own MotionEvent pipeline and rely on the InputListener
 * callbacks delivered to [StrokeCallback].
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

            // 4. Enable input capture + overlay composition. setOverlayEnabled
            // tells the daemon to actually paint stroke pixels to our layer —
            // without it, mtk_commit only sends dirty(0,0,1,1) stubs.
            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            cls.getMethod("setOverlayEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            runCatching {
                cls.getMethod("setBlendEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)
            }
            val content = runCatching { cls.getMethod("getContent").invoke(c) }.getOrNull()
            Log.i(TAG, "post-connect: content=$content (overlay/blend enabled)")

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
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            strokeWidth = 3f
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private var lastX = 0f
        private var lastY = 0f
        private val dirty = android.graphics.Rect()

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onInputTouch" && args != null && args.size >= 5) {
                val action = args[0] as Int
                val x = (args[1] as Int).toFloat()
                val y = (args[2] as Int).toFloat()
                val pressure = (args[3] as Int).toFloat() / 4096f
                val ts = if (args.size >= 6) (args[5] as Long) else android.os.SystemClock.uptimeMillis()

                // Draw directly onto the daemon's Canvas for zero-copy EPD paint.
                val cls = getClientClass()
                val client = getClient()
                if (cls != null && client != null) {
                    try {
                        val canvas = cls.getMethod("getCanvas").invoke(client) as? android.graphics.Canvas
                        if (action == ACTION_DOWN) {
                            val content = cls.getMethod("getContent").invoke(client)
                            android.util.Log.i("BigmeInkController", "DOWN: canvas=$canvas content=$content at ($x,$y)")
                        }
                        if (canvas != null) {
                            when (action) {
                                ACTION_DOWN -> {
                                    lastX = x; lastY = y
                                }
                                ACTION_MOVE -> {
                                    canvas.drawLine(lastX, lastY, x, y, paint)
                                    val pad = paint.strokeWidth.toInt() + 2
                                    dirty.set(
                                        minOf(lastX, x).toInt() - pad,
                                        minOf(lastY, y).toInt() - pad,
                                        maxOf(lastX, x).toInt() + pad,
                                        maxOf(lastY, y).toInt() + pad,
                                    )
                                    cls.getMethod("inValidate", android.graphics.Rect::class.java, Int::class.javaPrimitiveType)
                                        .invoke(client, dirty, MODE_HANDWRITE)
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

        /** Guard for the daemon path. Flip to `false` to attempt HandwrittenClient
         *  integration; currently disabled because coord-space transform from the
         *  daemon's native panel coordinates to our View-local space is unsolved
         *  without replicating xNote's `com.xrz.handwrittenPlus.core.Painter`. */
        private const val BIGME_INTEGRATION_DISABLED = true

        fun isBigmeDevice(): Boolean =
            Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
                Build.BRAND.equals("Bigme", ignoreCase = true)
    }
}
