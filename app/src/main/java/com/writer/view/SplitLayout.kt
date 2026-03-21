package com.writer.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * A vertical LinearLayout that intercepts touch events near the divider
 * to enable split-resize dragging. The divider stays visually thin (1dp)
 * while the touch target is expanded to [TOUCH_TARGET_DP] on each side.
 *
 * Set [dividerView] after inflation and [onSplitDrag] to receive drag deltas.
 */
class SplitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        /** Touch target expansion on each side of the divider, in dp. */
        private const val TOUCH_TARGET_DP = 24f
    }

    /** The divider View whose position defines the drag zone. Must be set after inflation. */
    var dividerView: View? = null

    /** Called during a split drag with the raw Y delta in pixels. */
    var onSplitDrag: ((delta: Float) -> Unit)? = null

    /** Called when a split drag starts. */
    var onSplitDragStart: (() -> Unit)? = null

    /** Called when a split drag ends. */
    var onSplitDragEnd: (() -> Unit)? = null

    private var dragging = false
    private var dragLastY = 0f

    private val touchTargetPx get() = ScreenMetrics.dp(TOUCH_TARGET_DP)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Only intercept finger touches — stylus must pass through for writing
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
            return super.onInterceptTouchEvent(ev)
        }

        val divider = dividerView ?: return super.onInterceptTouchEvent(ev)

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isNearDivider(ev.y, divider)) {
                    dragging = true
                    dragLastY = ev.rawY
                    onSplitDragStart?.invoke()
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val delta = event.rawY - dragLastY
                dragLastY = event.rawY
                onSplitDrag?.invoke(delta)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSplitDragEnd?.invoke()
                return true
            }
        }
        return true
    }

    private fun isNearDivider(y: Float, divider: View): Boolean {
        val dividerCenter = divider.top + divider.height / 2f
        return kotlin.math.abs(y - dividerCenter) <= touchTargetPx
    }
}
