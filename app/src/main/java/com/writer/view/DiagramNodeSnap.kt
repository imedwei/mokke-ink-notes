package com.writer.view

import android.graphics.RectF
import com.writer.model.DiagramNode
import com.writer.model.StrokeType
import kotlin.math.hypot

/**
 * Pure geometry helpers for magnetic arrow-endpoint snapping to diagram nodes.
 *
 * Extracted from [HandwritingCanvasView] private methods so they can be unit-tested
 * and to centralise the degenerate-endpoint fix (Bug 2: zero-length arrow).
 *
 * All geometry is implemented in the `*Raw` internal functions that accept plain
 * floats, keeping them free of android.graphics dependencies for pure-JVM unit tests.
 * The public overloads that accept [DiagramNode] / [RectF] are thin wrappers used
 * in production code.
 */
object DiagramNodeSnap {

    // ── Raw geometry (android-free, unit-testable) ────────────────────────────

    /** Distance from ([px],[py]) to nearest point on the bbox perimeter (0 if inside). */
    internal fun distToBboxRaw(
        px: Float, py: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ): Float {
        val cx = px.coerceIn(left, right)
        val cy = py.coerceIn(top, bottom)
        return hypot(px - cx, py - cy)
    }

    /**
     * Nearest point on a node's perimeter to ([px],[py]).
     *
     * For points outside the bounding box the nearest bbox edge is returned.
     * For points inside the bounding box the nearest of the four edges is returned.
     *
     * Note: a point just *outside* an edge and a point just *inside* the same edge
     * both project to the **same** perimeter coordinate (the edge itself).
     * See [snapArrowEndpointsRaw] for the higher-level guard against degenerate arrows.
     */
    internal fun nearestPerimeterPointRaw(
        px: Float, py: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        shapeType: StrokeType
    ): Pair<Float, Float> {
        if (shapeType == StrokeType.ELLIPSE) {
            val cx = (left + right) / 2f;  val cy = (top + bottom) / 2f
            val ra = (right - left) / 2f;  val rb = (bottom - top) / 2f
            val dx = px - cx;  val dy = py - cy
            val len = hypot(dx, dy)
            return if (len == 0f) Pair(cx + ra, cy)
            else Pair(cx + ra * dx / len, cy + rb * dy / len)
        }
        // Rectangle / RoundedRectangle / Diamond / Triangle: clamp onto bbox
        val cx = px.coerceIn(left, right)
        val cy = py.coerceIn(top, bottom)
        if (cx != px || cy != py) return Pair(cx, cy)  // outside — clamp is the nearest edge point
        // Inside — project to nearest of the four edges
        val dLeft   = px - left
        val dRight  = right - px
        val dTop    = py - top
        val dBottom = bottom - py
        val minD = minOf(dLeft, dRight, dTop, dBottom)
        return when (minD) {
            dLeft   -> Pair(left,  py)
            dRight  -> Pair(right, py)
            dTop    -> Pair(px, top)
            else    -> Pair(px, bottom)
        }
    }

    /**
     * Snap arrow endpoints to diagram-node perimeters, guarding against degenerate results.
     *
     * Each endpoint independently finds the nearest node within [threshold] px.
     *
     * **Degenerate-endpoint guard**: if both endpoints would snap to the *same* perimeter
     * point (e.g. stroke barely crosses one edge of a node), the FROM endpoint is left at
     * its original position so the rendered arrow line remains visible.
     *
     * @return Triple of (snappedFrom, snappedTo, Pair(fromNodeId, toNodeId))
     */
    internal fun snapArrowEndpointsRaw(
        fromPx: Float, fromPy: Float,
        toPx: Float, toPy: Float,
        nodes: Map<String, DiagramNode>,
        threshold: Float
    ): Triple<Pair<Float, Float>, Pair<Float, Float>, Pair<String?, String?>> {
        fun nearest(px: Float, py: Float): DiagramNode? = nodes.values
            .filter { n ->
                val b = n.bounds
                distToBboxRaw(px, py, b.left, b.top, b.right, b.bottom) < threshold
            }
            .minByOrNull { n ->
                val b = n.bounds
                distToBboxRaw(px, py, b.left, b.top, b.right, b.bottom)
            }

        val fromNode = nearest(fromPx, fromPy)
        val toNode   = nearest(toPx,   toPy)

        val snappedFrom = if (fromNode != null) {
            val b = fromNode.bounds
            nearestPerimeterPointRaw(fromPx, fromPy, b.left, b.top, b.right, b.bottom, fromNode.shapeType)
        } else Pair(fromPx, fromPy)

        val snappedTo = if (toNode != null) {
            val b = toNode.bounds
            nearestPerimeterPointRaw(toPx, toPy, b.left, b.top, b.right, b.bottom, toNode.shapeType)
        } else Pair(toPx, toPy)

        // Degenerate-endpoint guard (Bug 2 fix):
        // If both endpoints snapped to the same perimeter point (e.g. the stroke barely
        // crosses one edge of a node), the arrow line is zero-length and only the arrowhead
        // renders.  Leave the FROM endpoint at its original position in that case so the
        // arrow line remains visible.
        val (resolvedFrom, resolvedFromId) =
            if (snappedFrom == snappedTo && fromNode != null) Pair(Pair(fromPx, fromPy), null)
            else Pair(snappedFrom, fromNode?.strokeId)

        return Triple(resolvedFrom, snappedTo, Pair(resolvedFromId, toNode?.strokeId))
    }

    // ── Public wrappers (use DiagramNode / RectF, for production callers) ────

    fun distToBbox(px: Float, py: Float, bounds: RectF): Float =
        distToBboxRaw(px, py, bounds.left, bounds.top, bounds.right, bounds.bottom)

    fun nearestPerimeterPoint(px: Float, py: Float, node: DiagramNode): Pair<Float, Float> {
        val b = node.bounds
        return nearestPerimeterPointRaw(px, py, b.left, b.top, b.right, b.bottom, node.shapeType)
    }

    fun snapArrowEndpoints(
        fromPx: Float, fromPy: Float,
        toPx: Float, toPy: Float,
        nodes: Map<String, DiagramNode>,
        threshold: Float
    ) = snapArrowEndpointsRaw(fromPx, fromPy, toPx, toPy, nodes, threshold)
}
