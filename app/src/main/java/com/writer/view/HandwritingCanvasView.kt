package com.writer.view

import android.content.Context
import android.graphics.Bitmap
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
import com.writer.ui.writing.PerfCounters
import com.writer.ui.writing.PerfMetric
import com.writer.ui.writing.TextAnnotation
import com.writer.view.ink.InkController
import com.writer.view.ink.InkControllerFactory
import com.writer.view.ink.StrokeCallback
import android.text.TextPaint
import com.writer.model.DiagramArea
import com.writer.model.TextBlock
import com.writer.ui.writing.DiagramStrokeClassifier
import com.writer.ui.writing.SpaceInsertMode
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.model.maxY
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Primary ink input surface. Delegates low-latency pen rendering to an
 * [InkController] (currently Onyx Boox only — see the [com.writer.view.ink]
 * package). When no hardware ink pipeline is available, falls back to
 * standard Android MotionEvent + Canvas rendering.
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
        private val ARROW_DWELL_RADIUS get() = ScreenMetrics.dp(8f)
        // Magnetic snap: max distance in line-spacings to snap arrow to node
        private const val MAGNET_THRESHOLD_SPANS = 1.5f
        private const val ARROW_DWELL_MS = 500L
    }

    private val completedStrokes = mutableListOf<InkStroke>()
    private val currentStrokePoints = mutableListOf<StrokePoint>()
    private val currentPath = Path()
    // Reused during rendering to avoid allocating a new Path per stroke per frame
    private val renderPath = Path()

    // Offscreen bitmap caching the static scene (background, ruled lines,
    // completed strokes, text blocks, overlays that don't change per pointer
    // event). Rebuilt by [drawToSurface]; reused by [drawOverlayOnlyToSurface]
    // during ACTION_MOVE so per-move updates skip the full scene redraw that
    // otherwise dominates latency on the Canvas-fallback (non-Onyx) path.
    private var contentBitmap: Bitmap? = null

    // Choreographer-coalesced overlay composer. ACTION_MOVE events arrive faster
    // than the SurfaceFlinger can post frames, so a naive compose-per-event
    // backs up the main thread by hundreds of ms. Instead we mark a pending
    // frame and let the next vsync drain it — any intervening MOVE updates to
    // `currentPath` are captured automatically in the next compose.
    private val choreographer = android.view.Choreographer.getInstance()
    private var overlayFrameScheduled = false
    // Oldest MOTIONEvent eventTime pending since the last compose. Drained on
    // each frame to record true end-to-end latency (event → pixel on screen).
    private var pendingOverlayEventTime = 0L
    private val overlayFrameCallback = android.view.Choreographer.FrameCallback {
        overlayFrameScheduled = false
        if (surfaceReady && contentBitmap != null) {
            PerfCounters.time(PerfMetric.INK_COMPOSE_OVERLAY) { composeSurface() }
            val eventTime = pendingOverlayEventTime
            if (eventTime > 0L) {
                val latencyNs = (android.os.SystemClock.uptimeMillis() - eventTime) * 1_000_000L
                if (latencyNs >= 0) PerfCounters.recordDirect(PerfMetric.INK_MOVE_LATENCY, latencyNs)
                pendingOverlayEventTime = 0L
            }
        }
    }

    private fun scheduleOverlayFrame(eventTimeMs: Long) {
        if (pendingOverlayEventTime == 0L) pendingOverlayEventTime = eventTimeMs
        if (overlayFrameScheduled) return
        overlayFrameScheduled = true
        choreographer.postFrameCallback(overlayFrameCallback)
    }

    private fun cancelPendingOverlayFrame() {
        if (overlayFrameScheduled) {
            choreographer.removeFrameCallback(overlayFrameCallback)
            overlayFrameScheduled = false
        }
        pendingOverlayEventTime = 0L
    }

    // Scroll and other state mutations trigger drawToSurface. Unlike overlay
    // compose (dirty-rect, ~10 ms), a full scene rebuild is ~100 ms on this
    // HAL, so firing one per MotionEvent makes scroll stall-prone. Coalesce
    // to at most one rebuild per vsync via Choreographer.
    private var rebuildComposeScheduled = false
    private val rebuildComposeCallback = android.view.Choreographer.FrameCallback {
        rebuildComposeScheduled = false
        if (!surfaceReady) return@FrameCallback
        cancelPendingOverlayFrame()
        PerfCounters.time(PerfMetric.INK_RENDER_STATIC) { rebuildContentBitmap() }
        composeSurface()
    }

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

    /** Text blocks (transcribed audio) in the current document. */
    var textBlocks: List<TextBlock> = emptyList()

    /** Called when user finger-taps a TextBlock with linked audio. Second param is word-level startMs if available. */
    var onTextBlockTap: ((TextBlock, Long?) -> Unit)? = null

    /** Called when user taps the pause/play toggle in the playback overlay bar. */
    var onPlaybackPauseToggle: (() -> Unit)? = null

    /** Called when user taps outside text blocks while audio is playing. */
    var onPlaybackStop: (() -> Unit)? = null

    /** ID of the TextBlock currently playing audio (for visual indicator). */
    var playingTextBlockId: String? = null
        set(value) { field = value; drawToSurface() }


    private val textBlockPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.textBody
        isAntiAlias = false
    }

    /** Whether lecture capture is active — shows a recording indicator. */
    var lectureRecording: Boolean = false
        set(value) {
            field = value
            if (value) startRecordingIndicator() else stopRecordingIndicator()
        }

    private val recordingDotPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val recordingTextPaint = Paint().apply {
        color = Color.RED
        textSize = ScreenMetrics.dp(12f)
        isAntiAlias = true
    }

    private var recordingDotVisible = true
    private val recordingBlinkRunnable = object : Runnable {
        override fun run() {
            recordingDotVisible = !recordingDotVisible
            drawToSurface()
            if (lectureRecording) handler.postDelayed(this, 1000)
        }
    }

    private fun startRecordingIndicator() {
        recordingDotVisible = true
        handler.removeCallbacks(recordingBlinkRunnable)
        handler.postDelayed(recordingBlinkRunnable, 1000)
        drawToSurface()
    }

    private fun stopRecordingIndicator() {
        handler.removeCallbacks(recordingBlinkRunnable)
        drawToSurface()
    }

    /** Line index where a recording placeholder should be shown (-1 = none). */
    var recordingPlaceholderLine: Int = -1
        set(value) { field = value; drawToSurface() }

    /** Live partial transcription text shown inside the recording placeholder. */
    var partialTranscriptionText: String = ""
        set(value) { if (field != value) { field = value; drawToSurface() } }


    private val placeholderBorderPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(ScreenMetrics.dp(8f), ScreenMetrics.dp(6f)), 0f)
    }

    private val partialTextPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.textBody
        isAntiAlias = false
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
    }

    private val placeholderTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.dp(13f)
        isAntiAlias = false
    }

    /** Transcription progress state for inline progress bar. */
    data class TranscriptionProgress(
        val lineIndex: Int,
        val audioDurationSec: Float,
        val progress: Float, // 0.0 to 1.0
        val label: String
    )
    var transcriptionProgress: TranscriptionProgress? = null
        set(value) { field = value; drawToSurface() }

    private val progressBgPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }

    private val progressFillPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    private val progressLabelPaint = Paint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.dp(14f)
        isAntiAlias = false
    }

    private val squigglePaint = Paint().apply {
        color = 0xFFD32F2F.toInt() // Material Red 700 — visible on Kaleido 3 color e-ink
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(1.5f)
        isAntiAlias = false // sharper on e-ink
    }
    private val confidenceThreshold = 0.5f

    private val audioIconPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val playbackAccentPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
        strokeWidth = ScreenMetrics.dp(3f)
    }

    /** Column model reference for magnetic snap access. */
    var columnModel: ColumnModel? = null

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

    // --- Space insert mode ---
    /** When true, the canvas shows line boundary handles and accepts drag to insert/remove space. */
    var spaceInsertMode = false
        set(value) {
            field = value
            spaceInsertDragActive = false
            spaceInsertAnchorLine = -1
            spaceInsertDragStartY = 0f
            if (value) {
                pauseRawDrawing()
                drawToSurface()
            } else {
                drawToSurface()
                resumeRawDrawing()
            }
        }
    private var spaceInsertDragActive = false
    private var spaceInsertAnchorLine = -1
    private var spaceInsertDragStartY = 0f
    private var spaceInsertDragCurrentY = 0f
    private var spaceInsertMaxUpPx = 0f  // max allowed upward shift (clamped at content)
    /** Called when the user completes a space-insert drag. (anchorLine, linesInserted) — negative = removed. */
    var onSpaceInsert: ((anchorLine: Int, lines: Int) -> Unit)? = null

    private val spaceInsertLinePaint = Paint().apply {
        color = CanvasTheme.DIAGRAM_BORDER_COLOR
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private val spaceInsertBarrierPaint = Paint().apply {
        color = CanvasTheme.BARRIER_COLOR
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    /** When true, all pen input is blocked and annotations are rendered. */
    var tutorialMode = false
    var annotationStrokes: List<AnnotationStroke> = emptyList()
    var textAnnotations: List<TextAnnotation> = emptyList()

    // Tutorial ghost strokes: rendered in gray with optional progressive reveal
    var ghostStrokes: List<InkStroke> = emptyList()
    /** 0.0 = all gray, 1.0 = all revealed in black. Controls progressive reveal animation. */
    var ghostRevealProgress: Float = 0f

    private val ghostPaint = Paint().apply {
        color = CanvasTheme.LINE_COLOR
        strokeWidth = CanvasTheme.DEFAULT_STROKE_WIDTH
        style = Paint.Style.STROKE
        isAntiAlias = false
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ghostRevealPaint = CanvasTheme.newStrokePaint()

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

    /** Called when the stylus hovers over this canvas but it doesn't have the
     *  Onyx SDK session. Parameters: hover x, y in view-local coordinates.
     *  The activity should transfer the session before pen-down. */
    var onRequestOnyxSession: ((Float, Float) -> Unit)? = null

    private val inkController: InkController = InkControllerFactory.create()
    /** True iff an attached hardware ink overlay is currently rendering strokes. */
    private val useOnyxSdk: Boolean get() = inkController.isActive
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

    // --- Shared stroke lifecycle methods (used by SDK callbacks, fallback touch, and tests) ---

    /** Called at the very start of pen input (before any processing). */
    var onPenDown: (() -> Unit)? = null


    /** Begin a new stroke: set pen active, clear state, start dwell detection. */
    fun beginStroke(firstPoint: StrokePoint) {
        touchFilter?.penActive = true
        onPenDown?.invoke()
        onPenStateChanged?.invoke(true)
        handler.removeCallbacks(idleRunnable)
        currentStrokePoints.clear()
        currentDiagramBounds = getDiagramBounds(firstPoint.y)
        currentStrokePoints.add(firstPoint)
        initStrokeBounds(currentStrokePoints.last())
        startDwellJob()
    }

    /** Add a point to the current stroke and update tracking. */
    fun addStrokePoint(point: StrokePoint) {
        currentStrokePoints.add(point)
        updateStrokeBounds(currentStrokePoints.last())
        checkDwellCancellation()
    }

    /** End the current stroke: cancel dwell, fire callbacks, process. */
    fun endStroke(lastPoint: StrokePoint) {
        cancelDwellJob()
        touchFilter?.let {
            it.penActive = false
            it.penUpTimestamp = android.os.SystemClock.uptimeMillis()
        }
        lastPenUpTime = System.currentTimeMillis()
        onPenStateChanged?.invoke(false)
        currentStrokePoints.add(lastPoint)
        finishStroke()
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private val overlayStrokeCallback = object : StrokeCallback {
        override fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            beginStroke(StrokePoint(x, y + scrollOffsetY, pressure, timestampMs))
        }

        override fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            addStrokePoint(StrokePoint(x, y + scrollOffsetY, pressure, timestampMs))
        }

        override fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            Log.d(TAG, "onStrokeEnd: ${currentStrokePoints.size} points")
            endStroke(StrokePoint(x, y + scrollOffsetY, pressure, timestampMs))
        }
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        drawToSurface()
        tryInitOnyx()
    }

    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    private val reinitOnyxRunnable = Runnable { reinitializeRawDrawing() }

    /** Cancel any pending delayed Onyx SDK reinitialize (e.g. from surfaceChanged). */
    fun cancelPendingReinit() {
        handler.removeCallbacks(reinitOnyxRunnable)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawToSurface()
        val dimensionsChanged = width != lastSurfaceWidth || height != lastSurfaceHeight
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        if (inkController.isActive && dimensionsChanged) {
            // Delay reinitialize to let the system fully settle after rotation.
            // The Onyx SDK caches the view's screen position at attach time —
            // if we reinitialize too early, it picks up stale coordinates.
            handler.removeCallbacks(reinitOnyxRunnable)
            handler.postDelayed(reinitOnyxRunnable, 500L)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        handler.removeCallbacks(idleRunnable)
        cancelPendingOverlayFrame()
        if (rebuildComposeScheduled) {
            choreographer.removeFrameCallback(rebuildComposeCallback)
            rebuildComposeScheduled = false
        }
        inkController.detach()
        contentBitmap?.recycle()
        contentBitmap = null
    }

    /** When true, skip auto-init of Onyx SDK on surfaceCreated. The session
     *  will be transferred here on demand via [onRequestOnyxSession]. */
    var deferOnyxInit = false

    /** When true, an external shared Onyx SDK handles stylus input — skip stylus
     *  events in onTouchEvent to avoid double-processing. */
    var externalOnyxActive = false

    private fun tryInitOnyx() {
        if (inkController.isActive) return
        if (deferOnyxInit) return // will be initialized on demand
        val limit = Rect()
        getLocalVisibleRect(limit)
        inkController.attach(this, limit, overlayStrokeCallback)
    }

    // --- Hover handling (for Onyx SDK session swap between canvases) ---

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            // Track hover state for palm rejection — reject all finger events while
            // the stylus is in proximity range (Apple Pencil-style suppression).
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                    touchFilter?.penHovering = true
                MotionEvent.ACTION_HOVER_EXIT ->
                    touchFilter?.penHovering = false
            }

            // SDK session transfer — request on hover enter only (not every move)
            // to minimize EPD artifacts from repeated transfers near the divider.
            if (!useOnyxSdk && event.action == MotionEvent.ACTION_HOVER_ENTER) {
                onRequestOnyxSession?.invoke(event.x, event.y)
            }
        }
        return super.onHoverEvent(event)
    }

    // --- Touch handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Space insert mode: intercept ALL input (finger + stylus) for drag handling
        if (spaceInsertMode) return handleSpaceInsertTouch(event)

        // Finger touches: filter through palm rejection, allow vertical scroll
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            handler.removeCallbacks(idleRunnable)
            return handleFingerTouch(event)
        }

        // In tutorial mode, block all writing input
        if (tutorialMode) return false

        // Fallback: stylus touched canvas without SDK (e.g., direct pen-down on the
        // cue column without hovering first). Request session transfer before the
        // SDK check below — after transfer, useOnyxSdk becomes true and the SDK
        // handles subsequent events via its callback.
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS && !useOnyxSdk && !externalOnyxActive
            && event.action == MotionEvent.ACTION_DOWN) {
            onRequestOnyxSession?.invoke(event.x, event.y)
        }

        // Controllers that consume MotionEvents (Onyx TouchHelper, or an external
        // shared Onyx session) — pen input is handled by SDK callbacks, swallow here.
        // Bigme's xrz path does NOT consume MotionEvents: the native daemon paints
        // the visible ink at the framebuffer while the app still processes the
        // logical stroke through the code below.
        if (inkController.consumesMotionEvents || externalOnyxActive) return true

        // Stylus/mouse on canvas → writing (fallback for non-Boox devices)
        val x = event.x
        val y = event.y + scrollOffsetY
        val pressure = event.pressure
        val timestamp = event.eventTime

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.reset()
                currentPath.moveTo(x, y)
                beginStroke(StrokePoint(x, y, pressure, timestamp))
                // No rebuild needed: the cached bitmap already reflects all
                // completed state (every mutation path calls drawToSurface).
                // Just compose the existing scene.
                if (contentBitmap != null) composeSurface() else drawToSurface()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i) + scrollOffsetY
                    val hp = event.getHistoricalPressure(i)
                    val ht = event.getHistoricalEventTime(i)
                    currentPath.lineTo(hx, hy)
                    addStrokePoint(StrokePoint(hx, hy, hp, ht))
                }
                currentPath.lineTo(x, y)
                addStrokePoint(StrokePoint(x, y, pressure, timestamp))
                // Schedule a coalesced overlay compose for the next vsync.
                // Many MOVE events per frame collapse into one paint.
                drawOverlayOnlyToSurface(event.eventTime)
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                endStroke(StrokePoint(x, y, pressure, timestamp))
                // Incremental commit: paint the just-finished stroke into the
                // existing cached bitmap instead of re-rasterizing everything.
                // Full rebuild is O(N strokes) × ~100ms; incremental is O(1)
                // per stroke and runs in the low milliseconds even on this HAL.
                if (!appendLastStrokeToBitmap()) drawToSurface() else composeSurface()
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

                // Detect tap (no scroll movement) on a TextBlock with audio
                if (event.action == MotionEvent.ACTION_UP && tf != null && !tf.hasMovedPastSlop()) {
                    val docY = event.y + scrollOffsetY
                    val tapLine = ((docY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)
                    val tappedBlock = textBlocks.find {
                        it.containsLine(tapLine) && it.audioFile.isNotEmpty()
                    }
                    if (tappedBlock != null) {
                        if (!tutorialMode) resumeRawDrawing()
                        // Map tap X to a specific word for word-level audio seek
                        val tapDocX = event.x
                        var wordStartMs: Long? = null
                        if (tappedBlock.words.isNotEmpty()) {
                            val margin = LINE_SPACING * 0.3f
                            val fullText = tappedBlock.text.trimStart()
                            val textW = (width.toFloat() - 2 * margin).toInt().coerceAtLeast(1)

                            // Use StaticLayout to find which wrapped line the tap is on
                            val layout = android.text.StaticLayout.Builder
                                .obtain(fullText, 0, fullText.length, textBlockPaint, textW)
                                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                                .build()

                            // Which wrapped line did the user tap?
                            val tappedWrappedLine = tapLine - tappedBlock.startLineIndex
                            if (tappedWrappedLine in 0 until layout.lineCount) {
                                val lineStart = layout.getLineStart(tappedWrappedLine)
                                val lineEnd = layout.getLineEnd(tappedWrappedLine)
                                val lineText = fullText.substring(lineStart, lineEnd).trimEnd()
                                val lineLeftOffset = layout.getLineLeft(tappedWrappedLine)

                                // Map tap X to a word on this wrapped line
                                val tapXInLine = tapDocX - margin - lineLeftOffset
                                val lineWords = lineText.split(" ").filter { it.isNotEmpty() }

                                // Count words before this line to index into block.words
                                val wordsBefore = fullText.substring(0, lineStart)
                                    .split(" ").filter { it.isNotEmpty() }.size

                                var charInLine = 0
                                for ((j, wordText) in lineWords.withIndex()) {
                                    val wordIdx = wordsBefore + j
                                    if (wordIdx >= tappedBlock.words.size) break
                                    val wx = textBlockPaint.measureText(lineText, 0, charInLine)
                                    val ww = textBlockPaint.measureText(wordText)
                                    if (tapXInLine >= wx - ww * 0.15f && tapXInLine <= wx + ww * 1.15f) {
                                        val word = tappedBlock.words[wordIdx]
                                        wordStartMs = word.startMs
                                        android.util.Log.i("TextBlockTap",
                                            "Tapped word[$wordIdx]=\"${word.text}\" startMs=${word.startMs} endMs=${word.endMs} " +
                                            "tapX=%.1f wordX=%.1f..%.1f".format(tapXInLine, wx, wx + ww))
                                        break
                                    }
                                    charInLine += wordText.length + 1
                                }
                            }
                        }
                        if (wordStartMs == null && tappedBlock.words.isNotEmpty()) {
                            android.util.Log.w("TextBlockTap",
                                "No word matched tap. Falling back to block startMs=${tappedBlock.audioStartMs}. " +
                                "Words: ${tappedBlock.words.joinToString { "${it.text}@${it.startMs}ms" }}")
                        }
                        onTextBlockTap?.invoke(tappedBlock, wordStartMs)
                        return true
                    }
                }

                // Tapped outside any text block — stop playback if active
                if (playingTextBlockId != null) {
                    onPlaybackStop?.invoke()
                }

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

    /**
     * Handle touch events during space-insert mode. Either finger or stylus works.
     * Touch anywhere on the canvas to set the anchor line (nearest line boundary),
     * then drag down to insert space or up to remove space.
     */
    private fun handleSpaceInsertTouch(event: MotionEvent): Boolean {
        val docY = event.y + scrollOffsetY
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Anchor = the line the user touches. Content at and below this line will shift.
                val lineIndex = ((docY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)
                spaceInsertAnchorLine = lineIndex
                spaceInsertDragStartY = event.y
                spaceInsertDragCurrentY = event.y
                spaceInsertDragActive = true

                // If anchor is inside a diagram, scan from the diagram's top edge
                val scanFrom = SpaceInsertMode.effectiveShiftLine(lineIndex, diagramAreas, textBlocks)

                // Compute max allowed upward shift by counting empty lines above scanFrom
                val occupiedLines = completedStrokes.map { s ->
                    ((s.minY + s.maxY) / 2f - TOP_MARGIN) / LINE_SPACING
                }.map { it.toInt().coerceAtLeast(0) }.toSet()
                var emptyAbove = 0
                for (i in 1..scanFrom) {
                    val checkLine = scanFrom - i
                    if (checkLine in occupiedLines) break
                    if (diagramAreas.any { it.containsLine(checkLine) }) break
                    if (textBlocks.any { it.containsLine(checkLine) }) break
                    emptyAbove++
                }
                spaceInsertMaxUpPx = emptyAbove * LINE_SPACING
                Log.d(TAG, "Space insert: anchor=$lineIndex emptyAbove=$emptyAbove maxUp=${spaceInsertMaxUpPx}px")
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!spaceInsertDragActive) return true
                spaceInsertDragCurrentY = event.y
                drawToSurface()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!spaceInsertDragActive) {
                    spaceInsertMode = false
                    return true
                }
                spaceInsertDragActive = false
                val dragDeltaY = event.y - spaceInsertDragStartY
                val linesDelta = (dragDeltaY / LINE_SPACING).toInt()
                Log.d(TAG, "Space insert: drag=${dragDeltaY}px lines=$linesDelta anchor=${spaceInsertAnchorLine}")

                if (linesDelta != 0) {
                    onSpaceInsert?.invoke(spaceInsertAnchorLine, linesDelta)
                }

                spaceInsertMode = false
                return true
            }
        }
        return true
    }

    /** Elapsed time of the last finishStroke() call in ms, for performance testing. */
    var lastFinishStrokeMs: Long = 0L
        private set

    private fun finishStroke() {
        if (currentStrokePoints.size < 2) {
            resetStrokeState()
            return
        }

        val t0 = android.os.SystemClock.elapsedRealtime()

        // Raw capture: record stroke before any processing (scratch-out, shape snap, etc.)
        onRawStrokeCapture?.invoke(currentStrokePoints.toList())

        if (currentDiagramBounds != null) {
            finishDiagramStroke()
        } else {
            finishTextStroke()
        }

        lastFinishStrokeMs = android.os.SystemClock.elapsedRealtime() - t0
    }

    /** Post-stroke pipeline for strokes INSIDE a diagram area. */
    private fun finishDiagramStroke() {
        val tailDwell = dwellIndicatorShown
        dwellDotCenter = null
        dwellIndicatorShown = false

        // Shape snap before scratch-out: rectangles have X-reversals that
        // would otherwise be consumed as scratch-out.
        val rawPoints = currentStrokePoints.toList()
        val snapData = checkShapeSnap(tailDwell)

        if (snapData == null && checkPostStrokeScratchOut()) {
            currentDiagramBounds = null
            return
        }

        val finalStroke = emitStroke(rawPoints, snapData)

        // Expand diagram if stroke (raw or snapped) crosses the boundary.
        val (diagTopY, diagBottomY) = currentDiagramBounds!!
        val overflowMinY = minOf(strokeMinY, finalStroke.points.minOf { it.y })
        val overflowMaxY = maxOf(strokeMaxY, finalStroke.points.maxOf { it.y })
        if (overflowMinY < diagTopY || overflowMaxY > diagBottomY) {
            onDiagramStrokeOverflow?.invoke(finalStroke.strokeId, overflowMinY, overflowMaxY)
        }

        resetStrokeState()
        if (snapData != null) flushAndRedraw()
    }

    /** Post-stroke pipeline for strokes OUTSIDE any diagram area. */
    private fun finishTextStroke() {
        if (checkPostStrokeScratchOut()) return

        // Shape-intent: dwell at end → shape detection → auto-create diagram
        val snapData = checkShapeSnap()
        if (snapData != null) {
            val rawPoints = currentStrokePoints.toList()
            val rawStroke = emitStroke(rawPoints, null)
            onDiagramShapeDetected?.invoke(rawStroke)

            // Replace raw with snapped shape
            val snappedStroke = InkStroke(
                points = currentStrokePoints.toList(),
                isGeometric = snapData.isGeometric,
                strokeType = snapData.strokeType
            )
            completedStrokes.remove(rawStroke)
            completedStrokes.add(snappedStroke)
            onStrokeReplaced?.invoke(rawStroke.strokeId, snappedStroke)

            resetStrokeState()
            flushAndRedraw()
            return
        }

        // Auto-classify: if stroke looks like a drawing (score ≥ 0.5),
        // create a diagram area around it. No dwell required — the system
        // infers intent from the stroke shape.
        val stroke = InkStroke(points = currentStrokePoints.toList())
        val drawingScore = DiagramStrokeClassifier.classifyStroke(
            stroke, LINE_SPACING, includeConnector = false
        )
        completedStrokes.add(stroke)
        onStrokeCompleted?.invoke(stroke)
        if (drawingScore >= 0.5f) {
            onDiagramShapeDetected?.invoke(stroke)
            resetStrokeState()
            flushAndRedraw()
        } else {
            resetStrokeState()
        }
    }

    /**
     * Emit a stroke with optional shape snap (two-phase commit).
     * If [snapData] is non-null, emits the raw freehand stroke first, then
     * replaces it with the snapped version. Returns the final emitted stroke.
     */
    private fun emitStroke(rawPoints: List<StrokePoint>, snapData: SnapData?): InkStroke {
        val rawStroke = InkStroke(
            points = rawPoints,
            isGeometric = false,
            strokeType = StrokeType.FREEHAND
        )
        completedStrokes.add(rawStroke)
        onStrokeCompleted?.invoke(rawStroke)

        if (snapData == null) return rawStroke

        val snappedStroke = InkStroke(
            points = currentStrokePoints.toList(),
            isGeometric = snapData.isGeometric,
            strokeType = snapData.strokeType
        )
        completedStrokes.remove(rawStroke)
        completedStrokes.add(snappedStroke)
        onStrokeReplaced?.invoke(rawStroke.strokeId, snappedStroke)
        return snappedStroke
    }

    /** Clear transient stroke state after processing. */
    private fun resetStrokeState() {
        currentStrokePoints.clear()
        currentPath.reset()
        currentDiagramBounds = null
        dwellDotCenter = null
        dwellIndicatorShown = false
    }

    /** Force e-ink refresh by cycling the Onyx raw drawing mode. */
    private fun flushAndRedraw() {
        pauseRawDrawing()
        drawToSurface()
        resumeRawDrawing()
    }

    /**
     * Inject a complete stroke for testing. Calls the same [beginStroke],
     * [addStrokePoint], [endStroke] methods used by the SDK callbacks and
     * the fallback touch path — no duplicated logic. Must be called on the UI thread.
     */
    fun injectStrokeForTest(points: List<StrokePoint>) {
        if (points.size < 2) return
        beginStroke(points.first())
        for (i in 1 until points.size - 1) {
            addStrokePoint(points[i])
        }
        endStroke(points.last())
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
            currentStrokePoints, last.x, last.y, ARROW_DWELL_RADIUS, ARROW_DWELL_MS
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
        val nodes = columnModel?.diagram?.nodes ?: emptyMap()
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
                    currentStrokePoints, result.x2, result.y2, ARROW_DWELL_RADIUS, ARROW_DWELL_MS
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
                    currentStrokePoints, result.x2, result.y2, ARROW_DWELL_RADIUS, ARROW_DWELL_MS
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
                    currentStrokePoints, result.x2, result.y2, ARROW_DWELL_RADIUS, ARROW_DWELL_MS
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
                    currentStrokePoints, last.x, last.y, ARROW_DWELL_RADIUS, ARROW_DWELL_MS
                )
                strokeType = ArrowDwellDetection.classifyArc(tipDwell, tailDwell)
                isGeometric = false

                result.points.map { (x, y) -> StrokePoint(x, y, 0f, t) }
            }
            is ShapeSnapDetection.SnapResult.SelfLoop -> {
                val tipDwell = ArrowDwellDetection.hasDwellAtEnd(
                    currentStrokePoints, last.x, last.y, ARROW_DWELL_RADIUS, ARROW_DWELL_MS
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
        // Standard scratch-out check against existing ink strokes
        var isScratch = ScratchOutDetection.isScratchOut(currentStrokePoints, completedStrokes, LINE_SPACING)

        // If not a scratch-out over strokes, check if the gesture shape is a scratch-out
        // over a TextBlock (TextBlocks aren't strokes so the focus check doesn't apply)
        if (!isScratch && textBlocks.isNotEmpty()) {
            val xs = FloatArray(currentStrokePoints.size) { currentStrokePoints[it].x }
            val yRange = currentStrokePoints.maxOf { it.y } - currentStrokePoints.minOf { it.y }
            if (ScratchOutDetection.detect(xs, yRange, LINE_SPACING, false)) {
                // Check if scratch bbox overlaps a TextBlock
                val scratchCenterY = (strokeMinY + strokeMaxY) / 2
                val scratchLine = ((scratchCenterY - TOP_MARGIN) / LINE_SPACING).toInt().coerceAtLeast(0)
                if (textBlocks.any { it.containsLine(scratchLine) }) {
                    isScratch = true
                }
            }
        }

        if (!isScratch) return false

        val scratchPoints = currentStrokePoints.toList()
        val left = strokeMinX; val top = strokeMinY
        val right = strokeMaxX; val bottom = strokeMaxY

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
            if (dx * dx + dy * dy < ARROW_DWELL_RADIUS * ARROW_DWELL_RADIUS) {
                dwellIndicatorShown = true
                dwellDotCenter = PointF(first.x, first.y)
                drawToSurface()
            }
        }
        dwellJob = job
        handler.postDelayed(job, ARROW_DWELL_MS)
    }

    /** Cancel dwell if the pen has moved significantly from the first point. */
    private fun checkDwellCancellation() {
        val first = currentStrokePoints.firstOrNull() ?: return
        val last = currentStrokePoints.lastOrNull() ?: return
        val dx = last.x - first.x
        val dy = last.y - first.y
        val distSq = dx * dx + dy * dy
        // Cancel pending timer if pen moved beyond dwell radius
        if (dwellJob != null && distSq > ARROW_DWELL_RADIUS * ARROW_DWELL_RADIUS) {
            cancelDwellJob()
        }
        // Clear already-fired dwell if pen moved beyond 2× radius
        if (dwellIndicatorShown && distSq > ARROW_DWELL_RADIUS * ARROW_DWELL_RADIUS * 4) {
            dwellIndicatorShown = false
            dwellDotCenter = null
        }
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
        // Coalesce to one rebuild+compose per vsync — dozens of scroll/state
        // mutations in one burst collapse into a single scene rebuild.
        if (rebuildComposeScheduled) return
        rebuildComposeScheduled = true
        choreographer.postFrameCallback(rebuildComposeCallback)
    }

    /** Fast path for ACTION_MOVE: reuse the cached static scene and only paint
     *  the in-progress stroke + dwell-dot overlay. Any mutation of completed
     *  strokes, scroll offset, text blocks, or other static content must go
     *  through [drawToSurface] so the cache is rebuilt.
     *
     *  Coalesces to one compose per display frame via Choreographer — many
     *  MOVE events within one vsync collapse into a single redraw. */
    private fun drawOverlayOnlyToSurface(eventTimeMs: Long) {
        if (!surfaceReady) return
        if (contentBitmap == null) {
            drawToSurface()
            return
        }
        scheduleOverlayFrame(eventTimeMs)
    }

    private fun rebuildContentBitmap() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val existing = contentBitmap
        val bmp = if (existing != null && existing.width == w && existing.height == h) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { contentBitmap = it }
        }
        renderStaticContent(Canvas(bmp))
    }

    /** Paint just the most recently completed stroke into the existing cached
     *  bitmap — avoids a full O(N) rebuild on ACTION_UP. Returns false if the
     *  cache doesn't exist yet (caller should fall back to a full rebuild). */
    private fun appendLastStrokeToBitmap(): Boolean {
        val bmp = contentBitmap ?: return false
        val stroke = completedStrokes.lastOrNull() ?: return false
        val c = Canvas(bmp)
        c.save()
        c.translate(0f, -scrollOffsetY)
        drawStroke(c, stroke)
        c.restore()
        return true
    }

    private fun composeSurface() {
        val bmp = contentBitmap ?: return
        val dirty = computeOverlayDirtyRect(bmp.width, bmp.height)
        val canvas = if (dirty != null) holder.lockCanvas(dirty) else holder.lockCanvas()
        if (canvas == null) return
        try {
            if (dirty != null) {
                // Partial compose: only the region around the in-progress stroke.
                // SurfaceFlinger preserves pixels outside [dirty] from the
                // previous posted buffer, so blitting the whole bitmap here is
                // waste. Clip to the dirty region and copy just that slice.
                canvas.clipRect(dirty)
                canvas.drawBitmap(bmp, dirty, dirty, null)
            } else {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
            drawDynamicOverlay(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** Bounding rectangle (screen-space, inflated for stroke width) of the
     *  in-progress stroke — used to limit the surface compose to just the
     *  region that changes during ACTION_MOVE. Returns null when no live
     *  stroke exists, forcing a full compose. */
    private fun computeOverlayDirtyRect(surfaceW: Int, surfaceH: Int): Rect? {
        if (currentStrokePoints.size < 2) return null
        // Stroke bounds are tracked in document space; translate to screen and
        // pad by stroke-width plus a safety margin for antialias bleed.
        val pad = strokePaint.strokeWidth.toInt() + 4
        val left = (strokeMinX - pad).toInt().coerceAtLeast(0)
        val top = (strokeMinY - scrollOffsetY - pad).toInt().coerceAtLeast(0)
        val right = (strokeMaxX + pad).toInt().coerceAtMost(surfaceW)
        val bottom = (strokeMaxY - scrollOffsetY + pad).toInt().coerceAtMost(surfaceH)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    /** In-progress stroke + dwell dot — the parts that change per pointer event.
     *  Both are stored in document coordinates (see stroke-capture path in
     *  onTouchEvent, which adds scrollOffsetY), so we apply the same scroll
     *  translation [renderStaticContent] uses before painting onto the
     *  screen-space surface. */
    private fun drawDynamicOverlay(canvas: Canvas) {
        val hasLiveStroke = !inkController.consumesMotionEvents && currentStrokePoints.size > 1
        val hasDwellDot = dwellDotCenter != null
        if (!hasLiveStroke && !hasDwellDot) return
        canvas.save()
        canvas.translate(0f, -scrollOffsetY)
        if (hasLiveStroke) {
            canvas.drawPath(currentPath, strokePaint)
        }
        dwellDotCenter?.let { dot ->
            canvas.drawCircle(dot.x, dot.y, CanvasTheme.DEFAULT_STROKE_WIDTH * 3f, dwellDotPaint)
        }
        canvas.restore()
    }

    private fun renderStaticContent(canvas: Canvas) {
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

        // Draw ghost strokes (tutorial demo content)
        if (ghostStrokes.isNotEmpty()) {
            val totalPoints = ghostStrokes.sumOf { it.points.size }
            val revealedCount = (totalPoints * ghostRevealProgress).toInt()
            var pointsSoFar = 0
            for (stroke in ghostStrokes) {
                val strokeStart = pointsSoFar
                val strokeEnd = pointsSoFar + stroke.points.size
                pointsSoFar = strokeEnd

                if (strokeStart >= revealedCount) {
                    // Entirely unrevealed — draw as ghost
                    CanvasTheme.drawStroke(canvas, stroke, renderPath, ghostPaint)
                } else if (strokeEnd <= revealedCount) {
                    // Entirely revealed — draw in black
                    CanvasTheme.drawStroke(canvas, stroke, renderPath, ghostRevealPaint)
                } else {
                    // Partially revealed — draw ghost first, then revealed portion
                    CanvasTheme.drawStroke(canvas, stroke, renderPath, ghostPaint)
                    val revealIdx = revealedCount - strokeStart
                    if (revealIdx >= 2) {
                        val partial = InkStroke(points = stroke.points.subList(0, revealIdx))
                        CanvasTheme.drawStroke(canvas, partial, renderPath, ghostRevealPaint)
                    }
                }
            }
        }

        // Only draw strokes within the visible viewport
        val viewTop = scrollOffsetY
        val viewBottom = scrollOffsetY + height

        // During space-insert drag, compute preview offset for strokes at/below anchor.
        // Clamp upward shift to the max allowed (empty lines above anchor).
        val rawShiftPx = if (spaceInsertMode && spaceInsertDragActive && spaceInsertAnchorLine >= 0) {
            spaceInsertDragCurrentY - spaceInsertDragStartY
        } else 0f
        val previewShiftPx = rawShiftPx.coerceAtLeast(-spaceInsertMaxUpPx)
        val isClamped = rawShiftPx < -spaceInsertMaxUpPx
        // If anchor is inside a diagram, shift from the diagram's top edge so all
        // strokes in the diagram preview-shift together (mirrors SpaceInsertMode logic).
        val shiftFromLine = if (spaceInsertAnchorLine >= 0) {
            SpaceInsertMode.effectiveShiftLine(spaceInsertAnchorLine, diagramAreas, textBlocks)
        } else Int.MAX_VALUE
        val anchorY = if (shiftFromLine < Int.MAX_VALUE) {
            TOP_MARGIN + shiftFromLine * LINE_SPACING
        } else Float.MAX_VALUE

        for (stroke in completedStrokes) {
            val strokeCenterY = (stroke.minY + stroke.maxY) / 2f
            val shift = if (spaceInsertMode && spaceInsertDragActive && strokeCenterY >= anchorY) previewShiftPx else 0f
            if (stroke.maxY + shift >= viewTop && stroke.minY + shift <= viewBottom) {
                if (shift != 0f) {
                    canvas.save()
                    canvas.translate(0f, shift)
                    drawStroke(canvas, stroke)
                    canvas.restore()
                } else {
                    drawStroke(canvas, stroke)
                }
            }
        }

        // (In-progress stroke + dwell dot are rendered by drawDynamicOverlay
        // directly onto the live SurfaceView after this cached scene is blitted.)

        // Draw borders around all diagram areas (shifted during space-insert preview).
        // Use solid borders in space-insert mode to distinguish from dashed line markers.
        for (area in diagramAreas) {
            val areaShift = if (spaceInsertMode && spaceInsertDragActive &&
                (area.startLineIndex >= spaceInsertAnchorLine || area.containsLine(spaceInsertAnchorLine))
            ) previewShiftPx else 0f
            val topY = TOP_MARGIN + area.startLineIndex * LINE_SPACING + areaShift
            val bottomY = TOP_MARGIN + (area.startLineIndex + area.heightInLines) * LINE_SPACING + areaShift
            if (spaceInsertMode) {
                canvas.drawRect(0f, topY, canvasRight, bottomY, diagramBorderPaint)
            } else {
                canvas.drawRect(0f, topY, canvasRight, bottomY, freeformPreviewPaint)
            }
        }

        // Draw text blocks (transcribed audio) at their line positions,
        // with each wrapped line aligned to a ruled line on the canvas.
        val textLeftMargin = LINE_SPACING * 0.3f
        val textWidth = (canvasRight - 2 * textLeftMargin).toInt().coerceAtLeast(1)
        for (block in textBlocks) {
            if (block.text.isBlank()) continue
            val blockShift = if (spaceInsertMode && spaceInsertDragActive &&
                block.startLineIndex >= spaceInsertAnchorLine
            ) previewShiftPx else 0f

            // Build a StaticLayout for word wrapping
            val layout = android.text.StaticLayout.Builder
                .obtain(block.text, 0, block.text.length, textBlockPaint, textWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .build()

            // Draw each wrapped line with its baseline on the ruled line.
            // Ruled line is at TOP_MARGIN + (lineIndex+1) * LINE_SPACING.
            // Descenders naturally hang below the baseline (below the ruled line).
            for (i in 0 until layout.lineCount) {
                val lineText = block.text.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
                val ruledLineIndex = block.startLineIndex + i
                val baselineY = TOP_MARGIN + (ruledLineIndex + 1) * LINE_SPACING + blockShift
                canvas.drawText(lineText, textLeftMargin, baselineY, textBlockPaint)
            }

            // Draw red squiggly underline on low-confidence words
            if (block.words.isNotEmpty()) {
                val fullText = block.text.trimStart()
                val wordsInText = fullText.split(" ").filter { it.isNotEmpty() }
                var charPos = 0
                for ((i, wordText) in wordsInText.withIndex()) {
                    if (i >= block.words.size) break
                    val word = block.words[i]
                    if (word.confidence < confidenceThreshold) {
                        val wordX = textLeftMargin + textBlockPaint.measureText(fullText, 0, charPos)
                        val wordWidth = textBlockPaint.measureText(wordText)
                        val wordLine = block.startLineIndex
                        val baselineY = TOP_MARGIN + (wordLine + 1) * LINE_SPACING + blockShift
                        val squiggleY = baselineY + ScreenMetrics.dp(2f)
                        val amplitude = ScreenMetrics.dp(2f)
                        val period = ScreenMetrics.dp(6f)
                        val squigglePath = android.graphics.Path()
                        squigglePath.moveTo(wordX, squiggleY)
                        var sx = wordX
                        var phase = 0
                        while (sx < wordX + wordWidth) {
                            val nextX = (sx + period / 2).coerceAtMost(wordX + wordWidth)
                            val dy = if (phase % 2 == 0) amplitude else -amplitude
                            squigglePath.lineTo(nextX, squiggleY + dy)
                            sx = nextX
                            phase++
                        }
                        canvas.drawPath(squigglePath, squigglePaint)
                    }
                    charPos += wordText.length + 1
                }
            }

            // Draw audio indicators
            if (block.audioFile.isNotEmpty()) {
                val iconSize = LINE_SPACING * 0.25f
                val iconX = textLeftMargin * 0.15f
                val iconCenterY = TOP_MARGIN + block.startLineIndex * LINE_SPACING + LINE_SPACING * 0.5f + blockShift
                val isPlaying = block.id == playingTextBlockId

                if (isPlaying) {
                    // Pause icon (two vertical bars)
                    val barW = iconSize * 0.25f
                    val barH = iconSize * 0.8f
                    canvas.drawRect(iconX, iconCenterY - barH / 2, iconX + barW, iconCenterY + barH / 2, audioIconPaint)
                    canvas.drawRect(iconX + barW * 2, iconCenterY - barH / 2, iconX + barW * 3, iconCenterY + barH / 2, audioIconPaint)

                    // Accent bar on left edge
                    val topY = TOP_MARGIN + block.startLineIndex * LINE_SPACING + blockShift
                    val bottomY = TOP_MARGIN + (block.endLineIndex + 1) * LINE_SPACING + blockShift
                    canvas.drawRect(0f, topY, ScreenMetrics.dp(3f), bottomY, playbackAccentPaint)
                } else {
                    // Speaker icon (triangle + arcs approximated as a simple triangle)
                    val path = android.graphics.Path()
                    path.moveTo(iconX, iconCenterY - iconSize * 0.3f)
                    path.lineTo(iconX + iconSize * 0.6f, iconCenterY - iconSize * 0.5f)
                    path.lineTo(iconX + iconSize * 0.6f, iconCenterY + iconSize * 0.5f)
                    path.lineTo(iconX, iconCenterY + iconSize * 0.3f)
                    path.close()
                    canvas.drawPath(path, audioIconPaint)
                }
            }
        }

        // Draw space-insert mode: dashed line boundaries (skip inside diagram areas)
        if (spaceInsertMode) {
            var handleY = TOP_MARGIN + LINE_SPACING
            while (handleY < maxDocY) {
                // Skip dashed lines inside diagram areas — the solid border is sufficient
                val lineIdx = ((handleY - TOP_MARGIN) / LINE_SPACING).toInt()
                val insideDiagram = diagramAreas.any { lineIdx >= it.startLineIndex && lineIdx < it.startLineIndex + it.heightInLines }
                if (!insideDiagram) {
                    canvas.drawLine(0f, handleY, canvasRight, handleY, spaceInsertLinePaint)
                }
                handleY += LINE_SPACING
            }
            // Highlight the anchor line during drag
            if (spaceInsertDragActive && spaceInsertAnchorLine >= 0) {
                val aY = TOP_MARGIN + spaceInsertAnchorLine * LINE_SPACING
                spaceInsertLinePaint.strokeWidth = 4f
                canvas.drawLine(0f, aY, canvasRight, aY, spaceInsertLinePaint)
                spaceInsertLinePaint.strokeWidth = 2f

                // When clamped at content, highlight the barrier line and blocking strokes
                if (isClamped) {
                    val emptyAbove = (spaceInsertMaxUpPx / LINE_SPACING).toInt()
                    val barrierLine = SpaceInsertMode.barrierLine(spaceInsertAnchorLine, diagramAreas, emptyAbove, textBlocks)
                    if (barrierLine >= 0) {
                        val barrierY = TOP_MARGIN + (barrierLine + 1) * LINE_SPACING
                        canvas.drawLine(0f, barrierY, canvasRight, barrierY, spaceInsertBarrierPaint)

                        // Highlight all blocking content on the barrier line with one circle
                        val barrierTopY = TOP_MARGIN + barrierLine * LINE_SPACING
                        val barrierBottomY = barrierTopY + LINE_SPACING
                        var bMinX = Float.MAX_VALUE; var bMaxX = Float.MIN_VALUE
                        var bMinY = Float.MAX_VALUE; var bMaxY = Float.MIN_VALUE
                        var hasContent = false
                        for (stroke in completedStrokes) {
                            val centerY = (stroke.minY + stroke.maxY) / 2f
                            if (centerY >= barrierTopY && centerY < barrierBottomY) {
                                bMinX = minOf(bMinX, stroke.minX)
                                bMaxX = maxOf(bMaxX, stroke.maxX)
                                bMinY = minOf(bMinY, stroke.minY)
                                bMaxY = maxOf(bMaxY, stroke.maxY)
                                hasContent = true
                            }
                        }
                        if (hasContent) {
                            val pad = ScreenMetrics.dp(6f)
                            val cornerR = ScreenMetrics.dp(4f)
                            canvas.drawRoundRect(
                                bMinX - pad, bMinY - pad, bMaxX + pad, bMaxY + pad,
                                cornerR, cornerR, spaceInsertBarrierPaint
                            )
                        }
                    }
                }
            }
        }

        // Draw recording placeholder — dashed border showing where TextBlock will go
        if (recordingPlaceholderLine >= 0 && transcriptionProgress == null) {
            val phMargin = LINE_SPACING * 0.2f
            val textLeftMarginPh = LINE_SPACING * 0.4f
            val textWidthPh = (canvasRight - 2 * textLeftMarginPh).toInt().coerceAtLeast(1)

            if (partialTranscriptionText.isNotBlank()) {
                // Live partial text — italic inside dashed border
                val layout = android.text.StaticLayout.Builder
                    .obtain(partialTranscriptionText, 0, partialTranscriptionText.length, partialTextPaint, textWidthPh)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .build()
                val lineCount = layout.lineCount.coerceAtLeast(2)
                val phTop = TOP_MARGIN + recordingPlaceholderLine * LINE_SPACING + phMargin
                val phBottom = TOP_MARGIN + (recordingPlaceholderLine + lineCount) * LINE_SPACING - phMargin
                canvas.drawRect(phMargin, phTop, canvasRight - phMargin, phBottom, placeholderBorderPaint)

                for (i in 0 until layout.lineCount) {
                    val lineText = partialTranscriptionText.substring(
                        layout.getLineStart(i), layout.getLineEnd(i)
                    ).trimEnd()
                    val ruledLineIndex = recordingPlaceholderLine + i
                    val baselineY = TOP_MARGIN + (ruledLineIndex + 1) * LINE_SPACING
                    canvas.drawText(lineText, textLeftMarginPh, baselineY, partialTextPaint)
                }
            } else {
                // No partial yet — show placeholder label
                val phTop = TOP_MARGIN + recordingPlaceholderLine * LINE_SPACING + phMargin
                val phBottom = TOP_MARGIN + (recordingPlaceholderLine + 2) * LINE_SPACING - phMargin
                canvas.drawRect(phMargin, phTop, canvasRight - phMargin, phBottom, placeholderBorderPaint)

                val labelY = TOP_MARGIN + recordingPlaceholderLine * LINE_SPACING + LINE_SPACING * 0.85f
                canvas.drawText("\uD83C\uDFA4  Recording \u2014 transcription will appear here", textLeftMarginPh, labelY, placeholderTextPaint)
            }
        }

        // Draw transcription progress bar at the line where TextBlock will appear
        // Spans two ruled lines: bar on first line, label on second line
        transcriptionProgress?.let { prog ->
            val barLeftMargin = LINE_SPACING * 0.3f
            val barY = TOP_MARGIN + prog.lineIndex * LINE_SPACING + LINE_SPACING * 0.35f
            val barWidth = canvasRight - 2 * barLeftMargin
            val barHeight = ScreenMetrics.dp(10f)

            // Background track
            canvas.drawRect(barLeftMargin, barY, barLeftMargin + barWidth, barY + barHeight, progressBgPaint)
            // Filled portion
            canvas.drawRect(barLeftMargin, barY, barLeftMargin + barWidth * prog.progress, barY + barHeight, progressFillPaint)

            // Percentage text right-aligned on the bar
            val pctText = "%d%%".format((prog.progress * 100).toInt())
            val pctWidth = progressLabelPaint.measureText(pctText)
            canvas.drawText(pctText, barLeftMargin + barWidth - pctWidth, barY - ScreenMetrics.dp(3f), progressLabelPaint)

            // Label on the next line
            val labelY = TOP_MARGIN + (prog.lineIndex + 1) * LINE_SPACING + LINE_SPACING * 0.5f
            canvas.drawText(prog.label, barLeftMargin, labelY, progressLabelPaint)
        }

        canvas.restore()

        // Draw recording indicator (screen-space, not scrolled)
        if (lectureRecording && recordingDotVisible) {
            val dotX = ScreenMetrics.dp(20f)
            val dotY = ScreenMetrics.dp(20f)
            val dotR = ScreenMetrics.dp(5f)
            canvas.drawCircle(dotX, dotY, dotR, recordingDotPaint)
            canvas.drawText("REC", dotX + dotR + ScreenMetrics.dp(6f), dotY + ScreenMetrics.dp(4f), recordingTextPaint)
        }

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

    /** Fully close the ink overlay session (e.g. before launching another activity). */
    fun closeRawDrawing() {
        inkController.detach()
    }

    /** Reopen the ink overlay after it was closed (e.g. returning from another activity). */
    fun reopenRawDrawing() {
        if (!surfaceReady) return
        if (inkController.isActive) return
        tryInitOnyx()
        drawToSurface()
    }

    /** Fully close and reopen the overlay to reset internal state (e.g. after canvas resize). */
    fun reinitializeRawDrawing() {
        inkController.detach()
        if (surfaceReady) {
            tryInitOnyx()
            drawToSurface()
        }
    }

    /** Pause the ink overlay (needed before scrolling/screen refresh). */
    fun pauseRawDrawing() {
        inkController.setEnabled(false)
    }

    /** Resume the ink overlay after scrolling/screen refresh. */
    fun resumeRawDrawing() {
        inkController.setEnabled(true)
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
        textBlocks = emptyList()
        handler.removeCallbacks(idleRunnable)
        drawToSurface()
    }

    /** Load strokes from saved data (for restoring persisted documents). */
    fun loadStrokes(strokes: List<InkStroke>) {
        completedStrokes.clear()
        completedStrokes.addAll(strokes)
        currentStrokePoints.clear()
        currentPath.reset()
        textOverscroll = 0f
        handler?.removeCallbacks(idleRunnable)
        drawToSurface()
    }

    fun getStrokes(): List<InkStroke> = completedStrokes.toList()

    fun getStrokeCount(): Int = completedStrokes.size

    fun clearAnnotations() {
        tutorialMode = false
        annotationStrokes = emptyList()
        textAnnotations = emptyList()
    }

    fun isUsingOnyxSdk(): Boolean = inkController.isActive

    /** True if a stroke is currently being drawn (pen is in contact). */
    fun isPenActive(): Boolean = touchFilter?.penActive == true

    /** Timestamp of the last pen-up event. */
    private var lastPenUpTime = 0L

    /** True if the pen is currently active OR was active within the last [windowMs]. */
    fun isPenRecentlyActive(windowMs: Long = 2000L): Boolean {
        if (isPenActive()) return true
        return System.currentTimeMillis() - lastPenUpTime < windowMs
    }
}
