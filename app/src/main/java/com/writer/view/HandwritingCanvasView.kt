package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.writer.ui.writing.AnnotationStroke
import com.writer.ui.writing.TextAnnotation
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.writer.model.InkStroke
import com.writer.model.StrokePoint

/**
 * Primary ink input surface. Uses Onyx Pen SDK for low-latency
 * e-ink rendering on Boox devices via SurfaceView. Falls back to standard
 * Android MotionEvent handling on non-Boox devices (for emulator testing).
 */
class HandwritingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "HandwritingCanvas"
        private const val DEFAULT_STROKE_WIDTH = 5f
        // Line spacing in pixels. ~128px at 300ppi ≈ 0.43 inches.
        const val LINE_SPACING = 128f
        // Idle timeout before checking scroll condition (ms)
        private const val IDLE_TIMEOUT_MS = 2000L
        // Top margin before the first line
        const val TOP_MARGIN = 40f
        // Width of the scroll gutter on the right edge
        const val GUTTER_WIDTH = 144f
    }

    private val completedStrokes = mutableListOf<InkStroke>()
    private val currentStrokePoints = mutableListOf<StrokePoint>()
    private val currentPath = Path()

    private val strokePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = DEFAULT_STROKE_WIDTH
        style = Paint.Style.STROKE
        isAntiAlias = false
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val linePaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val gutterPaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        style = Paint.Style.FILL
    }

    private val gutterLinePaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val annotationPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val annotationTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    /** When true, all pen input is blocked and annotations are rendered. */
    var tutorialMode = false
    var annotationStrokes: List<AnnotationStroke> = emptyList()
    var textAnnotations: List<TextAnnotation> = emptyList()

    var onStrokeCompleted: ((InkStroke) -> Unit)? = null
    var onIdleTimeout: (() -> Unit)? = null
    /** Called when manual scrolling changes the offset. */
    var onManualScroll: (() -> Unit)? = null

    /** Scroll offset in document-space pixels. Increase to scroll content up. */
    var scrollOffsetY: Float = 0f

    // Gutter scrolling state
    private var isGutterDragging = false
    private var gutterDragLastY = 0f

    private val idleRunnable = Runnable { onIdleTimeout?.invoke() }

    private var useOnyxSdk = false
    private var touchHelper: TouchHelper? = null
    private var surfaceReady = false

    init {
        holder.addCallback(this)
    }

    private val onyxCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            handler.removeCallbacks(idleRunnable)
            currentStrokePoints.clear()
            currentStrokePoints.add(tp.toDocStrokePoint())
        }

        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            currentStrokePoints.add(tp.toDocStrokePoint())
        }

        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {
            // Batch delivery — used by SDK after stroke ends
        }

        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            Log.d(TAG, "onEndRawDrawing: ${currentStrokePoints.size} points")
            currentStrokePoints.add(tp.toDocStrokePoint())
            finishStroke()
            handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
        }

        override fun onBeginRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(tpl: TouchPointList) {}
    }

    /** Convert SDK TouchPoint to document-space StrokePoint. */
    private fun TouchPoint.toDocStrokePoint(): StrokePoint {
        return StrokePoint(
            x = this.x,
            y = this.y + scrollOffsetY,
            pressure = this.pressure,
            timestamp = this.timestamp
        )
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawToSurface()
        tryInitOnyx()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawToSurface()
        if (useOnyxSdk) {
            try {
                val limit = Rect()
                getLocalVisibleRect(limit)
                limit.right = (limit.right - GUTTER_WIDTH).toInt()
                touchHelper?.setLimitRect(limit, emptyList())
            } catch (e: Exception) {
                Log.w(TAG, "Error updating limit rect: ${e.message}")
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        handler.removeCallbacks(idleRunnable)
        try {
            touchHelper?.closeRawDrawing()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Onyx SDK: ${e.message}")
        }
    }

    private fun tryInitOnyx() {
        if (useOnyxSdk) return // already initialized

        try {
            val limit = Rect()
            getLocalVisibleRect(limit)
            limit.right = (limit.right - GUTTER_WIDTH).toInt()

            touchHelper = TouchHelper.create(this, onyxCallback)
            touchHelper?.setStrokeWidth(DEFAULT_STROKE_WIDTH)
            touchHelper?.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
            touchHelper?.setStrokeColor(Color.BLACK)
            touchHelper?.setLimitRect(limit, emptyList())
            touchHelper?.openRawDrawing()
            touchHelper?.setRawDrawingEnabled(true)

            useOnyxSdk = true
            Log.i(TAG, "Onyx SDK initialized: limitRect=$limit")
        } catch (e: Exception) {
            Log.w(TAG, "Onyx SDK init failed, falling back to standard touch: ${e.message}")
            useOnyxSdk = false
            touchHelper = null
        }
    }

    // --- Touch handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Reject all finger/palm touches, but cancel idle timer
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            handler.removeCallbacks(idleRunnable)
            return false
        }

        // If already in a gutter drag, keep handling as gutter even if pen leaves the area
        if (isGutterDragging) {
            return handleGutterTouch(event)
        }

        // Stylus/mouse in gutter area → scroll drag
        if (event.x >= width - GUTTER_WIDTH) {
            return handleGutterTouch(event)
        }

        // In tutorial mode, block all writing input but allow gutter (handled above)
        if (tutorialMode) return false

        // If using Onyx SDK, pen input in the canvas area is handled by SDK callbacks
        if (useOnyxSdk) return true

        // Stylus/mouse on canvas → writing (fallback for non-Boox devices)
        val x = event.x
        val y = event.y + scrollOffsetY
        val pressure = event.pressure
        val timestamp = event.eventTime

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(idleRunnable)
                currentStrokePoints.clear()
                currentPath.reset()
                currentPath.moveTo(x, y)
                currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i) + scrollOffsetY
                    val hp = event.getHistoricalPressure(i)
                    val ht = event.getHistoricalEventTime(i)
                    currentPath.lineTo(hx, hy)
                    currentStrokePoints.add(StrokePoint(hx, hy, hp, ht))
                }
                currentPath.lineTo(x, y)
                currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                finishStroke()
                handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
                drawToSurface()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleGutterTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isGutterDragging = true
                gutterDragLastY = event.y
                handler.removeCallbacks(idleRunnable)
                pauseRawDrawing()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isGutterDragging) return false
                val dy = gutterDragLastY - event.y  // drag up = positive = scroll down
                gutterDragLastY = event.y
                scrollOffsetY = (scrollOffsetY + dy).coerceAtLeast(0f)
                drawToSurface()
                onManualScroll?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isGutterDragging) return false
                isGutterDragging = false
                scrollOffsetY = snapToLine(scrollOffsetY)
                drawToSurface()
                if (!tutorialMode) resumeRawDrawing()
                onManualScroll?.invoke()
                return true
            }
        }
        return false
    }

    private fun finishStroke() {
        if (currentStrokePoints.size < 2) {
            currentStrokePoints.clear()
            currentPath.reset()
            return
        }
        val stroke = InkStroke(points = currentStrokePoints.toList())
        completedStrokes.add(stroke)
        onStrokeCompleted?.invoke(stroke)
        currentStrokePoints.clear()
        currentPath.reset()
    }

    /** Draw all content to the SurfaceView's surface. */
    fun drawToSurface() {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        try {
            renderContent(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun renderContent(canvas: Canvas) {
        // Clear background
        canvas.drawColor(Color.WHITE)

        val gutterLeft = width - GUTTER_WIDTH

        // Apply scroll offset
        canvas.save()
        canvas.translate(0f, -scrollOffsetY)

        // Draw ruled lines
        val maxDocY = scrollOffsetY + height + LINE_SPACING
        var lineY = TOP_MARGIN + LINE_SPACING
        while (lineY < maxDocY) {
            canvas.drawLine(0f, lineY, gutterLeft, lineY, linePaint)
            lineY += LINE_SPACING
        }

        // Only draw strokes within the visible viewport
        val viewTop = scrollOffsetY
        val viewBottom = scrollOffsetY + height
        for (stroke in completedStrokes) {
            val minY = stroke.points.minOf { it.y }
            val maxY = stroke.points.maxOf { it.y }
            if (maxY >= viewTop && minY <= viewBottom) {
                drawStroke(canvas, stroke)
            }
        }

        // Draw current in-progress stroke (fallback only)
        if (!useOnyxSdk && currentStrokePoints.size > 1) {
            canvas.drawPath(currentPath, strokePaint)
        }

        canvas.restore()

        // Draw gutter (in screen space)
        canvas.drawRect(gutterLeft, 0f, width.toFloat(), height.toFloat(), gutterPaint)
        canvas.drawLine(gutterLeft, 0f, gutterLeft, height.toFloat(), gutterLinePaint)

        // Draw tutorial annotations on top of everything (including gutter)
        if (annotationStrokes.isNotEmpty() || textAnnotations.isNotEmpty()) {
            canvas.save()
            canvas.translate(0f, -scrollOffsetY)
            for (annotation in annotationStrokes) {
                if (annotation.points.size < 2) continue
                val path = Path()
                path.moveTo(annotation.points[0].x, annotation.points[0].y)
                for (i in 1 until annotation.points.size) {
                    path.lineTo(annotation.points[i].x, annotation.points[i].y)
                }
                annotationPaint.color = annotation.color
                annotationPaint.strokeWidth = annotation.strokeWidth
                canvas.drawPath(path, annotationPaint)
            }
            for (ta in textAnnotations) {
                annotationTextPaint.color = ta.color
                annotationTextPaint.textSize = ta.size
                annotationTextPaint.textAlign = if (ta.centered) Paint.Align.CENTER else Paint.Align.LEFT
                canvas.drawText(ta.text, ta.x, ta.y, annotationTextPaint)
            }
            canvas.restore()
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: InkStroke) {
        if (stroke.points.size < 2) return
        val path = Path()
        path.moveTo(stroke.points[0].x, stroke.points[0].y)
        for (i in 1 until stroke.points.size) {
            val prev = stroke.points[i - 1]
            val curr = stroke.points[i]
            val midX = (prev.x + curr.x) / 2f
            val midY = (prev.y + curr.y) / 2f
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        val last = stroke.points.last()
        path.lineTo(last.x, last.y)
        canvas.drawPath(path, strokePaint)
    }

    /** Pause Onyx SDK raw drawing (needed before scrolling/screen refresh). */
    fun pauseRawDrawing() {
        if (useOnyxSdk) {
            try {
                touchHelper?.setRawDrawingEnabled(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error pausing raw drawing: ${e.message}")
            }
        }
    }

    /** Resume Onyx SDK raw drawing after scrolling/screen refresh. */
    fun resumeRawDrawing() {
        if (useOnyxSdk) {
            try {
                touchHelper?.setRawDrawingEnabled(true)
            } catch (e: Exception) {
                Log.w(TAG, "Error resuming raw drawing: ${e.message}")
            }
        }
    }

    /** Remove strokes by ID from the canvas and redraw. */
    fun removeStrokes(strokeIds: Set<String>) {
        completedStrokes.removeAll { it.strokeId in strokeIds }
        drawToSurface()
    }

    /** Replace strokes by ID with new versions (e.g. shifted Y coordinates). */
    fun replaceStrokes(replacements: Map<String, InkStroke>) {
        for (i in completedStrokes.indices) {
            val replacement = replacements[completedStrokes[i].strokeId]
            if (replacement != null) {
                completedStrokes[i] = replacement
            }
        }
        drawToSurface()
    }

    /** Snap a scroll offset to the nearest line boundary so lines aren't cut off. */
    fun snapToLine(offset: Float): Float {
        if (offset <= 0f) return 0f
        val lineIndex = ((offset - TOP_MARGIN) / LINE_SPACING).let {
            kotlin.math.round(it)
        }
        return (TOP_MARGIN + lineIndex * LINE_SPACING).coerceAtLeast(0f)
    }

    /** Returns the maximum useful scroll offset based on current strokes. */
    fun getMaxScrollOffset(): Float {
        if (completedStrokes.isEmpty()) return 0f
        val maxY = completedStrokes.maxOf { stroke -> stroke.points.maxOf { it.y } }
        return (maxY - height / 2f).coerceAtLeast(0f)
    }

    fun clear() {
        completedStrokes.clear()
        currentStrokePoints.clear()
        currentPath.reset()
        scrollOffsetY = 0f
        handler.removeCallbacks(idleRunnable)
        drawToSurface()
    }

    /** Load strokes from saved data (for restoring persisted documents). */
    fun loadStrokes(strokes: List<InkStroke>) {
        completedStrokes.clear()
        completedStrokes.addAll(strokes)
        drawToSurface()
    }

    fun getStrokes(): List<InkStroke> = completedStrokes.toList()

    fun getStrokeCount(): Int = completedStrokes.size

    fun clearAnnotations() {
        tutorialMode = false
        annotationStrokes = emptyList()
        textAnnotations = emptyList()
    }

    fun isUsingOnyxSdk(): Boolean = useOnyxSdk
}
