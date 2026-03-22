package com.writer.view

import android.graphics.RectF
import com.writer.model.DiagramNode
import com.writer.model.StrokeType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

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

    // ── Self-loop detection (android-free, unit-testable) ────────────────────

    /**
     * Check if both endpoints of a stroke are near the **same** diagram node.
     *
     * @param pathLength total path length of the stroke in px
     * @param threshold  max distance from endpoint to node bbox perimeter
     * @param minPathLengthPx minimum path length to qualify (rejects short grazes)
     * @return the matching nodeId, or null if no single node is within threshold of both endpoints
     *         or if the path isn't curved enough (pathLength / endpointDistance must be > 2.0)
     */
    fun detectSelfLoop(
        firstX: Float, firstY: Float,
        lastX: Float, lastY: Float,
        pathLength: Float,
        nodes: Map<String, DiagramNode>,
        threshold: Float,
        minPathLengthPx: Float,
        xReversals: Int = 0
    ): String? {
        // A stroke with ≥2 X-direction reversals is a scratch-out, not a self-loop.
        if (xReversals >= ScratchOutDetection.MIN_REVERSALS) return null
        if (pathLength < minPathLengthPx) return null
        val endpointDist = hypot(lastX - firstX, lastY - firstY)
        if (endpointDist > 0f && pathLength / endpointDist <= 2.0f) return null

        // Find node within threshold of first point
        val firstNode = nodes.values
            .filter { n ->
                val b = n.bounds
                distToBboxRaw(firstX, firstY, b.left, b.top, b.right, b.bottom) < threshold
            }
            .minByOrNull { n ->
                val b = n.bounds
                distToBboxRaw(firstX, firstY, b.left, b.top, b.right, b.bottom)
            } ?: return null

        // Last point must also be within threshold of the SAME node
        val b = firstNode.bounds
        if (distToBboxRaw(lastX, lastY, b.left, b.top, b.right, b.bottom) >= threshold) return null

        return firstNode.strokeId
    }

    /**
     * Compute the perimeter point on a node shape from an approach direction.
     *
     * This mirrors the ray-intersection logic in [RecognizedTextView.perimeterPoint]
     * but works on raw floats (no android.graphics dependency).
     *
     * @param dx approach direction X component (need not be normalised)
     * @param dy approach direction Y component
     */
    internal fun perimeterPointFromDirectionRaw(
        dx: Float, dy: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        shapeType: StrokeType
    ): Pair<Float, Float> {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        if (dx == 0f && dy == 0f) return right to cy  // default: right-side midpoint
        return when (shapeType) {
            StrokeType.ELLIPSE -> {
                val a = (right - left) / 2f
                val b = (bottom - top) / 2f
                val t = 1f / sqrt((dx / a) * (dx / a) + (dy / b) * (dy / b))
                (cx + t * dx) to (cy + t * dy)
            }
            StrokeType.DIAMOND -> {
                val hw = (right - left) / 2f
                val hh = (bottom - top) / 2f
                val t = 1f / (abs(dx) / hw + abs(dy) / hh)
                (cx + t * dx) to (cy + t * dy)
            }
            else -> {
                val hw = (right - left) / 2f
                val hh = (bottom - top) / 2f
                val tx = if (dx != 0f) hw / abs(dx) else Float.MAX_VALUE
                val ty = if (dy != 0f) hh / abs(dy) else Float.MAX_VALUE
                val t = minOf(tx, ty)
                (cx + t * dx) to (cy + t * dy)
            }
        }
    }

    // ── Self-loop arc generation (android-free, unit-testable) ─────────────

    /**
     * Generate a clean cubic Bézier arc for a self-loop arrow.
     *
     * The arc starts at ([startX],[startY]) and ends at ([endX],[endY]) — both
     * perimeter snap points on the same node.  Control points are pushed outward
     * from the node center through each endpoint, producing a curve that bulges
     * away from the node.
     *
     * @return list of [numPoints]+1 evenly-sampled points along the cubic Bézier.
     */
    fun generateSelfLoopArc(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        cx: Float, cy: Float,
        nodeWidth: Float, nodeHeight: Float,
        numPoints: Int = 20
    ): List<Pair<Float, Float>> {
        val loopRadius = max(nodeWidth, nodeHeight) * 0.6f

        // Outward direction: from center through perimeter point
        fun outward(px: Float, py: Float): Pair<Float, Float> {
            val dx = px - cx; val dy = py - cy
            val len = hypot(dx, dy)
            return if (len == 0f) Pair(1f, 0f) else Pair(dx / len, dy / len)
        }

        val (odx0, ody0) = outward(startX, startY)
        val (odx1, ody1) = outward(endX, endY)

        val cp1x = startX + odx0 * loopRadius
        val cp1y = startY + ody0 * loopRadius
        val cp2x = endX + odx1 * loopRadius
        val cp2y = endY + ody1 * loopRadius

        val result = ArrayList<Pair<Float, Float>>(numPoints + 1)
        for (i in 0..numPoints) {
            val t = i.toFloat() / numPoints
            val u = 1f - t
            val x = u * u * u * startX + 3f * u * u * t * cp1x + 3f * u * t * t * cp2x + t * t * t * endX
            val y = u * u * u * startY + 3f * u * u * t * cp1y + 3f * u * t * t * cp2y + t * t * t * endY
            result.add(Pair(x, y))
        }
        return result
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
