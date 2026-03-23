package com.writer.ui.writing

import android.content.Context
import android.widget.LinearLayout
import com.writer.model.InkStroke
import com.writer.model.DocumentData
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView

/**
 * Manages tutorial lifecycle: showing the interactive tutorial overlay,
 * saving/restoring document state around it, and tracking whether the
 * user has already seen the tutorial.
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

    fun show() {
        val coordinator = getCoordinator()

        // Save current document state
        savedState = coordinator?.getState()
        savedStrokes = inkCanvas.getStrokes()
        savedScrollY = inkCanvas.scrollOffsetY

        // Stop coordinator (removes callbacks)
        coordinator?.stop()

        // Save layout params
        val textParams = textView.layoutParams as LinearLayout.LayoutParams
        val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        savedTextHeight = textParams.height
        savedTextWeight = textParams.weight
        savedCanvasHeight = canvasParams.height
        savedCanvasWeight = canvasParams.weight

        // Measure text content to compute split
        textView.tutorialMode = true
        textView.textScrollOffset = 0f

        // First pass: generate with current canvas height to get text paragraphs
        val totalHeight = textView.height + inkCanvas.height
        val textNeeded = run {
            val temp = TutorialContent.generate(inkCanvas.width, inkCanvas.height)
            textView.setContent(temp.textParagraphs, temp.diagramDisplays)
            textView.totalTextHeight + 130  // 110 close button + 5 gap + 15 bottom padding
        }

        // Compute split: text gets what it needs, canvas gets the rest
        val newCanvasHeight = (totalHeight - textNeeded).coerceAtLeast(400)
        val newTextHeight = totalHeight - newCanvasHeight
        textParams.height = newTextHeight
        textParams.weight = 0f
        textView.layoutParams = textParams
        canvasParams.height = newCanvasHeight
        canvasParams.weight = 0f
        inkCanvas.layoutParams = canvasParams

        // Second pass: regenerate with correct canvas height (for auto-scroll hint positioning)
        val tutorial = TutorialContent.generate(inkCanvas.width, newCanvasHeight)
        textView.setContent(tutorial.textParagraphs, tutorial.diagramDisplays)

        // Load tutorial strokes into canvas
        inkCanvas.loadStrokes(tutorial.strokes)
        inkCanvas.scrollOffsetY = tutorial.scrollOffsetY
        inkCanvas.annotationStrokes = tutorial.annotations
        inkCanvas.textAnnotations = tutorial.textAnnotations
        inkCanvas.diagramAreas = tutorial.diagramAreas
        inkCanvas.tutorialMode = true
        inkCanvas.drawToSurface()

        // Wire close tutorial tap
        textView.onCloseTutorialTap = { close() }

        isActive = true
    }

    fun close() {
        // Mark tutorial as seen
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TUTORIAL_SEEN, true).apply()

        // Restore split size
        val textParams = textView.layoutParams as LinearLayout.LayoutParams
        val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        textParams.height = savedTextHeight
        textParams.weight = savedTextWeight
        textView.layoutParams = textParams
        canvasParams.height = savedCanvasHeight
        canvasParams.weight = savedCanvasWeight
        inkCanvas.layoutParams = canvasParams

        // Clear tutorial annotations and diagram areas
        inkCanvas.diagramAreas = emptyList()
        inkCanvas.clearAnnotations()

        // Restore original document
        val strokes = savedStrokes
        if (strokes != null) {
            inkCanvas.loadStrokes(strokes)
            inkCanvas.scrollOffsetY = savedScrollY
            inkCanvas.drawToSurface()
        }

        // Clear text view tutorial mode and reset paragraphs before restore
        textView.tutorialMode = false
        textView.setParagraphs(emptyList())

        // Reset and restart coordinator (restoreState will set correct paragraphs)
        val coordinator = getCoordinator()
        coordinator?.reset()
        coordinator?.start()
        if (savedState != null) {
            coordinator?.restoreState(savedState!!)
        } else {
            val pending = getPendingRestore()
            if (pending != null) {
                clearPendingRestore()
                coordinator?.restoreState(pending)
            }
        }

        textView.invalidate()

        // Clear close button handler
        textView.onCloseTutorialTap = null

        // Fully reinitialize SDK — canvas resized, so SDK needs fresh state
        inkCanvas.reinitializeRawDrawing()

        // Clean up saved state
        savedStrokes = null
        savedState = null
        isActive = false

        onClosed()
    }
}
