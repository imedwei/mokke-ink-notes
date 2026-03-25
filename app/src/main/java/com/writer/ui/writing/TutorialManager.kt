package com.writer.ui.writing

import android.content.Context
import android.graphics.Rect
import android.widget.LinearLayout
import com.writer.model.DocumentData
import com.writer.model.InkStroke
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

        // Restart coordinator on blank canvas so strokes are processed normally
        coordinator?.start()

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
                tooltipText = "Scribble over content to erase",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.PEN
            ),
            TutorialStep(
                id = "scroll",
                cutoutRect = null,
                tooltipText = "Scroll with your finger",
                tooltipPosition = TutorialStep.TooltipPosition.CENTER,
                acceptsInput = TutorialStep.InputType.FINGER
            )
        )
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
                // Full reveal complete — on erase step, remove the demo content
                // to show the erasing effect
                if (currentStepIndex < steps.size && steps[currentStepIndex].id == "erase" && revealLoopCount == 0) {
                    val docModel = inkCanvas.documentModel
                    docModel?.activeStrokes?.clear()
                    inkCanvas.clear()
                    inkCanvas.pauseRawDrawing()
                    inkCanvas.drawToSurface()
                    inkCanvas.resumeRawDrawing()
                }
                revealLoopCount++
                revealHandler.postDelayed(this, REVEAL_PAUSE_MS)
            } else {
                // Reset and loop — on erase step, re-add demo content to erase again
                if (currentStepIndex < steps.size && steps[currentStepIndex].id == "erase") {
                    reloadEraseContent()
                }
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
        // For scroll step, reveal the full screen (canvas + preview) so user sees text appear
        val cutout = if (step.id == "scroll") {
            Rect(0, 0, inkCanvas.rootView.width, inkCanvas.rootView.height)
        } else {
            getCanvasScreenRect()
        }
        overlay.currentStep = step.copy(cutoutRect = cutout)
        overlay.stepIndex = currentStepIndex
        overlay.totalSteps = steps.size

        // Load demo ghost strokes for this step
        loadDemoContent(step.id)
    }

    private fun loadDemoContent(stepId: String) {
        val ls = com.writer.view.HandwritingCanvasView.LINE_SPACING
        val tm = com.writer.view.HandwritingCanvasView.TOP_MARGIN
        val canvasWidth = inkCanvas.width.toFloat()
        val coordinator = getCoordinator()

        // Clear canvas between steps so each step has fresh demo content
        // Clear canvas between steps so each starts fresh with its own demo content
        coordinator?.reset()
        inkCanvas.clear()
        inkCanvas.diagramAreas = emptyList()
        inkCanvas.scrollOffsetY = 0f
        textView.setParagraphs(emptyList())
        coordinator?.start()

        val ghosts: List<InkStroke>
        val preloadedStrokes: List<InkStroke>

        val font = hersheyFont

        when (stepId) {
            "write" -> {
                ghosts = if (font != null) {
                    TutorialDemoContent.generateWelcomeText(
                        font = font,
                        startX = canvasWidth * 0.06f,
                        startY = tm + ls * 0.1f,
                        lineSpacing = ls
                    )
                } else emptyList()
                preloadedStrokes = emptyList()
            }
            "draw" -> {
                ghosts = listOf(TutorialDemoContent.generateRectangle(
                    cx = canvasWidth * 0.35f,
                    cy = tm + ls * 1.5f,
                    width = ls * 2f,
                    height = ls * 1.5f
                ))
                preloadedStrokes = emptyList()
            }
            "erase" -> {
                // Pre-load demo text and a shape as real strokes to erase
                val demoTextStrokes = if (font != null) {
                    TutorialDemoContent.generateHelloText(
                        font = font,
                        startX = canvasWidth * 0.08f,
                        startY = tm + ls * 0.1f,
                        lineSpacing = ls
                    )
                } else emptyList()
                val demoRect = TutorialDemoContent.generateSmallRect(
                    cx = canvasWidth * 0.5f,
                    cy = tm + ls * 2f,
                    lineSpacing = ls
                )
                preloadedStrokes = demoTextStrokes + listOf(demoRect)

                // Ghost animations showing how to erase
                ghosts = listOf(
                    TutorialDemoContent.generateStrikethrough(
                        startX = canvasWidth * 0.05f,
                        endX = canvasWidth * 0.45f,
                        y = tm + ls * 0.55f
                    ),
                    TutorialDemoContent.generateScratchOut(
                        cx = canvasWidth * 0.5f,
                        cy = tm + ls * 2f,
                        width = ls * 1.5f,
                        height = ls * 0.8f
                    )
                )
            }
            "scroll" -> {
                // Load real text strokes so scrolling up produces recognized text.
                // Use a smaller scale to fit within the canvas width.
                ghosts = emptyList()
                val scrollStrokes = mutableListOf<InkStroke>()
                if (font != null) {
                    // Scale to fit: Hershey chars are ~20 units wide on average,
                    // so N chars at scale S takes ~N*20*S pixels.
                    val maxCharsPerLine = 18
                    val textScale = (canvasWidth * 0.85f) / (maxCharsPerLine * 20f)
                    val jitter = textScale * 0.3f
                    val margin = canvasWidth * 0.06f

                    scrollStrokes.addAll(font.textToStrokes(
                        "The quick brown",
                        startX = margin,
                        startY = tm + ls * 0.4f,
                        scale = textScale,
                        jitter = jitter
                    ))
                    scrollStrokes.addAll(font.textToStrokes(
                        "fox jumps over",
                        startX = margin,
                        startY = tm + ls * 1.4f,
                        scale = textScale,
                        jitter = jitter
                    ))
                    scrollStrokes.addAll(font.textToStrokes(
                        "the lazy dog",
                        startX = margin,
                        startY = tm + ls * 2.4f,
                        scale = textScale,
                        jitter = jitter
                    ))
                }
                preloadedStrokes = scrollStrokes
            }
            else -> {
                ghosts = emptyList()
                preloadedStrokes = emptyList()
            }
        }

        // Load pre-built strokes as real content (for erase step)
        if (preloadedStrokes.isNotEmpty()) {
            for (stroke in preloadedStrokes) {
                inkCanvas.documentModel?.activeStrokes?.add(stroke)
            }
            inkCanvas.loadStrokes(
                (inkCanvas.getStrokes() + preloadedStrokes)
            )
        }

        inkCanvas.ghostStrokes = ghosts
        inkCanvas.ghostRevealProgress = 0f
        inkCanvas.drawToSurface()

        // Start continuous progressive reveal animation
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

    private fun reloadEraseContent() {
        val ls = com.writer.view.HandwritingCanvasView.LINE_SPACING
        val tm = com.writer.view.HandwritingCanvasView.TOP_MARGIN
        val canvasWidth = inkCanvas.width.toFloat()
        val font = hersheyFont

        val demoStrokes = mutableListOf<InkStroke>()
        if (font != null) {
            demoStrokes.addAll(TutorialDemoContent.generateHelloText(
                font, canvasWidth * 0.08f, tm + ls * 0.1f, ls
            ))
        }
        demoStrokes.add(TutorialDemoContent.generateSmallRect(
            canvasWidth * 0.5f, tm + ls * 2f, ls
        ))

        val docModel = inkCanvas.documentModel
        docModel?.activeStrokes?.clear()
        docModel?.activeStrokes?.addAll(demoStrokes)
        inkCanvas.loadStrokes(demoStrokes)
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
            // (reset() clears documentModel.activeStrokes)
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
        val docModel = inkCanvas.documentModel
        if (savedState != null) {
            docModel?.activeStrokes?.addAll(savedState!!.strokes)
            docModel?.diagramAreas?.addAll(savedState!!.diagramAreas)
            coordinator?.restoreState(savedState!!)
        } else {
            val pending = getPendingRestore()
            if (pending != null) {
                clearPendingRestore()
                docModel?.activeStrokes?.addAll(pending.strokes)
                docModel?.diagramAreas?.addAll(pending.diagramAreas)
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
