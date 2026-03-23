package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.PointF
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
import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.minY
import com.writer.model.maxY
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
        // Line spacing and top margin are DPI-scaled via ScreenMetrics.
        val LINE_SPACING get() = ScreenMetrics.lineSpacing
        // Idle timeout before checking scroll condition (ms)
        private const val IDLE_TIMEOUT_MS = 2000L
        // Top margin before the first line
        val TOP_MARGIN get() = ScreenMetrics.topMargin
        // Arrow dwell detection: radius and time for start/end dwell
        private const val ARROW_DWELL_RADIUS_PX = 15f   // ~8 dp
        // Magnetic snap: max distance in line-spacings to snap arrow to node
        private const val MAGNET_THRESHOLD_SPANS = 1.5f
        private const val ARROW_DWELL_MS = 500L
    }

    private val completedStrokes = mutableListOf<InkStroke>()
    private val currentStrokePoints = mutableListOf<StrokePoint>()
    private val currentPath = Path()
    // Reused during rendering to avoid allocating a new Path per stroke per frame
    private val renderPath = Path()

    private val strokePaint = CanvasTheme.newStrokePaint()
    private val linePaint = CanvasTheme.newLinePaint()
    private val diagramBorderPaint = CanvasTheme.newDiagramBorderPaint()

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

    /** Diagram areas in the current document. */
    var diagramAreas: List<DiagramArea> = emptyList()

    /** Document model reference for magnetic snap access. */
    var documentModel: DocumentModel? = null

    // Dwell indicator state (inside diagram → arrow tail; outside → freeform zone)
    private var dwellJob: Runnable? = null
    private var dwellIndicatorShown = false
    private var dwellDotCenter: PointF? = null

    private val dwellDotPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val freeformPreviewPaint = Paint().apply {
        color = CanvasTheme.DIAGRAM_BORDER_COLOR
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }

    /** When true, all pen input is blocked and annotations are rendered. */
    var tutorialMode = false
    var annotationStrokes: List<AnnotationStroke> = emptyList()
    var textAnnotations: List<TextAnnotation> = emptyList()

    var onStrokeCompleted: ((InkStroke) -> Unit)? = null
    var onIdleTimeout: (() -> Unit)? = null
    /** Called when manual scrolling changes the offset. */
    var onManualScroll: (() -> Unit)? = null

    /** Called when pen state changes: true = pen down (writing), false = pen lifted. */
    var onPenStateChanged: ((Boolean) -> Unit)? = null

    /** Called when a shape is detected outside a diagram area. Returns diagram bounds if created. */
    var onDiagramShapeDetected: ((stroke: InkStroke) -> Pair<Float, Float>?)? = null

    /** Raw stroke capture: called with every finished stroke BEFORE any processing.
     *  Used for test fixture recording. Set to non-null to enable capture. */
    var onRawStrokeCapture: ((points: List<StrokePoint>) -> Unit)? = null

    /** Called when a stroke inside a diagram extends beyond its bounds, to expand the area. */
    var onDiagramStrokeOverflow: ((strokeId: String, minY: Float, maxY: Float) -> Unit)? = null

    // Scratch-out callback (inside diagram areas: erase overlapping strokes)
    var onScratchOut: ((scratchPoints: List<StrokePoint>, left: Float, top: Float, right: Float, bottom: Float) -> Unit)? = null

    // Stroke-replaced callback (shape snap: raw freehand → snapped geometric)
    var onStrokeReplaced: ((oldStrokeId: String, newStroke: InkStroke) -> Unit)? = null

    /** Scroll offset in document-space pixels. Increase to scroll content up. */
    var scrollOffsetY: Float = 0f

    /** Extra scroll past the top of the document, for scrolling the text view. */
    var textOverscroll: Float = 0f

    // Diagram drawing bounds: when stroke starts in a diagram area, Y is clamped to these bounds
    private var currentDiagramBounds: Pair<Float, Float>? = null // (topY, bottomY) in doc space

    /** Shared palm-rejection filter, set by WritingActivity. */
    var touchFilter: TouchFilter? = null

    // Finger scroll state
    private var fingerScrollActive = false
    private var fingerScrollLastY = 0f

    private val idleRunnable = Runnable { onIdleTimeout?.invoke() }

    private var useOnyxSdk = false
    private var touchHelper: TouchHelper? = null
    private var surfaceReady = false

    // ── Running stroke bounding box ───────────────────────────────────────────
    // Post-stroke detection (scratch-out, shape snap) needs stroke bounds.
    // Running bounding box updated O(1) per point avoids an O(n²) scan.
    //
    // Coordinates are in document space (y includes scrollOffsetY), matching
    // currentStrokePoints.  Reset at stroke start; updated by updateStrokeBounds().
    private var strokeMinX = 0f
    private var strokeMaxX = 0f
    private var strokeMinY = 0f
    private var strokeMaxY = 0f

    init {
        holder.addCallback(this)
    }

    private val onyxCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            touchFilter?.penActive = true
            onPenStateChanged?.invoke(true)
            handler.removeCallbacks(idleRunnable)
            currentStrokePoints.clear()
            val docPt = tp.toDocStrokePoint()
            currentDiagramBounds = getDiagramBounds(docPt.y)
            currentStrokePoints.add(docPt)
            initStrokeBounds(currentStrokePoints.last())
            // Start dwell detection: inside diagram → arrow tail; outside → freeform zone
            startDwellJob()
        }

        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            val docPt = tp.toDocStrokePoint()
            currentStrokePoints.add(docPt)
            updateStrokeBounds(currentStrokePoints.last())
        }

        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {
            // Batch delivery — used by SDK after stroke ends
        }

        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            cancelDwellJob()
            touchFilter?.let {
                it.penActive = false
                it.penUpTimestamp = android.os.SystemClock.uptimeMillis()
            }
            onPenStateChanged?.invoke(false)
            Log.d(TAG, "onEndRawDrawing: ${currentStrokePoints.size} points")
            val docPt = tp.toDocStrokePoint()
            currentStrokePoints.add(docPt)
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
        useOnyxSdk = false
    }

    private fun tryInitOnyx() {
        if (useOnyxSdk) return // already initialized

        try {
            val limit = Rect()
            getLocalVisibleRect(limit)

            touchHelper = TouchHelper.create(this, onyxCallback)
            touchHelper?.setStrokeWidth(CanvasTheme.DEFAULT_STROKE_WIDTH)
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

        // Finger touches: filter through palm rejection, allow vertical scroll
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            handler.removeCallbacks(idleRunnable)
            return handleFingerTouch(event)
        }

        // In tutorial mode, block all writing input
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
                touchFilter?.penActive = true
                onPenStateChanged?.invoke(true)
                handler.removeCallbacks(idleRunnable)
                currentStrokePoints.clear()
                currentPath.reset()
                currentDiagramBounds = getDiagramBounds(y)
                currentPath.moveTo(x, y)
                currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                initStrokeBounds(currentStrokePoints.last())
                startDwellJob()
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
                    updateStrokeBounds(currentStrokePoints.last())
                }
                currentPath.lineTo(x, y)
                currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                updateStrokeBounds(currentStrokePoints.last())
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelDwellJob()
                touchFilter?.let {
                    it.penActive = false
                    it.penUpTimestamp = android.os.SystemClock.uptimeMillis()
                }
                onPenStateChanged?.invoke(false)
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

    /**
     * Handle filtered finger touches on the canvas. Only vertical scrolling is
     * allowed — no taps (avoids accidental palm taps).
     */
    private fun handleFingerTouch(event: MotionEvent): Boolean {
        val tf = touchFilter ?: return false
        val touchMinorDp = event.touchMinor / ScreenMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (tf.evaluateDown(
                        pointerCount = event.pointerCount,
                        touchMinorDp = touchMinorDp,
                        eventTime = event.eventTime,
                        x = event.x,
                        y = event.y,
                    ) == TouchFilter.Decision.REJECT
                ) {
                    fingerScrollActive = false
                    return false
                }
                fingerScrollLastY = event.y
                fingerScrollActive = true
                pauseRawDrawing()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fingerScrollActive) return false
                if (tf.evaluateMove(
                        pointerCount = event.pointerCount,
                        touchMinorDp = touchMinorDp,
                        eventTime = event.eventTime,
                        x = event.x,
                        y = event.y,
                        checkStationary = true,
                    ) == TouchFilter.Decision.REJECT
                ) {
                    // Cancel this finger gesture
                    fingerScrollActive = false
                    if (!tutorialMode) resumeRawDrawing()
                    return false
                }
                if (!tf.hasMovedPastSlop()) return true // wait for intentional drag
                val dy = fingerScrollLastY - event.y // drag up = scroll down
                fingerScrollLastY = event.y
                if (textOverscroll > 0f && dy > 0f) {
                    textOverscroll = (textOverscroll - dy).coerceAtLeast(0f)
                } else {
                    val raw = scrollOffsetY + dy
                    if (raw < 0f) {
                        scrollOffsetY = 0f
                        textOverscroll = (textOverscroll - raw).coerceAtLeast(0f)
                    } else {
                        scrollOffsetY = raw
                    }
                }
                drawToSurface()
                onManualScroll?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!fingerScrollActive) return false
                fingerScrollActive = false
                if (textOverscroll == 0f) {
                    scrollOffsetY = snapToLine(scrollOffsetY)
                }
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
            currentDiagramBounds = null
            dwellDotCenter = null
            dwellIndicatorShown = false
            return
        }

        // Raw capture: record stroke before any processing (scratch-out, shape snap, etc.)
        onRawStrokeCapture?.invoke(currentStrokePoints.toList())

        if (currentDiagramBounds != null) {
            // INSIDE DIAGRAM AREA: post-stroke shape-snap pipeline
            val tailDwell = dwellIndicatorShown
            dwellDotCenter = null
            dwellIndicatorShown = false


            // Save raw points before checkShapeSnap overwrites currentStrokePoints
            val rawPoints = currentStrokePoints.toList()

            // Shape snap before scratch-out: rectangles have X-reversals that
            // would otherwise be consumed as scratch-out.
            val snapData = checkShapeSnap(tailDwell)

            // Only check scratch-out if no shape was snapped.
            if (snapData == null && checkPostStrokeScratchOut()) {
                currentDiagramBounds = null
                return
            }

            // Determine the FINAL stroke (snapped or raw) before overflow check,
            // so overflow uses the actual stroke bounds that will be in the document.
            val finalStroke: InkStroke
            if (snapData != null) {
                // Two-phase commit: emit raw stroke first, then replace with snapped
                val rawStroke = InkStroke(
                    points = rawPoints,
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND
                )
                completedStrokes.add(rawStroke)
                onStrokeCompleted?.invoke(rawStroke)  // → saves snapshot N, adds raw stroke → state N+1

                val snappedStroke = InkStroke(
                    points = currentStrokePoints.toList(),
                    isGeometric = snapData.isGeometric,
                    strokeType = snapData.strokeType
                )
                completedStrokes.remove(rawStroke)
                completedStrokes.add(snappedStroke)
                onStrokeReplaced?.invoke(rawStroke.strokeId, snappedStroke)  // → saves snapshot N+1, replaces → state N+2
                finalStroke = snappedStroke
            } else {
                val stroke = InkStroke(
                    points = currentStrokePoints.toList(),
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND
                )
                completedStrokes.add(stroke)
                onStrokeCompleted?.invoke(stroke)
                finalStroke = stroke
            }

            // Check overflow AFTER snapping, using the final stroke's actual bounds.
            // This ensures the snapped geometry (which may differ from raw) is used.
            val (diagTopY, diagBottomY) = currentDiagramBounds!!
            val finalMinY = finalStroke.points.minOf { it.y }
            val finalMaxY = finalStroke.points.maxOf { it.y }
            if (finalMinY < diagTopY || finalMaxY > diagBottomY) {
                onDiagramStrokeOverflow?.invoke(finalStroke.strokeId, finalMinY, finalMaxY)
            }
            currentStrokePoints.clear()
            currentPath.reset()
            currentDiagramBounds = null
            if (snapData != null) {
                // Flush SDK hardware overlay showing freehand stroke, redraw clean snapped shape
                pauseRawDrawing()
                drawToSurface()
                resumeRawDrawing()
            }
        } else {
            // OUTSIDE DIAGRAM AREA
            if (checkPostStrokeScratchOut()) {
    
                return
            }

            // Check for shape-intent: dwell at end → shape detection → auto-create diagram
            val snapData = checkShapeSnap()
            if (snapData != null) {
                val rawPoints = currentStrokePoints.toList()

                // Two-phase commit: emit raw stroke, then replace with snapped shape
                val rawStroke = InkStroke(
                    points = rawPoints,
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND
                )
                completedStrokes.add(rawStroke)
                onStrokeCompleted?.invoke(rawStroke)

                // Notify coordinator to create diagram area around the shape
                onDiagramShapeDetected?.invoke(rawStroke)

                val snappedStroke = InkStroke(
                    points = currentStrokePoints.toList(),
                    isGeometric = snapData.isGeometric,
                    strokeType = snapData.strokeType
                )
                completedStrokes.remove(rawStroke)
                completedStrokes.add(snappedStroke)
                onStrokeReplaced?.invoke(rawStroke.strokeId, snappedStroke)

                currentStrokePoints.clear()
                currentPath.reset()
                currentDiagramBounds = null

                // Flush SDK hardware overlay, redraw clean snapped shape
                pauseRawDrawing()
                drawToSurface()
                resumeRawDrawing()
            } else if (dwellIndicatorShown) {
                // Start-dwell without shape → create freeform diagram zone
                val stroke = InkStroke(
                    points = currentStrokePoints.toList(),
                    isGeometric = false,
                    strokeType = StrokeType.FREEHAND
                )
                completedStrokes.add(stroke)
                onStrokeCompleted?.invoke(stroke)
                onDiagramShapeDetected?.invoke(stroke)
                dwellDotCenter = null
                dwellIndicatorShown = false
                currentStrokePoints.clear()
                currentPath.reset()
                currentDiagramBounds = null

                // Show dashed border, then clear preview state
                pauseRawDrawing()
                drawToSurface()
                resumeRawDrawing()
    
            } else {
                val stroke = InkStroke(points = currentStrokePoints.toList())
                completedStrokes.add(stroke)
                onStrokeCompleted?.invoke(stroke)
                currentStrokePoints.clear()
                currentPath.reset()
                currentDiagramBounds = null
    
            }
        }
    }

    // --- Diagram area helpers ---

    /** Check if a document-space Y coordinate falls inside a diagram area. */
    private fun isInDiagramArea(docY: Float): Boolean {
        val lineIdx = ((docY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)
        return diagramAreas.any { it.containsLine(lineIdx) }
    }

    /** Get the Y-bounds (topY, bottomY) of the diagram area containing [docY], or null. */
    private fun getDiagramBounds(docY: Float): Pair<Float, Float>? {
        val lineIdx = ((docY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)
        val area = diagramAreas.find { it.containsLine(lineIdx) } ?: return null
        val topY = TOP_MARGIN + area.startLineIndex * LINE_SPACING
        val bottomY = TOP_MARGIN + (area.startLineIndex + area.heightInLines) * LINE_SPACING
        return Pair(topY, bottomY)
    }

    // ── Diagram-area post-stroke detection ──────────────────────────────────

    /** Result of shape snap: strokeType + isGeometric flag. */
    private data class SnapData(
        val strokeType: StrokeType,
        val isGeometric: Boolean
    )

    /**
     * Attempt to snap the completed stroke to a known geometric shape.
     * Only called for strokes inside diagram areas.
     */
    private fun checkShapeSnap(tailDwell: Boolean = false): SnapData? {
        if (currentStrokePoints.size < 2) return null

        // Shape snapping requires a dwell at the end of the stroke — the user
        // holds the pen still briefly to signal "snap this to a shape".
        // Without the dwell, the stroke is treated as freehand.
        val last = currentStrokePoints.last()
        val hasEndDwell = ArrowDwellDetection.hasDwellAtEnd(
            currentStrokePoints, last.x, last.y, ARROW_DWELL_RADIUS_PX, ARROW_DWELL_MS
        )
        if (!hasEndDwell) return null

        val xs = FloatArray(currentStrokePoints.size) { currentStrokePoints[it].x }
        val ys = FloatArray(currentStrokePoints.size) { currentStrokePoints[it].y }
        val result = ShapeSnapDetection.detect(xs, ys, LINE_SPACING)
        if (result == null) return null

        val t = currentStrokePoints.first().timestamp
        var strokeType = StrokeType.FREEHAND
        var isGeometric = false

        // Magnetic snap: resolve arrow endpoints to nearest shape perimeters
        val nodes = documentModel?.diagram?.nodes ?: emptyMap()
        val magnetThreshold = MAGNET_THRESHOLD_SPANS * LINE_SPACING

        fun magnetSnap(x1: Float, y1: Float, x2: Float, y2: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
            if (nodes.isEmpty()) return Pair(x1 to y1, x2 to y2)
            val (from, to, _) = DiagramNodeSnap.snapArrowEndpointsRaw(
                x1, y1, x2, y2, nodes, magnetThreshold
            )
            return Pair(from, to)
        }

        val snappedPoints: List<StrokePoint> = when (result) {
            is ShapeSnapDetection.SnapResult.Line -> {
                val tipDwell = ArrowDwellDetection.hasDwellAtEnd(
                    currentStrokePoints, result.x2, result.y2, ARROW_DWELL_RADIUS_PX, ARROW_DWELL_MS
                )
                strokeType = ArrowDwellDetection.classifyArrow(tipDwell, tailDwell)
                isGeometric = true

                val (from, to) = magnetSnap(result.x1, result.y1, result.x2, result.y2)
                listOf(
                    StrokePoint(from.first, from.second, 0f, t),
                    StrokePoint(to.first, to.second, 0f, t)
                )
            }
            is ShapeSnapDetection.SnapResult.Arrow -> return null
            is ShapeSnapDetection.SnapResult.Elbow -> {
                val tipDwell = ArrowDwellDetection.hasDwellAtEnd(
                    currentStrokePoints, result.x2, result.y2, ARROW_DWELL_RADIUS_PX, ARROW_DWELL_MS
                )
                strokeType = ArrowDwellDetection.classifyElbow(tipDwell, tailDwell)
                isGeometric = true

                val (from, to) = magnetSnap(result.x1, result.y1, result.x2, result.y2)
                listOf(
                    StrokePoint(from.first, from.second, 0f, t),
                    StrokePoint(result.cx, result.cy, 0f, t),
                    StrokePoint(to.first, to.second, 0f, t)
                )
            }
            is ShapeSnapDetection.SnapResult.Arc -> {
                val tipDwell = ArrowDwellDetection.hasDwellAtEnd(
                    currentStrokePoints, result.x2, result.y2, ARROW_DWELL_RADIUS_PX, ARROW_DWELL_MS
                )
                strokeType = ArrowDwellDetection.classifyArc(tipDwell, tailDwell)
                isGeometric = false

                val (from, to) = magnetSnap(result.x1, result.y1, result.x2, result.y2)
                listOf(
                    StrokePoint(from.first, from.second, 0f, t),
                    StrokePoint(result.cx, result.cy, 0f, t),
                    StrokePoint(to.first, to.second, 0f, t)
                )
            }
            is ShapeSnapDetection.SnapResult.Ellipse -> {
                strokeType = StrokeType.ELLIPSE
                val n = 60
                (0..n).map { i ->
                    val angle = 2 * Math.PI * i / n
                    StrokePoint(
                        (result.cx + result.a * cos(angle)).toFloat(),
                        (result.cy + result.b * sin(angle)).toFloat(),
                        0f, t
                    )
                }
            }
            is ShapeSnapDetection.SnapResult.RoundedRectangle -> {
                strokeType = StrokeType.ROUNDED_RECTANGLE
                val r = result.cornerRadius.coerceAtMost(
                    minOf(result.right - result.left, result.bottom - result.top) / 2f
                )
                val cl = result.left + r;  val cr = result.right - r
                val ct = result.top + r;   val cb = result.bottom - r
                val arcN = 8
                val list = mutableListOf<StrokePoint>()
                fun arc(cx: Float, cy: Float, startDeg: Double, endDeg: Double) {
                    for (i in 0 until arcN) {
                        val a = Math.toRadians(startDeg + (endDeg - startDeg) * i / arcN)
                        list += StrokePoint((cx + r * cos(a)).toFloat(),
                                            (cy + r * sin(a)).toFloat(), 0f, t)
                    }
                }
                arc(cr, ct, -90.0,   0.0);  list += StrokePoint(result.right, cb, 0f, t)
                arc(cr, cb,   0.0,  90.0);  list += StrokePoint(cl, result.bottom, 0f, t)
                arc(cl, cb,  90.0, 180.0);  list += StrokePoint(result.left, ct, 0f, t)
                arc(cl, ct, 180.0, 270.0);  list += StrokePoint(cr, result.top, 0f, t)
                list
            }
            is ShapeSnapDetection.SnapResult.Rectangle -> {
                strokeType = StrokeType.RECTANGLE
                isGeometric = true
                listOf(
                    StrokePoint(result.left,  result.top,    0f, t),
                    StrokePoint(result.right, result.top,    0f, t),
                    StrokePoint(result.right, result.bottom, 0f, t),
                    StrokePoint(result.left,  result.bottom, 0f, t),
                    StrokePoint(result.left,  result.top,    0f, t),
                )
            }
            is ShapeSnapDetection.SnapResult.Diamond -> {
                strokeType = StrokeType.DIAMOND
                isGeometric = true
                val cx = (result.left + result.right) / 2f
                val cy = (result.top + result.bottom) / 2f
                listOf(
                    StrokePoint(cx,           result.top,    0f, t),
                    StrokePoint(result.right, cy,            0f, t),
                    StrokePoint(cx,           result.bottom, 0f, t),
                    StrokePoint(result.left,  cy,            0f, t),
                    StrokePoint(cx,           result.top,    0f, t),
                )
            }
            is ShapeSnapDetection.SnapResult.Triangle -> {
                strokeType = StrokeType.TRIANGLE
                isGeometric = true
                listOf(
                    StrokePoint(result.x1, result.y1, 0f, t),
                    StrokePoint(result.x2, result.y2, 0f, t),
                    StrokePoint(result.x3, result.y3, 0f, t),
                    StrokePoint(result.x1, result.y1, 0f, t),
                )
            }
            is ShapeSnapDetection.SnapResult.Curve -> {
                val tipDwell = ArrowDwellDetection.hasDwellAtEnd(
                    currentStrokePoints, last.x, last.y, ARROW_DWELL_RADIUS_PX, ARROW_DWELL_MS
                )
                strokeType = ArrowDwellDetection.classifyArc(tipDwell, tailDwell)
                isGeometric = false

                result.points.map { (x, y) -> StrokePoint(x, y, 0f, t) }
            }
            is ShapeSnapDetection.SnapResult.SelfLoop -> {
                val tipDwell = ArrowDwellDetection.hasDwellAtEnd(
                    currentStrokePoints, last.x, last.y, ARROW_DWELL_RADIUS_PX, ARROW_DWELL_MS
                )
                strokeType = ArrowDwellDetection.classifyArc(tipDwell, tailDwell)
                isGeometric = false

                val nPts = 40
                (0..nPts).map { i ->
                    val angle = result.startAngle + result.sweepAngle * i.toFloat() / nPts
                    StrokePoint(
                        (result.cx + result.rx * cos(angle.toDouble())).toFloat(),
                        (result.cy + result.ry * sin(angle.toDouble())).toFloat(),
                        0f, t
                    )
                }
            }
        }
        currentStrokePoints.clear()
        currentStrokePoints.addAll(snappedPoints)
        Log.i(TAG, "Shape snap: $result → $strokeType")

        return SnapData(strokeType, isGeometric)
    }

    /** Check if the completed stroke is a scratch-out erase gesture. */
    private fun checkPostStrokeScratchOut(): Boolean {
        val diagonal = hypot(strokeMaxX - strokeMinX, strokeMaxY - strokeMinY)
        val first = currentStrokePoints.first()
        val last  = currentStrokePoints.last()
        val closeDist = hypot(last.x - first.x, last.y - first.y)
        val isClosedLoop = diagonal > 0f && closeDist < ShapeSnapDetection.CLOSE_FRACTION * diagonal

        val xs = FloatArray(currentStrokePoints.size) { currentStrokePoints[it].x }
        val yRange = strokeMaxY - strokeMinY
        if (!ScratchOutDetection.detect(xs, yRange, LINE_SPACING, isClosedLoop)) return false

        val left = strokeMinX; val top = strokeMinY
        val right = strokeMaxX; val bottom = strokeMaxY

        // Only treat as scratch-out if:
        // 1) It intersects existing strokes, AND
        // 2) The bulk of its path is focused on crossing those strokes
        //    (not just grazing a descender while writing new text)
        val scratchPoints = currentStrokePoints.toList()
        if (!ScratchOutDetection.isFocusedScratchOut(scratchPoints, completedStrokes)) return false

        currentStrokePoints.clear()
        currentPath.reset()

        pauseRawDrawing()
        onScratchOut?.invoke(scratchPoints, left, top, right, bottom)
        resumeRawDrawing()

        Log.i(TAG, "Post-stroke scratch-out: region=[$left,$top,$right,$bottom]")
        return true
    }

    // ── Dwell helpers ─────────────────────────────────────────────────────────

    private fun startDwellJob() {
        dwellIndicatorShown = false
        dwellDotCenter = null
        val job = Runnable {
            val pts = currentStrokePoints
            if (pts.isEmpty()) return@Runnable
            val first = pts.first()
            val last = pts.lastOrNull() ?: return@Runnable
            val dx = last.x - first.x
            val dy = last.y - first.y
            if (dx * dx + dy * dy < ARROW_DWELL_RADIUS_PX * ARROW_DWELL_RADIUS_PX) {
                dwellIndicatorShown = true
                dwellDotCenter = PointF(first.x, first.y)
                // Draw indicator to surface; on Boox the SDK overlay may obscure it
                // mid-stroke, but the dwell state is preserved for finishStroke().
                // Do NOT pause/resume SDK here — it triggers onBeginRawDrawing
                // which resets the stroke + dwell state.
                drawToSurface()
            }
        }
        dwellJob = job
        handler.postDelayed(job, ARROW_DWELL_MS)
    }

    private fun cancelDwellJob() {
        dwellJob?.let { handler.removeCallbacks(it) }
        dwellJob = null
    }

    // ── Running stroke bounding box ───────────────────────────────────────────

    /**
     * Initialise the running stroke bounding box from the first point of a new stroke.
     * Must be called once at stroke start (ACTION_DOWN / onBeginRawDrawing).
     */
    private fun initStrokeBounds(p: StrokePoint) {
        strokeMinX = p.x; strokeMaxX = p.x
        strokeMinY = p.y; strokeMaxY = p.y
    }

    /**
     * Expand the running bounding box to include [p].
     *
     * Called in O(1) on every incoming point so that post-stroke detection can read
     * xRange and yRange in O(1) rather than scanning [currentStrokePoints] each time.
     *
     * The naive approach called currentStrokePoints.maxOf/minOf on every point:
     * O(n) per point → O(n²) per stroke. Profiling with STROKE_DIAG confirmed
     * this cost: 136 ms for a 551-point stroke, 267 ms for a 781-point stroke.
     * With running bounds the total cost across the whole stroke is O(n);
     * the same strokes measured 3 ms and were not observed in the new session.
     */
    private fun updateStrokeBounds(p: StrokePoint) {
        if (p.x < strokeMinX) strokeMinX = p.x
        if (p.x > strokeMaxX) strokeMaxX = p.x
        if (p.y < strokeMinY) strokeMinY = p.y
        if (p.y > strokeMaxY) strokeMaxY = p.y
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

        val canvasRight = width.toFloat()

        // Apply scroll offset
        canvas.save()
        canvas.translate(0f, -scrollOffsetY)

        // Draw ruled lines (text lines show through diagram areas)
        val maxDocY = scrollOffsetY + height + LINE_SPACING
        var lineY = TOP_MARGIN + LINE_SPACING
        while (lineY < maxDocY) {
            canvas.drawLine(0f, lineY, canvasRight, lineY, linePaint)
            lineY += LINE_SPACING
        }

        // Only draw strokes within the visible viewport
        val viewTop = scrollOffsetY
        val viewBottom = scrollOffsetY + height
        for (stroke in completedStrokes) {
            if (stroke.maxY >= viewTop && stroke.minY <= viewBottom) {
                drawStroke(canvas, stroke)
            }
        }

        // Draw current in-progress stroke (fallback only)
        if (!useOnyxSdk && currentStrokePoints.size > 1) {
            canvas.drawPath(currentPath, strokePaint)
        }

        // Draw dwell indicator dot (start-dwell)
        dwellDotCenter?.let { dot ->
            canvas.drawCircle(dot.x, dot.y, CanvasTheme.DEFAULT_STROKE_WIDTH * 3f, dwellDotPaint)
        }

        // Draw dashed borders around all diagram areas
        for (area in diagramAreas) {
            val topY = TOP_MARGIN + area.startLineIndex * LINE_SPACING
            val bottomY = TOP_MARGIN + (area.startLineIndex + area.heightInLines) * LINE_SPACING
            canvas.drawRect(0f, topY, canvasRight, bottomY, freeformPreviewPaint)
        }

        canvas.restore()

        // Draw tutorial annotations on top of everything
        if (annotationStrokes.isNotEmpty() || textAnnotations.isNotEmpty()) {
            canvas.save()
            canvas.translate(0f, -scrollOffsetY)
            for (annotation in annotationStrokes) {
                if (annotation.points.size < 2) continue
                renderPath.reset()
                renderPath.moveTo(annotation.points[0].x, annotation.points[0].y)
                for (i in 1 until annotation.points.size) {
                    renderPath.lineTo(annotation.points[i].x, annotation.points[i].y)
                }
                annotationPaint.color = annotation.color
                annotationPaint.strokeWidth = annotation.strokeWidth
                canvas.drawPath(renderPath, annotationPaint)
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
        CanvasTheme.drawStroke(canvas, stroke, renderPath, strokePaint)
    }

    /** Fully close Onyx SDK raw drawing session (e.g. before launching another activity). */
    fun closeRawDrawing() {
        if (useOnyxSdk) {
            try {
                touchHelper?.closeRawDrawing()
                useOnyxSdk = false
                Log.i(TAG, "Onyx SDK raw drawing closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing raw drawing: ${e.message}")
            }
        }
    }

    /** Reopen Onyx SDK raw drawing after it was closed (e.g. returning from another activity). */
    fun reopenRawDrawing() {
        if (!surfaceReady) return
        if (useOnyxSdk) return
        tryInitOnyx()
        drawToSurface()
    }

    /** Fully close and reopen the SDK to reset internal state (e.g. after canvas resize). */
    fun reinitializeRawDrawing() {
        if (useOnyxSdk) {
            try {
                touchHelper?.closeRawDrawing()
                useOnyxSdk = false
            } catch (e: Exception) {
                Log.w(TAG, "Error closing SDK for reinit: ${e.message}")
            }
        }
        if (surfaceReady) {
            tryInitOnyx()
            drawToSurface()
        }
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
        val maxStrokeY = completedStrokes.maxOf { it.maxY }
        return (maxStrokeY - height / 2f).coerceAtLeast(0f)
    }

    fun clear() {
        completedStrokes.clear()
        currentStrokePoints.clear()
        currentPath.reset()
        scrollOffsetY = 0f
        textOverscroll = 0f
        diagramAreas = emptyList()
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
