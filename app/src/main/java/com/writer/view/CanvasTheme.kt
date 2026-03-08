package com.writer.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.writer.model.InkStroke

/**
 * Shared visual constants and drawing utilities used by both
 * HandwritingCanvasView and HandwritingNameInput.
 */
object CanvasTheme {
    val DEFAULT_STROKE_WIDTH get() = ScreenMetrics.strokeWidth
    val LINE_COLOR: Int = Color.parseColor("#AAAAAA")
    val GUTTER_FILL_COLOR: Int = Color.parseColor("#DDDDDD")

    fun newStrokePaint() = Paint().apply {
        color = Color.BLACK
        strokeWidth = DEFAULT_STROKE_WIDTH
        style = Paint.Style.STROKE
        isAntiAlias = false
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun newLinePaint() = Paint().apply {
        color = LINE_COLOR
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    fun newGutterFillPaint() = Paint().apply {
        color = GUTTER_FILL_COLOR
        style = Paint.Style.FILL
    }

    fun newGutterLinePaint() = Paint().apply {
        color = LINE_COLOR
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    val DIAGRAM_BORDER_COLOR: Int = Color.parseColor("#555555")

    fun newDiagramBorderPaint() = Paint().apply {
        color = DIAGRAM_BORDER_COLOR
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    /**
     * Draw a stroke with quadratic-bezier smoothing.
     * [path] is a reusable Path to avoid allocation; it will be reset.
     */
    fun drawStroke(canvas: Canvas, stroke: InkStroke, path: Path, paint: Paint) {
        if (stroke.points.size < 2) return
        path.reset()
        path.moveTo(stroke.points[0].x, stroke.points[0].y)
        if (stroke.isGeometric) {
            // Sharp corners: lineTo each point (rectangle, triangle).
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }
        } else {
            // Smooth freehand rendering via quadratic bezier through midpoints.
            for (i in 1 until stroke.points.size) {
                val prev = stroke.points[i - 1]
                val curr = stroke.points[i]
                val midX = (prev.x + curr.x) / 2f
                val midY = (prev.y + curr.y) / 2f
                path.quadTo(prev.x, prev.y, midX, midY)
            }
            path.lineTo(stroke.points.last().x, stroke.points.last().y)
        }
        canvas.drawPath(path, paint)
    }
}
