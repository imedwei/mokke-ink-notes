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
        val pts = stroke.points
        val n = pts.size
        path.moveTo(pts[0].x, pts[0].y)
        if (stroke.strokeType.isArc && n == 3) {
            // Arc: 3 points = start, bezier control, end → quadratic bezier
            path.quadTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y)
        } else if (stroke.isGeometric) {
            // Sharp corners: lineTo each point (rectangle, triangle, arrow line, elbow, diamond).
            for (i in 1 until n) {
                path.lineTo(pts[i].x, pts[i].y)
            }
        } else {
            // Smooth freehand rendering via quadratic bezier through midpoints.
            for (i in 1 until n) {
                val prev = pts[i - 1]
                val curr = pts[i]
                val midX = (prev.x + curr.x) / 2f
                val midY = (prev.y + curr.y) / 2f
                path.quadTo(prev.x, prev.y, midX, midY)
            }
            path.lineTo(pts.last().x, pts.last().y)
        }
        canvas.drawPath(path, paint)

        // Draw arrowheads
        val first = pts.first()
        val last = pts.last()
        val size = stroke.strokeWidth * 4f
        val st = stroke.strokeType

        if (st.hasArrowAtTip) {
            val (dx, dy) = tipDirection(stroke)
            drawArrowhead(canvas, paint, last.x, last.y, dx, dy, size)
        }
        if (st.hasArrowAtTail) {
            val (dx, dy) = tailDirection(stroke)
            drawArrowhead(canvas, paint, first.x, first.y, dx, dy, size)
        }
    }

    /**
     * Compute the direction vector for the arrowhead at the tip (end) of a stroke.
     * Uses local tangent for arcs/elbows/freehand, chord for simple geometric lines.
     */
    private fun tipDirection(stroke: InkStroke): Pair<Float, Float> {
        val pts = stroke.points
        val n = pts.size
        val last = pts.last()
        val st = stroke.strokeType
        return when {
            // Arc: tip tangent = derivative of quadratic bezier at t=1 = 2(P2 - C)
            st.isArc && n == 3 -> {
                val dx = 2f * (pts[2].x - pts[1].x)
                val dy = 2f * (pts[2].y - pts[1].y)
                Pair(dx, dy)
            }
            // Elbow: tip direction = corner → end
            st.isElbow && n == 3 -> {
                Pair(pts[2].x - pts[1].x, pts[2].y - pts[1].y)
            }
            // Freehand with enough points: use local tangent
            !stroke.isGeometric && n > 3 -> {
                Pair(last.x - pts[n - 3].x, last.y - pts[n - 3].y)
            }
            // Simple geometric line: chord direction
            else -> Pair(last.x - pts.first().x, last.y - pts.first().y)
        }
    }

    /**
     * Compute the direction vector for the arrowhead at the tail (start) of a stroke.
     */
    private fun tailDirection(stroke: InkStroke): Pair<Float, Float> {
        val pts = stroke.points
        val n = pts.size
        val first = pts.first()
        val st = stroke.strokeType
        return when {
            // Arc: tail tangent = derivative at t=0 = 2(C - P0), reversed for pointing away
            st.isArc && n == 3 -> {
                val dx = 2f * (pts[0].x - pts[1].x)
                val dy = 2f * (pts[0].y - pts[1].y)
                Pair(dx, dy)
            }
            // Elbow: tail direction = corner → start
            st.isElbow && n == 3 -> {
                Pair(pts[0].x - pts[1].x, pts[0].y - pts[1].y)
            }
            // Freehand with enough points: use local tangent
            !stroke.isGeometric && n > 3 -> {
                Pair(first.x - pts[2].x, first.y - pts[2].y)
            }
            // Simple geometric line: reversed chord direction
            else -> Pair(first.x - pts.last().x, first.y - pts.last().y)
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
