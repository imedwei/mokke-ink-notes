package com.writer.ui.writing

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.writer.R
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.view.ScratchOutDetection
import com.writer.view.HandwritingCanvasView
import com.writer.recognition.TextRecognizerFactory
import com.writer.view.HandwritingNameInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaveAsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SaveAsActivity"
        const val EXTRA_CURRENT_NAME = "current_name"
        const val EXTRA_RESULT_NAME = "name"
    }

    private lateinit var nameDisplay: TextView
    private lateinit var handwritingInput: HandwritingNameInput

    private var recognizer: com.writer.recognition.TextRecognizer? = null
    private val allStrokes = mutableListOf<InkStroke>()
    private var currentName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_save_as)

        // Tap outside the dialog to cancel
        val overlay = findViewById<android.widget.FrameLayout>(R.id.dialogOverlay)
        overlay.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        // Prevent taps on the dialog content from dismissing
        overlay.getChildAt(0).setOnClickListener { }

        lifecycleScope.launch {
            val rec = TextRecognizerFactory.create(this@SaveAsActivity)
            try {
                rec.initialize("en-US")
                recognizer = rec
            } catch (e: Exception) {
                rec.close()
                Log.w(TAG, "Recognizer init failed", e)
                Toast.makeText(this@SaveAsActivity, "Handwriting recognition unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        nameDisplay = findViewById(R.id.nameDisplay)
        handwritingInput = findViewById(R.id.handwritingInput)

        // Pre-populate with current document name
        currentName = intent.getStringExtra(EXTRA_CURRENT_NAME) ?: ""
        nameDisplay.text = currentName
        // Don't show placeholder — the current name is already in nameDisplay above

        // Cancel pending EPD refresh when pen goes down again
        handwritingInput.onStrokeStarted = {
            handwritingInput.cancelPendingRefresh()
        }

        // Handle stroke completion
        handwritingInput.onStrokeCompleted = { stroke ->
            onStrokeCompleted(stroke)
        }

        // Buttons
        findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val name = currentName.trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Write a name first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val resultIntent = Intent().apply {
                putExtra(EXTRA_RESULT_NAME, name)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun onStrokeCompleted(stroke: InkStroke) {
        val erased = when {
            ScratchOutDetection.isScratchOut(stroke.points, allStrokes, HandwritingCanvasView.LINE_SPACING) ->
                findScratchOutTargets(stroke)
            GestureHandler.isStrikethroughShape(stroke) ->
                findStrikethroughTargets(stroke)
            else -> null
        }

        if (erased != null) {
            eraseStrokes(erased, gestureStrokeId = stroke.strokeId)
        } else {
            allStrokes.add(stroke)
            recognizeAll()
        }
    }

    private fun findScratchOutTargets(gesture: InkStroke): List<InkStroke> {
        val overlapping = allStrokes.filter { ScratchOutDetection.strokesIntersect(gesture.points, it.points) }
        return overlapping
    }

    private fun findStrikethroughTargets(gesture: InkStroke): List<InkStroke>? {
        val overlapping = allStrokes.filter { it.maxX >= gesture.minX && it.minX <= gesture.maxX }
        if (overlapping.isEmpty()) {
            handwritingInput.removeStrokes(setOf(gesture.strokeId))
            return null // no targets, just remove the gesture line
        }
        return overlapping
    }

    private fun eraseStrokes(targets: List<InkStroke>, gestureStrokeId: String) {
        if (targets.isEmpty()) return
        val idsToRemove = targets.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStrokeId)
        allStrokes.removeAll { it.strokeId in idsToRemove }
        handwritingInput.removeStrokes(idsToRemove)
        handwritingInput.scheduleRefresh(lifecycleScope, extraViews = listOf(nameDisplay))
        recognizeAll()
        Log.i(TAG, "Erased ${targets.size} strokes")
    }

    private fun recognizeAll() {
        val rec = recognizer ?: return
        if (allStrokes.isEmpty()) {
            currentName = intent.getStringExtra(EXTRA_CURRENT_NAME) ?: ""
            updateNameDisplay(currentName)
            return
        }

        // Build a single InkLine from all strokes, sorted left-to-right
        val sorted = allStrokes.sortedBy { it.minX }
        val line = InkLine.build(sorted)

        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    rec.recognizeLine(line)
                }
                currentName = text.trim()
                updateNameDisplay(currentName)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
            }
        }
    }

    private fun updateNameDisplay(text: String) {
        nameDisplay.text = text
        handwritingInput.scheduleRefresh(lifecycleScope, extraViews = listOf(nameDisplay))
    }

    override fun onDestroy() {
        handwritingInput.closeRawDrawing()
        super.onDestroy()
        recognizer?.close()
    }
}
