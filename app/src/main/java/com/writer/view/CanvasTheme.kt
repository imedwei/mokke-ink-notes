package com.writer.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.writer.model.InkStroke
import com.writer.model.StrokeType
import kotlin.math.hypot
import kotlin.math.sqrt

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
            // Sharp corners: lineTo each point (rectangle, triangle, arrow line, diamond).
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

        // Draw arrowheads for arrow stroke types
        val first = stroke.points.first()
        val last = stroke.points.last()
        val n = stroke.points.size
        val size = stroke.strokeWidth * 4f
        // For non-geometric strokes with enough points, use local tangent
        // so self-loop arrowheads point along the curve instead of using
        // the (near-zero) first→last vector.
        val useLocalTangent = !stroke.isGeometric && n > 3
        when (stroke.strokeType) {
            StrokeType.ARROW_HEAD -> {
                val ref = if (useLocalTangent) stroke.points[n - 3] else first
                drawArrowhead(canvas, paint, last.x, last.y,
                    last.x - ref.x, last.y - ref.y, size)
            }
            StrokeType.ARROW_TAIL -> {
                val ref = if (useLocalTangent) stroke.points[2] else last
                drawArrowhead(canvas, paint, first.x, first.y,
                    first.x - ref.x, first.y - ref.y, size)
            }
            StrokeType.ARROW_BOTH -> {
                val tipRef = if (useLocalTangent) stroke.points[n - 3] else first
                drawArrowhead(canvas, paint, last.x, last.y,
                    last.x - tipRef.x, last.y - tipRef.y, size)
                val tailRef = if (useLocalTangent) stroke.points[2] else last
                drawArrowhead(canvas, paint, first.x, first.y,
                    first.x - tailRef.x, first.y - tailRef.y, size)
            }
            else -> {}
        }
    }

    /**
     * Draw a filled isoceles triangle arrowhead at ([tipX], [tipY]) pointing in direction ([dx], [dy]).
     * [size] is the base half-width; height = size × 1.5.
     */
    private fun drawArrowhead(
        canvas: Canvas, paint: Paint,
        tipX: Float, tipY: Float,
        dx: Float, dy: Float,
        size: Float
    ) {
        val len = hypot(dx, dy)
        if (len == 0f) return
        // Unit forward vector
        val fx = dx / len; val fy = dy / len
        // Unit perpendicular
        val px = -fy; val py = fx
        val height = size * 1.5f
        // Base center = tip − height × forward
        val bx = tipX - height * fx; val by = tipY - height * fy
        val arrowPath = Path()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(bx + size * px, by + size * py)
        arrowPath.lineTo(bx - size * px, by - size * py)
        arrowPath.close()
        val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }
        canvas.drawPath(arrowPath, fillPaint)
    }
}
