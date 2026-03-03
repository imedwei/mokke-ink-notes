package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.writer.model.InkStroke
import com.writer.model.StrokePoint

/**
 * Simplified ink input surface for writing a document name.
 * Single ruled line, no scrolling, no gutter.
 * Uses standard touch only (no Onyx SDK — avoids conflict with the
 * main canvas's TouchHelper which can only have one active instance).
 */
class HandwritingNameInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "HandwritingNameInput"
        private val LINE_SPACING = HandwritingCanvasView.LINE_SPACING
        private val TOP_MARGIN = HandwritingCanvasView.TOP_MARGIN
    }

    private val completedStrokes = mutableListOf<InkStroke>()
    private val currentStrokePoints = mutableListOf<StrokePoint>()
    private val currentPath = Path()
    private val renderPath = Path()

    private val strokePaint = CanvasTheme.newStrokePaint()
    private val linePaint = CanvasTheme.newLinePaint()

    private val placeholderPaint = Paint().apply {
        color = Color.parseColor("#444444")
        textSize = 56f
        isAntiAlias = true
        typeface = Typeface.create("cursive", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    var onStrokeCompleted: ((InkStroke) -> Unit)? = null

    /** Light grey placeholder text shown until the user starts writing. */
    var placeholderText: String = ""
        set(value) {
            field = value
            drawToSurface()
        }

    private var surfaceReady = false

    init {
        holder.addCallback(this)
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawToSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawToSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // --- Touch handling (standard MotionEvent only) ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val pressure = event.pressure
        val timestamp = event.eventTime

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
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
                    val hy = event.getHistoricalY(i)
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
                drawToSurface()
                return true
            }
        }
        return super.onTouchEvent(event)
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

    // --- Drawing ---

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
        canvas.drawColor(Color.WHITE)

        // Draw 1 ruled line
        val lineY = TOP_MARGIN + LINE_SPACING
        canvas.drawLine(0f, lineY, width.toFloat(), lineY, linePaint)

        // Draw placeholder text if no strokes yet, vertically centered
        if (completedStrokes.isEmpty() && currentStrokePoints.isEmpty() && placeholderText.isNotEmpty()) {
            val textX = width / 2f
            val fm = placeholderPaint.fontMetrics
            val textY = (height - fm.ascent - fm.descent) / 2f
            canvas.drawText(placeholderText, textX, textY, placeholderPaint)
        }

        // Draw completed strokes
        for (stroke in completedStrokes) {
            drawStroke(canvas, stroke)
        }

        // Draw current in-progress stroke
        if (currentStrokePoints.size > 1) {
            canvas.drawPath(currentPath, strokePaint)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: InkStroke) {
        CanvasTheme.drawStroke(canvas, stroke, renderPath, strokePaint)
    }

    // --- Public API ---

    fun removeStrokes(strokeIds: Set<String>) {
        completedStrokes.removeAll { it.strokeId in strokeIds }
        drawToSurface()
    }

    fun clear() {
        completedStrokes.clear()
        currentStrokePoints.clear()
        currentPath.reset()
        drawToSurface()
    }

    fun getStrokes(): List<InkStroke> = completedStrokes.toList()
}
