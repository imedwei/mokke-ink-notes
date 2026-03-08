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
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minY
import com.writer.model.maxY

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
        // Line spacing, top margin and gutter width are DPI-scaled via ScreenMetrics.
        val LINE_SPACING get() = ScreenMetrics.lineSpacing
        // Idle timeout before checking scroll condition (ms)
        private const val IDLE_TIMEOUT_MS = 2000L
        // Top margin before the first line
        val TOP_MARGIN get() = ScreenMetrics.topMargin
        // Width of the scroll gutter on the right edge
        val GUTTER_WIDTH get() = ScreenMetrics.gutterWidth
        // Line-drag gesture: vertical span to activate (either direction)
        private const val LINE_DRAG_MIN_SPANS = 1f
        // Line-drag gesture: max horizontal drift ratio during activation
        private const val LINE_DRAG_MAX_DRIFT = 0.3f
        // Undo gesture: minimum horizontal span to detect initial stroke
        private const val UNDO_HORIZONTAL_MIN_SPANS = 1.5f
        // Undo gesture: max vertical drift ratio during horizontal stroke
        private const val UNDO_MAX_VERTICAL_DRIFT = 0.2f
        // Undo gesture: vertical span (in line spacings) to activate after horizontal stroke
        private const val UNDO_VERTICAL_ACTIVATION = 0.75f
        // Undo scrub: ~1.8 mm per undo/redo step, scaled to device DPI
        private val UNDO_STEP_SIZE get() = ScreenMetrics.dp(11f)
        // Diagram insert: fraction of stroke to analyze for scribble detection
        private const val SCRIBBLE_SEGMENT_FRACTION = 0.4f
        // Diagram insert: path-length / displacement ratio threshold for scribble
        private const val SCRIBBLE_MIN_COMPLEXITY = 3.0f
        // Diagram insert: minimum height in lines
        private const val DIAGRAM_MIN_HEIGHT = 2
    }

    private val completedStrokes = mutableListOf<InkStroke>()
    private val currentStrokePoints = mutableListOf<StrokePoint>()
    private val currentPath = Path()
    // Reused during rendering to avoid allocating a new Path per stroke per frame
    private val renderPath = Path()

    private val strokePaint = CanvasTheme.newStrokePaint()
    private val linePaint = CanvasTheme.newLinePaint()
    private val gutterPaint = CanvasTheme.newGutterFillPaint()
    private val gutterLinePaint = CanvasTheme.newGutterLinePaint()
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

    /** When true, all pen input is blocked and annotations are rendered. */
    var tutorialMode = false
    var annotationStrokes: List<AnnotationStroke> = emptyList()
    var textAnnotations: List<TextAnnotation> = emptyList()

    var onStrokeCompleted: ((InkStroke) -> Unit)? = null
    var onIdleTimeout: (() -> Unit)? = null
    /** Called when manual scrolling changes the offset. */
    var onManualScroll: (() -> Unit)? = null

    // Line-drag gesture callbacks
    var onLineDragStart: ((anchorLine: Int) -> Unit)? = null
    var onLineDragStep: ((shiftLines: Int) -> Unit)? = null
    var onLineDragEnd: (() -> Unit)? = null

    // Diagram insert gesture callbacks
    var onDiagramInsertStart: ((anchorLine: Int) -> Unit)? = null
    var onDiagramInsertStep: ((heightInLines: Int) -> Unit)? = null
    var onDiagramInsertEnd: (() -> Unit)? = null

    // Undo scrub callbacks (horizontal stroke then vertical movement)
    var onUndoGestureStart: (() -> Unit)? = null
    var onUndoGestureStep: ((absoluteOffset: Int) -> Unit)? = null
    var onUndoGestureEnd: (() -> Unit)? = null

    /** Scroll offset in document-space pixels. Increase to scroll content up. */
    var scrollOffsetY: Float = 0f

    /** Extra scroll past the top of the document, for scrolling the text view. */
    var textOverscroll: Float = 0f

    // Gutter scrolling state
    private var isGutterDragging = false
    private var gutterDragLastY = 0f

    // Line-drag gesture state
    private var lineDragActive = false
    private var lineDragStartScreenY = 0f
    private var lineDragLastShift = 0

    // Diagram insert gesture state
    private var diagramInsertActive = false
    private var diagramInsertStartScreenY = 0f
    private var diagramInsertLastHeight = 0

    // Undo gesture state: horizontal stroke → vertical scrub
    private var undoGestureReady = false
    private var undoReadyScreenY = 0f
    private var undoScrubActive = false
    private var undoScrubTriggerY = 0f
    private var undoScrubLastStep = 0

    // Diagram drawing bounds: when stroke starts in a diagram area, Y is clamped to these bounds
    private var currentDiagramBounds: Pair<Float, Float>? = null // (topY, bottomY) in doc space
    // Whether we've temporarily changed the Onyx SDK limit rect for a diagram stroke
    private var diagramLimitActive = false

    private val idleRunnable = Runnable { onIdleTimeout?.invoke() }

    private var useOnyxSdk = false
    private var touchHelper: TouchHelper? = null
    private var surfaceReady = false

    // ── Running stroke bounding box ───────────────────────────────────────────
    // checkGestures needs xRange and yRange of the stroke so far.  Maintaining
    // a running bounding box updated O(1) per point avoids an O(n²) scan.
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
            handler.removeCallbacks(idleRunnable)
            currentStrokePoints.clear()
            val docPt = tp.toDocStrokePoint()
            currentDiagramBounds = getDiagramBounds(docPt.y)
            if (currentDiagramBounds != null) {
                val (topY, bottomY) = currentDiagramBounds!!
                setDiagramLimitRect(topY, bottomY)
            } else if (diagramLimitActive) {
                // Safety: previous diagram limit wasn't cleaned up
                restoreLimitRect()
            }
            currentStrokePoints.add(docPt)
            lineDragActive = false
            diagramInsertActive = false
            undoGestureReady = false
            undoScrubActive = false
            initStrokeBounds(currentStrokePoints.last())
        }

        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            if (lineDragActive || diagramInsertActive || undoScrubActive) {
                // SDK buffer dump after we disabled it — ignore.
                // Real movement comes via onTouchEvent now.
                return
            }
            val docPt = tp.toDocStrokePoint()
            currentDiagramBounds?.let { (topY, bottomY) ->
                if (docPt.y < topY || docPt.y > bottomY) return
            }
            currentStrokePoints.add(docPt)
            updateStrokeBounds(currentStrokePoints.last())
            if (undoGestureReady) {
                // Horizontal threshold met — check for vertical activation
                processUndoReadyMove(tp.y)
            } else {
                checkGestures(tp.x, tp.y)
            }
        }

        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {
            // Batch delivery — used by SDK after stroke ends
        }

        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            Log.d(TAG, "onEndRawDrawing: ${currentStrokePoints.size} points, lineDrag=$lineDragActive, diagramInsert=$diagramInsertActive, undoReady=$undoGestureReady, undoScrub=$undoScrubActive")
            if (lineDragActive || diagramInsertActive || undoScrubActive) {
                // SDK fires this when disabled mid-stroke (buffer dump).
                // Ignore it — the real pen-up comes via onTouchEvent.
                Log.d(TAG, "Ignoring SDK onEndRawDrawing during interactive gesture")
                return
            }
            // If undoGestureReady but not undoScrubActive, the user drew a
            // horizontal stroke without going vertical — treat as normal stroke
            // (e.g. strikethrough).
            undoGestureReady = false
            val docPt = tp.toDocStrokePoint()
            currentDiagramBounds?.let { (topY, bottomY) ->
                if (docPt.y >= topY && docPt.y <= bottomY) {
                    currentStrokePoints.add(docPt)
                }
            } ?: currentStrokePoints.add(docPt)
            finishStroke()
            if (diagramLimitActive) {
                // Pen exited diagram limit rect or lifted inside diagram.
                // DON'T restore limit rect here — if pen exited, it's still
                // physically down and restoring would let the SDK start a new
                // stroke outside the diagram. Restore on true pen-up instead
                // (via onTouchEvent ACTION_UP).
                drawToSurface()
                return
            }
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
        useOnyxSdk = false
    }

    private fun tryInitOnyx() {
        if (useOnyxSdk) return // already initialized

        try {
            val limit = Rect()
            getLocalVisibleRect(limit)
            limit.right = (limit.right - GUTTER_WIDTH).toInt()

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

        // If an interactive gesture is active, we've disabled the SDK and handle here
        if (lineDragActive) {
            return handleLineDragTouch(event)
        }
        if (diagramInsertActive) {
            return handleDiagramInsertTouch(event)
        }
        if (undoScrubActive) {
            return handleUndoTouch(event)
        }

        // In tutorial mode, block all writing input but allow gutter (handled above)
        if (tutorialMode) return false

        // If using Onyx SDK, pen input in the canvas area is handled by SDK callbacks
        if (useOnyxSdk) {
            // Detect true pen-up to restore limit rect after diagram stroke
            if (diagramLimitActive && event.action == MotionEvent.ACTION_UP) {
                restoreLimitRect()
                drawToSurface()
                handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
            }
            return true
        }

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
                currentDiagramBounds = getDiagramBounds(y)
                currentPath.moveTo(x, y)
                currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                lineDragActive = false
                diagramInsertActive = false
                undoGestureReady = false
                undoScrubActive = false
                initStrokeBounds(currentStrokePoints.last())
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val bounds = currentDiagramBounds
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i) + scrollOffsetY
                    val hp = event.getHistoricalPressure(i)
                    val ht = event.getHistoricalEventTime(i)
                    if (bounds == null || (hy >= bounds.first && hy <= bounds.second)) {
                        currentPath.lineTo(hx, hy)
                        currentStrokePoints.add(StrokePoint(hx, hy, hp, ht))
                        updateStrokeBounds(currentStrokePoints.last())
                    }
                }
                if (bounds == null || (y >= bounds.first && y <= bounds.second)) {
                    currentPath.lineTo(x, y)
                    currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                    updateStrokeBounds(currentStrokePoints.last())
                }
                checkGesturesFallback(event.x, event.y)
                if (lineDragActive || diagramInsertActive || undoScrubActive) return true
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (lineDragActive) {
                    endLineDrag()
                    return true
                }
                if (diagramInsertActive) {
                    endDiagramInsert()
                    return true
                }
                if (undoScrubActive) {
                    endUndoGesture()
                    return true
                }
                // If undoGestureReady but not active, treat as normal stroke
                undoGestureReady = false
                val upBounds = currentDiagramBounds
                if (upBounds == null || (y >= upBounds.first && y <= upBounds.second)) {
                    currentPath.lineTo(x, y)
                    currentStrokePoints.add(StrokePoint(x, y, pressure, timestamp))
                }
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
                if (textOverscroll > 0f && dy > 0f) {
                    // Scrolling back down — reduce text overscroll first
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
                if (!isGutterDragging) return false
                isGutterDragging = false
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
            return
        }
        val stroke = InkStroke(points = currentStrokePoints.toList())
        completedStrokes.add(stroke)
        onStrokeCompleted?.invoke(stroke)
        currentStrokePoints.clear()
        currentPath.reset()
        currentDiagramBounds = null
    }

    // --- Gesture detection ---

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

    /** Temporarily constrain Onyx SDK drawing area to the diagram bounds (screen coords). */
    private fun setDiagramLimitRect(topY: Float, bottomY: Float) {
        if (!useOnyxSdk) return
        try {
            val limit = Rect()
            limit.left = 0
            limit.right = (width - GUTTER_WIDTH).toInt()
            limit.top = (topY - scrollOffsetY).toInt().coerceAtLeast(0)
            limit.bottom = (bottomY - scrollOffsetY).toInt().coerceAtMost(height)
            touchHelper?.setLimitRect(limit, emptyList())
            diagramLimitActive = true
        } catch (e: Exception) {
            Log.w(TAG, "Error setting diagram limit rect: ${e.message}")
        }
    }

    /** Restore Onyx SDK drawing area to the full canvas minus gutter. */
    private fun restoreLimitRect() {
        if (!diagramLimitActive || !useOnyxSdk) return
        try {
            val limit = Rect()
            getLocalVisibleRect(limit)
            limit.right = (limit.right - GUTTER_WIDTH).toInt()
            touchHelper?.setLimitRect(limit, emptyList())
            diagramLimitActive = false
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring limit rect: ${e.message}")
        }
    }

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
     * Called in O(1) on every incoming point so that [checkGestures] can read
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

    /**
     * Check if the in-progress stroke qualifies as a gesture.
     * Called during Onyx SDK move events with screen-space coordinates.
     * Detects vertical strokes (line-drag) and horizontal strokes (undo).
     *
     * xRange and yRange are read from the running bounding box — O(1) per call.
     */
    private fun checkGestures(screenX: Float, screenY: Float) {
        if (lineDragActive || diagramInsertActive || undoGestureReady || undoScrubActive) return
        if (currentStrokePoints.size < 3) return
        // No gestures start inside diagram areas
        if (isInDiagramArea(currentStrokePoints.first().y)) return

        val yDelta = currentStrokePoints.last().y - currentStrokePoints.first().y
        val absYDelta = kotlin.math.abs(yDelta)
        val xRange = strokeMaxX - strokeMinX
        val yRange = strokeMaxY - strokeMinY

        // Vertical stroke → line-drag or diagram insert
        if (absYDelta > LINE_DRAG_MIN_SPANS * LINE_SPACING) {
            if (yDelta > 0 && isScribbleStart()) {
                // Scribble then downward → diagram insert
                activateDiagramInsert(screenX, screenY)
            } else if (xRange < absYDelta * LINE_DRAG_MAX_DRIFT) {
                activateLineDrag(screenX, screenY)
            }
            return
        }

        // Horizontal stroke → undo ready
        if (xRange > UNDO_HORIZONTAL_MIN_SPANS * LINE_SPACING && yRange < xRange * UNDO_MAX_VERTICAL_DRIFT) {
            activateUndoReady(screenY)
        }
    }

    /**
     * Fallback version of [checkGestures] for the non-Onyx touch path.
     * Also handles in-progress step updates for active gestures inline.
     * Reads xRange/yRange from the running bounding box — same O(1) approach.
     */
    private fun checkGesturesFallback(screenX: Float, screenY: Float) {
        if (undoScrubActive) {
            // Already in undo scrub — process vertical movement
            val steps = ((screenY - undoScrubTriggerY) / UNDO_STEP_SIZE).toInt()
            if (steps != undoScrubLastStep) {
                onUndoGestureStep?.invoke(steps)
                undoScrubLastStep = steps
            }
            return
        }
        if (undoGestureReady) {
            // Waiting for vertical activation
            processUndoReadyMove(screenY)
            return
        }
        if (diagramInsertActive) {
            processDiagramInsertMove(screenY)
            return
        }
        if (lineDragActive) {
            // Already in line-drag — process vertical movement
            processLineDragMove(screenY)
            return
        }
        if (currentStrokePoints.size < 3) return
        // No gestures start inside diagram areas
        if (isInDiagramArea(currentStrokePoints.first().y)) return

        val yDelta = currentStrokePoints.last().y - currentStrokePoints.first().y
        val absYDelta = kotlin.math.abs(yDelta)
        val xRange = strokeMaxX - strokeMinX
        val yRange = strokeMaxY - strokeMinY

        // Vertical stroke → line-drag or diagram insert
        if (absYDelta > LINE_DRAG_MIN_SPANS * LINE_SPACING) {
            if (yDelta > 0 && isScribbleStart()) {
                activateDiagramInsert(screenX, screenY)
            } else if (xRange < absYDelta * LINE_DRAG_MAX_DRIFT) {
                activateLineDrag(screenX, screenY)
            }
            return
        }

        // Horizontal stroke → undo ready
        if (xRange > UNDO_HORIZONTAL_MIN_SPANS * LINE_SPACING && yRange < xRange * UNDO_MAX_VERTICAL_DRIFT) {
            activateUndoReady(screenY)
        }
    }

    private fun activateLineDrag(screenX: Float, screenY: Float) {
        lineDragActive = true

        val anchorDocY = currentStrokePoints.first().y
        val anchorLine = ((anchorDocY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)

        // Use the anchor line's screen position as the reference, so the
        // grabbed line tracks directly under the pen from the start.
        lineDragStartScreenY = TOP_MARGIN + anchorLine * LINE_SPACING - scrollOffsetY
        lineDragLastShift = 0

        currentStrokePoints.clear()
        currentPath.reset()

        // Disable SDK so onTouchEvent receives all subsequent move/up events
        if (useOnyxSdk) {
            try {
                touchHelper?.setRawDrawingEnabled(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error disabling SDK for line-drag: ${e.message}")
            }
        }

        onLineDragStart?.invoke(anchorLine)
        Log.i(TAG, "Line-drag activated: anchorLine=$anchorLine, screenY=$screenY")
    }

    /**
     * Handle touch events during an active line-drag gesture.
     * The SDK is disabled, so we receive all move/up events here.
     */
    private fun handleLineDragTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                processLineDragMove(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endLineDrag()
                return true
            }
        }
        return true
    }

    private fun processLineDragMove(screenY: Float) {
        val delta = screenY - lineDragStartScreenY
        val shiftLines = kotlin.math.floor(delta / LINE_SPACING).toInt()
        if (shiftLines != lineDragLastShift) {
            lineDragLastShift = shiftLines
            onLineDragStep?.invoke(shiftLines)
        }
    }

    private fun endLineDrag() {
        lineDragActive = false
        lineDragLastShift = 0
        currentStrokePoints.clear()
        currentPath.reset()
        onLineDragEnd?.invoke()
        finishInteractiveGesture()
        Log.i(TAG, "Line-drag ended")
    }

    // --- Diagram insert gesture (scribble → downward drag) ---

    /** Check if the first segment of the stroke is a scribble (high path complexity). */
    private fun isScribbleStart(): Boolean {
        val points = currentStrokePoints
        if (points.size < 10) return false

        val segmentEnd = (points.size * SCRIBBLE_SEGMENT_FRACTION).toInt().coerceAtLeast(5)
        val segment = points.subList(0, segmentEnd)

        var pathLen = 0f
        for (i in 1 until segment.size) {
            val dx = segment[i].x - segment[i - 1].x
            val dy = segment[i].y - segment[i - 1].y
            pathLen += kotlin.math.sqrt(dx * dx + dy * dy)
        }

        val dx = segment.last().x - segment.first().x
        val dy = segment.last().y - segment.first().y
        val displacement = kotlin.math.sqrt(dx * dx + dy * dy)
        if (displacement < 1f) return true

        return pathLen / displacement > SCRIBBLE_MIN_COMPLEXITY
    }

    private fun activateDiagramInsert(screenX: Float, screenY: Float) {
        diagramInsertActive = true

        val anchorDocY = currentStrokePoints.first().y
        val anchorLine = ((anchorDocY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)

        diagramInsertStartScreenY = TOP_MARGIN + anchorLine * LINE_SPACING - scrollOffsetY
        diagramInsertLastHeight = 0

        currentStrokePoints.clear()
        currentPath.reset()

        if (useOnyxSdk) {
            try {
                touchHelper?.setRawDrawingEnabled(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error disabling SDK for diagram insert: ${e.message}")
            }
        }

        onDiagramInsertStart?.invoke(anchorLine)
        Log.i(TAG, "Diagram insert activated: anchorLine=$anchorLine")
    }

    private fun handleDiagramInsertTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                processDiagramInsertMove(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDiagramInsert()
                return true
            }
        }
        return true
    }

    private fun processDiagramInsertMove(screenY: Float) {
        val delta = screenY - diagramInsertStartScreenY
        val heightInLines = kotlin.math.floor(delta / LINE_SPACING).toInt()
            .coerceAtLeast(DIAGRAM_MIN_HEIGHT)
        if (heightInLines != diagramInsertLastHeight) {
            diagramInsertLastHeight = heightInLines
            onDiagramInsertStep?.invoke(heightInLines)
        }
    }

    private fun endDiagramInsert() {
        diagramInsertActive = false
        diagramInsertLastHeight = 0
        currentStrokePoints.clear()
        currentPath.reset()
        onDiagramInsertEnd?.invoke()
        finishInteractiveGesture()
        Log.i(TAG, "Diagram insert ended")
    }

    // --- Undo gesture (horizontal stroke → vertical scrub) ---

    private fun activateUndoReady(screenY: Float) {
        undoGestureReady = true
        undoReadyScreenY = screenY
        Log.i(TAG, "Undo gesture ready (horizontal stroke detected), screenY=$screenY")
    }

    private fun handleUndoTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val steps = ((event.y - undoScrubTriggerY) / UNDO_STEP_SIZE).toInt()
                if (steps != undoScrubLastStep) {
                    undoScrubLastStep = steps
                    onUndoGestureStep?.invoke(steps)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endUndoGesture()
                return true
            }
        }
        return true
    }

    private fun processUndoReadyMove(screenY: Float) {
        if (kotlin.math.abs(screenY - undoReadyScreenY) > UNDO_VERTICAL_ACTIVATION * LINE_SPACING) {
            // Vertical movement confirmed — now disable SDK and activate undo scrub
            currentStrokePoints.clear()
            currentPath.reset()
            if (useOnyxSdk) {
                try {
                    touchHelper?.setRawDrawingEnabled(false)
                } catch (e: Exception) {
                    Log.w(TAG, "Error disabling SDK for undo gesture: ${e.message}")
                }
            }
            undoScrubActive = true
            undoScrubTriggerY = undoReadyScreenY
            undoScrubLastStep = 0
            onUndoGestureStart?.invoke()
            // Process the initial step immediately
            val steps = ((screenY - undoScrubTriggerY) / UNDO_STEP_SIZE).toInt()
            if (steps != 0) {
                undoScrubLastStep = steps
                onUndoGestureStep?.invoke(steps)
            }
            Log.i(TAG, "Undo scrub activated at screenY=$screenY")
        }
    }

    private fun endUndoGesture() {
        val wasActive = undoScrubActive
        undoGestureReady = false
        undoScrubActive = false
        undoScrubLastStep = 0
        currentStrokePoints.clear()
        currentPath.reset()
        if (wasActive) {
            onUndoGestureEnd?.invoke()
        }
        finishInteractiveGesture()
        Log.i(TAG, "Undo gesture ended (wasActive=$wasActive)")
    }

    /** Common cleanup after any interactive gesture (line-drag or undo scrub). */
    private fun finishInteractiveGesture() {
        drawToSurface()
        if (useOnyxSdk) {
            try {
                touchHelper?.setRawDrawingEnabled(true)
            } catch (e: Exception) {
                Log.w(TAG, "Error re-enabling SDK after gesture: ${e.message}")
            }
        }
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
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

        // Draw ruled lines (skip interior of diagram areas, darken borders)
        val maxDocY = scrollOffsetY + height + LINE_SPACING
        var lineY = TOP_MARGIN + LINE_SPACING
        while (lineY < maxDocY) {
            val lineIdx = ((lineY - TOP_MARGIN) / LINE_SPACING).toInt()
            val isTopBorder = diagramAreas.any { lineIdx == it.startLineIndex }
            val isBottomBorder = diagramAreas.any { lineIdx == it.endLineIndex + 1 }
            val isInterior = diagramAreas.any { lineIdx > it.startLineIndex && lineIdx <= it.endLineIndex }
            if (isTopBorder || isBottomBorder) {
                canvas.drawLine(0f, lineY, gutterLeft, lineY, diagramBorderPaint)
            } else if (!isInterior) {
                canvas.drawLine(0f, lineY, gutterLeft, lineY, linePaint)
            }
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
