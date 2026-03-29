package com.writer.ui.writing

import android.content.Context
import android.graphics.Rect
import android.widget.LinearLayout
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.model.maxY
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import com.writer.view.TutorialOverlay

/**
 * Manages the interactive step-based tutorial.
 *
 * The tutorial has 4 steps on a blank canvas:
 * 1. Write text with stylus
 * 2. Draw a shape (hold at end to snap)
 * 3. Erase (strikethrough or scratch-out)
 * 4. Scroll with finger
 *
 * Each step highlights the canvas, shows a tooltip, and waits for the
 * user to perform the action before advancing.
 */
class TutorialManager(
    private val context: Context,
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
    private val getCoordinator: () -> WritingCoordinator?,
    private val getPendingRestore: () -> DocumentData?,
    private val clearPendingRestore: () -> Unit,
    private val onClosed: () -> Unit
) {

    companion object {
        private const val PREFS_NAME = "writer_prefs"
        private const val KEY_TUTORIAL_SEEN = "tutorial_seen"
    }

    var isActive = false
        private set

    /** The ID of the current tutorial step (e.g., "write", "peek_cue"). */
    val currentStepId: String?
        get() = if (isActive && currentStepIndex < steps.size) steps[currentStepIndex].id else null

    /** Called when the tutorial step changes — activity can update UI (e.g., toggle button). */
    var onStepChanged: (() -> Unit)? = null

    /** Returns the screen rect of the context rail strip (for anchored tooltips). */
    var getContextRailRect: (() -> Rect?)? = null

    /** Called when the user taps Next — activity can perform the skipped action (e.g., fold to cues). */
    var onNextStep: ((stepId: String) -> Unit)? = null

    private var overlay: TutorialOverlay? = null
    private var hersheyFont: HersheyFont? = null
    private var currentStepIndex = 0
    private var steps: List<TutorialStep> = emptyList()

    // Saved document state (restored on close)
    private var savedStrokes: List<InkStroke>? = null
    private var savedScrollY: Float = 0f
    private var savedState: DocumentData? = null
    private var savedTextHeight = 0
    private var savedTextWeight = 0f
    private var savedCanvasHeight = 0
    private var savedCanvasWeight = 0f

    fun shouldAutoShow(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
    }

    fun resetSeen() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TUTORIAL_SEEN, false).apply()
    }

    /** Set the overlay view (called from WritingActivity after layout inflation). */
    fun setOverlay(overlay: TutorialOverlay) {
        this.overlay = overlay
        overlay.onSkip = { close() }
        overlay.onNext = { next() }
    }

    /** Advance to the next step (called from the Next button). */
    fun next() {
        if (!isActive) return
        if (currentStepIndex >= steps.size - 1) {
            close()
            return
        }
        val skippedStepId = steps[currentStepIndex].id
        advanceHandler.removeCallbacks(advanceRunnable)
        // Let the activity perform the skipped step's action (e.g., fold to cues)
        onNextStep?.invoke(skippedStepId)
        currentStepIndex++
        showCurrentStep()
        inkCanvas.pauseRawDrawing()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }

    fun show() {
        val coordinator = getCoordinator()
        val overlay = this.overlay ?: return

        // Load Hershey font for demo text (lazy — only on first tutorial show)
        if (hersheyFont == null) {
            hersheyFont = HersheyFont.loadScript(context)
        }

        // Save current document state
        savedState = coordinator?.getState()
        savedStrokes = inkCanvas.getStrokes()
        savedScrollY = inkCanvas.scrollOffsetY

        // Save layout params
        val textParams = textView.layoutParams as LinearLayout.LayoutParams
        val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        savedTextHeight = textParams.height
        savedTextWeight = textParams.weight
        savedCanvasHeight = canvasParams.height
        savedCanvasWeight = canvasParams.weight

        // Clear canvas for a blank tutorial
        coordinator?.stop()
        coordinator?.reset()
        inkCanvas.clear()
        inkCanvas.diagramAreas = emptyList()
        inkCanvas.scrollOffsetY = 0f
        // Flush Onyx hardware overlay so old strokes aren't visible
        inkCanvas.reinitializeRawDrawing()
        textView.setParagraphs(emptyList())
        textView.showScrollHint = false

        // Restart coordinator and load the showcase document
        coordinator?.start()
        loadShowcaseDocument()

        // Build the 4 tutorial steps
        steps = buildSteps()
        currentStepIndex = 0

        isActive = true
        showCurrentStep()
    }

    private fun buildSteps(): List<TutorialStep> {
        // Canvas rect in screen coordinates for the cutout
        // (computed when showing each step since layout may change)
        return listOf(
            // Phase 1: Editor basics
            TutorialStep(
                id = "write",
                cutoutRect = null,  // computed dynamically
                tooltipText = "Write with your stylus",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.PEN
            ),
            TutorialStep(
                id = "draw",
                cutoutRect = null,
                tooltipText = "Draw a shape — hold to snap",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.PEN
            ),
            TutorialStep(
                id = "erase",
                cutoutRect = null,
                tooltipText = "Scribble/strike content to erase",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.PEN
            ),
            TutorialStep(
                id = "scroll",
                cutoutRect = null,
                tooltipText = "Scroll with your finger",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.FINGER
            ),
            // Phase 2: Cornell Notes
            TutorialStep(
                id = "switch_to_cues",
                cutoutRect = null,
                tooltipText = "Tap the lightbulb to add cues",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.ANY
            ),
            TutorialStep(
                id = "peek_note",
                cutoutRect = null,
                tooltipText = "Peek at your notes",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.FINGER
            ),
            TutorialStep(
                id = "write_cue",
                cutoutRect = null,
                tooltipText = "Write a cue — annotate your notes",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.PEN
            )
        )
    }

    /** Whether the current step is in the Cornell Notes phase (toggle button should be active). */
    fun isCornellPhase(): Boolean {
        if (!isActive || currentStepIndex >= steps.size) return false
        return steps[currentStepIndex].id in listOf("switch_to_cues", "write_cue", "peek_note")
    }

    private fun getCanvasScreenRect(): Rect {
        val loc = IntArray(2)
        inkCanvas.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + inkCanvas.width, loc[1] + inkCanvas.height)
    }

    private val revealHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var revealTickCount = 0
    private var revealLoopCount = 0
    private val REVEAL_TICKS = 5
    private val REVEAL_INTERVAL_MS = 350L
    private val REVEAL_PAUSE_MS = 800L
    private val revealRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            revealTickCount++
            if (revealTickCount <= REVEAL_TICKS) {
                // Progressive reveal
                inkCanvas.ghostRevealProgress = revealTickCount.toFloat() / REVEAL_TICKS
                inkCanvas.pauseRawDrawing()
                inkCanvas.drawToSurface()
                inkCanvas.resumeRawDrawing()
                revealHandler.postDelayed(this, REVEAL_INTERVAL_MS)
            } else if (revealTickCount == REVEAL_TICKS + 1) {
                // Pause at full reveal before looping
                revealLoopCount++
                revealHandler.postDelayed(this, REVEAL_PAUSE_MS)
            } else {
                // Reset and loop
                revealTickCount = 0
                inkCanvas.ghostRevealProgress = 0f
                inkCanvas.pauseRawDrawing()
                inkCanvas.drawToSurface()
                inkCanvas.resumeRawDrawing()
                revealHandler.postDelayed(this, REVEAL_INTERVAL_MS)
            }
        }
    }

    private fun showCurrentStep() {
        val overlay = this.overlay ?: return
        if (currentStepIndex >= steps.size) {
            close()
            return
        }
        // Reset per-step state
        stepActionReceived = false
        scrollTextAppeared = false
        revealLoopCount = 0
        advanceHandler.removeCallbacks(advanceRunnable)
        revealHandler.removeCallbacks(revealRunnable)

        val step = steps[currentStepIndex]
        // From the scroll step onward, reveal the full screen so the preview area
        // stays visible and the user can access scroll, gutter toggle, or cue strip.
        val scrollStepIndex = steps.indexOfFirst { it.id == "scroll" }
        val cutout = if (currentStepIndex >= scrollStepIndex) {
            Rect(0, 0, inkCanvas.rootView.width, inkCanvas.rootView.height)
        } else {
            getCanvasScreenRect()
        }
        val isLast = currentStepIndex == steps.size - 1
        overlay.currentStep = step.copy(
            cutoutRect = cutout,
            isLastStep = isLast
        )
        overlay.stepIndex = currentStepIndex
        overlay.totalSteps = steps.size

        // Defer anchor tooltip for peek_note — layout must complete after fold
        if (step.id == "peek_note") {
            overlay.post {
                val anchorRect = getContextRailRect?.invoke()
                if (anchorRect != null) {
                    overlay.currentStep = overlay.currentStep?.copy(
                        anchorTooltipText = "Press and hold",
                        anchorTooltipRect = anchorRect
                    )
                }
            }
        }

        // Load ghost animations for this step (showcase document persists)
        loadStepGhosts(step.id)

        // Notify activity to update UI (e.g., toggle button visibility)
        onStepChanged?.invoke()
    }

    /** Load the showcase document once at tutorial start. Called from show(). */
    private fun loadShowcaseDocument() {
        val font = hersheyFont ?: return
        val ls = com.writer.view.HandwritingCanvasView.LINE_SPACING
        val tm = com.writer.view.HandwritingCanvasView.TOP_MARGIN
        val canvasWidth = inkCanvas.width.toFloat()

        val showcase = TutorialDemoContent.generateShowcaseDocument(font, canvasWidth, ls, tm)
        val colModel = inkCanvas.columnModel ?: return
        colModel.activeStrokes.addAll(showcase.strokes)
        colModel.diagramAreas.add(showcase.diagramArea)
        inkCanvas.loadStrokes(showcase.strokes)
        inkCanvas.diagramAreas = colModel.diagramAreas.toList()
        inkCanvas.drawToSurface()
        getCoordinator()?.recognizeAllLines()
    }

    /** Load ghost animations for the current step (overlay on the persistent showcase doc). */
    private fun loadStepGhosts(stepId: String) {
        val ghosts = when (stepId) {
            "erase" -> buildEraseGhosts()
            else -> emptyList()
        }

        inkCanvas.ghostStrokes = ghosts
        inkCanvas.ghostRevealProgress = 0f
        inkCanvas.drawToSurface()

        if (ghosts.isNotEmpty()) {
            revealTickCount = 0
            revealHandler.postDelayed(revealRunnable, REVEAL_INTERVAL_MS)
        }
    }

    private val advanceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val advanceRunnable = Runnable {
        if (!isActive) return@Runnable
        currentStepIndex++
        showCurrentStep()
        // Force e-ink refresh so the new tooltip is visible
        inkCanvas.pauseRawDrawing()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }
    private var stepActionReceived = false
    /** Build erase ghost strokes by looking up actual content positions. */
    private fun buildEraseGhosts(): List<InkStroke> {
        val segmenter = com.writer.recognition.LineSegmenter()
        val colModel = inkCanvas.columnModel ?: return emptyList()
        val strokesByLine = segmenter.groupByLine(colModel.activeStrokes)
        val ghosts = mutableListOf<InkStroke>()

        // Strikethrough over "timeline" — right half of line 2 strokes
        val line2 = strokesByLine[2]
        if (line2 != null && line2.isNotEmpty()) {
            val allMinX = line2.minOf { it.minX }
            val allMaxX = line2.maxOf { it.maxX }
            val midX = (allMinX + allMaxX) / 2f
            // "timeline" is the right portion of "- Launch timeline"
            val rightStrokes = line2.filter { it.minX > midX }
            if (rightStrokes.isNotEmpty()) {
                val startX = rightStrokes.minOf { it.minX }
                val endX = rightStrokes.maxOf { it.maxX }
                val centerY = rightStrokes.flatMap { it.points }.map { it.y }.average().toFloat()
                ghosts.add(TutorialDemoContent.generateStrikethrough(startX, endX, centerY))
            }
        }

        // Scratch-out over "Launch!" — strokes in lines 5-6 (ellipse + text in diagram)
        val diagramLines = (5..6).flatMap { strokesByLine[it] ?: emptyList() }
        if (diagramLines.isNotEmpty()) {
            val cx = (diagramLines.minOf { it.minX } + diagramLines.maxOf { it.maxX }) / 2f
            val cy = (diagramLines.minOf { it.minY } + diagramLines.maxOf { it.maxY }) / 2f
            val width = diagramLines.maxOf { it.maxX } - diagramLines.minOf { it.minX }
            val height = diagramLines.maxOf { it.maxY } - diagramLines.minOf { it.minY }
            ghosts.add(TutorialDemoContent.generateScratchOut(cx, cy, width, height))
        }

        return ghosts
    }

    private var scrollTextAppeared = false

    private val scrollCheckRunnable = Runnable {
        if (!isActive) return@Runnable
        if (currentStepIndex >= steps.size) return@Runnable
        val step = steps[currentStepIndex]
        if (step.id != "scroll") return@Runnable

        if (textView.totalTextHeight > 0) {
            scrollTextAppeared = true
            overlay?.currentStep = step.copy(
                cutoutRect = Rect(0, 0, inkCanvas.rootView.width, inkCanvas.rootView.height),
                tooltipText = "Great! Your writing appears as text"
            )
            // Force e-ink refresh to show the new tooltip
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            // Close after 2s
            advanceHandler.removeCallbacks(advanceRunnable)
            advanceHandler.postDelayed(advanceRunnable, 2000L)
        }
    }

    private fun stopRevealAnimation() {
        if (inkCanvas.ghostStrokes.isNotEmpty()) {
            revealHandler.removeCallbacks(revealRunnable)
            inkCanvas.ghostStrokes = emptyList()
            inkCanvas.ghostRevealProgress = 0f
        }
    }

    /** Called by WritingCoordinator/Activity when the expected action completes. */
    fun onStepAction(actionId: String) {
        if (!isActive) return

        // Stop the ghost animation on any user interaction — the pause/drawToSurface/
        // resume cycle in the animation steals pen input cycles causing stroke gaps.
        if (actionId == "pen_down") {
            stopRevealAnimation()
            // Reset advance timer — user is still interacting
            advanceHandler.removeCallbacks(advanceRunnable)
            return
        }

        if (currentStepIndex >= steps.size) return
        val currentStep = steps[currentStepIndex]

        val matches = when (currentStep.id) {
            "write" -> actionId == "stroke_completed"
            "draw" -> actionId == "stroke_replaced" || actionId == "diagram_created"
            "erase" -> actionId == "scratch_out" || actionId == "gesture_consumed"
            "scroll" -> actionId == "manual_scroll"
            "switch_to_cues" -> actionId == "folded_to_cues"
            "write_cue" -> actionId == "stroke_completed"
            "peek_note" -> actionId == "note_peeked"
            else -> false
        }

        if (!matches) return

        if (currentStep.id == "scroll") {
            // Wait for finger to lift (no more scroll events for 500ms),
            // then check if text appeared in preview.
            advanceHandler.removeCallbacks(scrollCheckRunnable)
            advanceHandler.postDelayed(scrollCheckRunnable, 500L)
            return
        }

        // For steps 1-3: delay 1.5s before advancing so user sees the result.
        // Reset timer if they perform another action (e.g., write more strokes).
        stepActionReceived = true
        advanceHandler.removeCallbacks(advanceRunnable)
        advanceHandler.postDelayed(advanceRunnable, 1500L)
    }

    fun close() {
        // Cancel any pending timers
        advanceHandler.removeCallbacks(advanceRunnable)
        advanceHandler.removeCallbacks(scrollCheckRunnable)
        revealHandler.removeCallbacks(revealRunnable)
        inkCanvas.ghostStrokes = emptyList()
        inkCanvas.ghostRevealProgress = 0f

        // Mark tutorial as seen
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TUTORIAL_SEEN, true).apply()

        // Hide overlay
        overlay?.currentStep = null

        // Restore split size
        val textParams = textView.layoutParams as LinearLayout.LayoutParams
        val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        textParams.height = savedTextHeight
        textParams.weight = savedTextWeight
        textView.layoutParams = textParams
        canvasParams.height = savedCanvasHeight
        canvasParams.weight = savedCanvasWeight
        inkCanvas.layoutParams = canvasParams

        // Restore original document
        val coordinator = getCoordinator()
        coordinator?.stop()
        coordinator?.reset()

        val strokes = savedStrokes
        if (strokes != null) {
            // Reload strokes into both the canvas and the document model
            // (reset() clears documentModel.main.activeStrokes)
            inkCanvas.loadStrokes(strokes)
            inkCanvas.scrollOffsetY = savedScrollY
            inkCanvas.drawToSurface()
        }

        textView.setParagraphs(emptyList())
        textView.showScrollHint = true

        // Restart coordinator with restored state.
        // restoreState() expects strokes already in documentModel — add them
        // before calling restoreState (same pattern as WritingActivity.restoreDocumentVisuals).
        coordinator?.start()
        val colModel = inkCanvas.columnModel
        if (savedState != null) {
            colModel?.activeStrokes?.addAll(savedState!!.main.strokes)
            colModel?.diagramAreas?.addAll(savedState!!.main.diagramAreas)
            coordinator?.restoreState(savedState!!)
        } else {
            val pending = getPendingRestore()
            if (pending != null) {
                clearPendingRestore()
                colModel?.activeStrokes?.addAll(pending.main.strokes)
                colModel?.diagramAreas?.addAll(pending.main.diagramAreas)
                coordinator?.restoreState(pending)
            }
        }

        textView.invalidate()
        inkCanvas.reinitializeRawDrawing()

        savedStrokes = null
        savedState = null
        isActive = false

        onClosed()
    }
}
