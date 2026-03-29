package com.writer.tools.viewer

import com.writer.model.proto.ColumnDataProto
import com.writer.model.proto.InkStrokeProto
import com.writer.model.proto.NumericRunProto
import com.writer.model.proto.StrokePointProto
import com.writer.model.proto.StrokeTypeProto
import kotlin.math.hypot

private const val LINE_SPACING = 50f
private const val TOP_MARGIN = 19f
private const val DEFAULT_STROKE_WIDTH = 2.6f
private const val LINE_COLOR = "#AAAAAA"

object ProtoToSvg {

    data class ColumnBounds(val maxX: Float, val maxY: Float)

    fun columnBounds(column: ColumnDataProto?, isNormalized: Boolean): ColumnBounds {
        var maxX = 0f
        var maxY = 0f
        for (stroke in column?.strokes.orEmpty()) {
            for ((x, y) in resolvePoints(stroke, isNormalized)) {
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        return ColumnBounds(maxX, maxY)
    }

    fun columnToSvg(
        column: ColumnDataProto?,
        isNormalized: Boolean,
        width: Float,
        height: Float
    ): String {
        val sb = StringBuilder()
        val w = width.toInt()
        val h = height.toInt()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">""")

        // Ruled lines
        var y = TOP_MARGIN + LINE_SPACING
        while (y < height) {
            sb.append("""<line x1="0" y1="${fmt(y)}" x2="$w" y2="${fmt(y)}" stroke="$LINE_COLOR" stroke-width="1"/>""")
            y += LINE_SPACING
        }

        // Strokes
        for (stroke in column?.strokes.orEmpty()) {
            val pts = resolvePoints(stroke, isNormalized)
            if (pts.size < 2) continue

            val strokeWidth = stroke.stroke_width ?: DEFAULT_STROKE_WIDTH
            val path = strokeToSvgPath(stroke, pts)
            sb.append("""<path d="$path" fill="none" stroke="black" stroke-width="${fmt(strokeWidth)}" stroke-linecap="round" stroke-linejoin="round"/>""")

            // Arrowheads
            val st = stroke.stroke_type ?: StrokeTypeProto.FREEHAND
            val size = strokeWidth * 4f
            if (st.hasArrowAtTip) {
                val (dx, dy) = tipDirection(stroke, pts)
                val last = pts.last()
                sb.append(arrowheadSvg(last.first, last.second, dx, dy, size))
            }
            if (st.hasArrowAtTail) {
                val (dx, dy) = tailDirection(stroke, pts)
                val first = pts.first()
                sb.append(arrowheadSvg(first.first, first.second, dx, dy, size))
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun strokeToSvgPath(
        stroke: InkStrokeProto,
        pts: List<Pair<Float, Float>>
    ): String {
        val sb = StringBuilder()
        sb.append("M ${fmt(pts[0].first)} ${fmt(pts[0].second)}")

        val st = stroke.stroke_type ?: StrokeTypeProto.FREEHAND
        val isGeometric = stroke.is_geometric ?: false
        val n = pts.size

        when {
            st.isArc && n == 3 -> {
                sb.append(" Q ${fmt(pts[1].first)} ${fmt(pts[1].second)} ${fmt(pts[2].first)} ${fmt(pts[2].second)}")
            }
            isGeometric -> {
                for (i in 1 until n) {
                    sb.append(" L ${fmt(pts[i].first)} ${fmt(pts[i].second)}")
                }
            }
            else -> {
                for (i in 1 until n) {
                    val prev = pts[i - 1]
                    val curr = pts[i]
                    val midX = (prev.first + curr.first) / 2f
                    val midY = (prev.second + curr.second) / 2f
                    sb.append(" Q ${fmt(prev.first)} ${fmt(prev.second)} ${fmt(midX)} ${fmt(midY)}")
                }
                sb.append(" L ${fmt(pts.last().first)} ${fmt(pts.last().second)}")
            }
        }
        return sb.toString()
    }

    private fun tipDirection(
        stroke: InkStrokeProto,
        pts: List<Pair<Float, Float>>
    ): Pair<Float, Float> {
        val n = pts.size
        val last = pts.last()
        val st = stroke.stroke_type ?: StrokeTypeProto.FREEHAND
        val isGeometric = stroke.is_geometric ?: false
        return when {
            st.isArc && n == 3 -> {
                Pair(2f * (pts[2].first - pts[1].first), 2f * (pts[2].second - pts[1].second))
            }
            st.isElbow && n == 3 -> {
                Pair(pts[2].first - pts[1].first, pts[2].second - pts[1].second)
            }
            !isGeometric && n > 3 -> {
                Pair(last.first - pts[n - 3].first, last.second - pts[n - 3].second)
            }
            else -> Pair(last.first - pts.first().first, last.second - pts.first().second)
        }
    }

    private fun tailDirection(
        stroke: InkStrokeProto,
        pts: List<Pair<Float, Float>>
    ): Pair<Float, Float> {
        val n = pts.size
        val first = pts.first()
        val st = stroke.stroke_type ?: StrokeTypeProto.FREEHAND
        val isGeometric = stroke.is_geometric ?: false
        return when {
            st.isArc && n == 3 -> {
                Pair(2f * (pts[0].first - pts[1].first), 2f * (pts[0].second - pts[1].second))
            }
            st.isElbow && n == 3 -> {
                Pair(pts[0].first - pts[1].first, pts[0].second - pts[1].second)
            }
            !isGeometric && n > 3 -> {
                Pair(first.first - pts[2].first, first.second - pts[2].second)
            }
            else -> Pair(first.first - pts.last().first, first.second - pts.last().second)
        }
    }

    private fun arrowheadSvg(
        tipX: Float, tipY: Float,
        dx: Float, dy: Float,
        size: Float
    ): String {
        val len = hypot(dx, dy)
        if (len == 0f) return ""
        val fx = dx / len; val fy = dy / len
        val px = -fy; val py = fx
        val height = size * 1.5f
        val bx = tipX - height * fx; val by = tipY - height * fy
        return """<polygon points="${fmt(tipX)},${fmt(tipY)} ${fmt(bx + size * px)},${fmt(by + size * py)} ${fmt(bx - size * px)},${fmt(by - size * py)}" fill="black"/>"""
    }

    /** Decode points from v3 runs or fall back to v1/v2 per-point fields. */
    private fun resolvePoints(stroke: InkStrokeProto, isNormalized: Boolean): List<Pair<Float, Float>> {
        val xRun = stroke.x_run
        if (xRun != null) {
            // v3 compact encoding — decode runs and denormalize
            val xs = decodeRun(xRun)
            val ys = stroke.y_run?.let { decodeRun(it) } ?: FloatArray(xs.size)
            return List(xs.size) { i ->
                Pair(xs[i] * LINE_SPACING, TOP_MARGIN + ys[i] * LINE_SPACING)
            }
        }
        return stroke.points.map { denormalize(it, isNormalized) }
    }

    private fun decodeRun(run: NumericRunProto): FloatArray {
        val scale = run.scale ?: 1f
        val offset = run.offset ?: 0f
        val values = FloatArray(run.deltas.size)
        var acc = 0
        for (i in run.deltas.indices) {
            acc += run.deltas[i]
            values[i] = offset + scale * acc
        }
        return values
    }

    private fun denormalize(pt: StrokePointProto, isNormalized: Boolean): Pair<Float, Float> {
        val x = pt.x ?: 0f
        val y = pt.y ?: 0f
        return if (isNormalized) {
            Pair(x * LINE_SPACING, TOP_MARGIN + y * LINE_SPACING)
        } else {
            Pair(x, y)
        }
    }

    private fun fmt(v: Float): String = "%.1f".format(v)
}

// Extension properties matching StrokeType.kt
private val StrokeTypeProto.isArc: Boolean get() = this == StrokeTypeProto.ARC ||
    this == StrokeTypeProto.ARC_ARROW_HEAD || this == StrokeTypeProto.ARC_ARROW_TAIL ||
    this == StrokeTypeProto.ARC_ARROW_BOTH

private val StrokeTypeProto.isElbow: Boolean get() = this == StrokeTypeProto.ELBOW ||
    this == StrokeTypeProto.ELBOW_ARROW_HEAD || this == StrokeTypeProto.ELBOW_ARROW_TAIL ||
    this == StrokeTypeProto.ELBOW_ARROW_BOTH

private val StrokeTypeProto.hasArrowAtTip: Boolean get() =
    this == StrokeTypeProto.ARROW_HEAD || this == StrokeTypeProto.ARROW_BOTH ||
    this == StrokeTypeProto.ELBOW_ARROW_HEAD || this == StrokeTypeProto.ELBOW_ARROW_BOTH ||
    this == StrokeTypeProto.ARC_ARROW_HEAD || this == StrokeTypeProto.ARC_ARROW_BOTH

private val StrokeTypeProto.hasArrowAtTail: Boolean get() =
    this == StrokeTypeProto.ARROW_TAIL || this == StrokeTypeProto.ARROW_BOTH ||
    this == StrokeTypeProto.ELBOW_ARROW_TAIL || this == StrokeTypeProto.ELBOW_ARROW_BOTH ||
    this == StrokeTypeProto.ARC_ARROW_TAIL || this == StrokeTypeProto.ARC_ARROW_BOTH
