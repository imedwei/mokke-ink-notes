package com.writer.view.ink

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

/**
 * Barebones diagnostic activity modeled after xNote's MultiWindowActivity.
 * Launch via: `adb shell am start -n com.writer.dev/com.writer.view.ink.BigmeProbeActivity`
 *
 * Goal: determine whether a fresh AppCompat-less Activity with a top-level
 * SurfaceView and framework-color background can get `fmt=OPAQUE` and thereby
 * let Bigme's handwrittenservice daemon tag the surface with HANDWRITTEN_FLAG.
 * If HwcHal emits `setLayerRefreshMode mode=500b2` while drawing here,
 * Mokke's WritingActivity is the blocker (not the xrz integration). If no
 * HwcHal lines appear, the system-level requirement is beyond just window
 * opacity.
 */
class BigmeProbeActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private val paint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private var lastX = 0f
    private var lastY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.lockCanvas()?.let { c ->
                    c.drawColor(Color.WHITE)
                    holder.unlockCanvasAndPost(c)
                }
                attachBigmeRefreshMode()
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        surfaceView.setOnTouchListener(::handleTouch)
        setContentView(surfaceView)
    }

    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                surfaceView.holder.lockCanvas()?.let { c ->
                    c.drawLine(lastX, lastY, x, y, paint)
                    surfaceView.holder.unlockCanvasAndPost(c)
                }
                lastX = x; lastY = y
            }
        }
        return true
    }

    private fun attachBigmeRefreshMode() {
        try {
            val mgrClass = Class.forName("xrz.framework.manager.XrzEinkManager")
            val mgr = mgrClass.getConstructor(android.content.Context::class.java)
                .newInstance(this)
            mgrClass.getMethod(
                "setRefreshModeForSurfaceView",
                SurfaceView::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(mgr, surfaceView, EINK_HANDWRITE_MODE)
            Log.i(TAG, "setRefreshModeForSurfaceView($EINK_HANDWRITE_MODE) invoked on probe surface")
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(TAG, "probe attach failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
        }
    }

    companion object {
        private const val TAG = "BigmeProbeActivity"
        private const val EINK_HANDWRITE_MODE = 1029
    }
}
