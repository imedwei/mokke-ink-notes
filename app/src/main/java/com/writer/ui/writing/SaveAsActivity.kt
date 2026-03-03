package com.writer.ui.writing

import android.content.Intent
import android.graphics.RectF
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
import com.writer.recognition.HandwritingRecognizer
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

    private val recognizer = HandwritingRecognizer()
    private var recognizerReady = false
    private val allStrokes = mutableListOf<InkStroke>()
    private var currentName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_save_as)

        nameDisplay = findViewById(R.id.nameDisplay)
        handwritingInput = findViewById(R.id.handwritingInput)

        // Pre-populate with current document name
        currentName = intent.getStringExtra(EXTRA_CURRENT_NAME) ?: ""
        nameDisplay.text = currentName
        handwritingInput.placeholderText = currentName

        // Initialize recognizer
        lifecycleScope.launch {
            try {
                recognizer.initialize("en-US")
                recognizerReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init recognizer", e)
                Toast.makeText(this@SaveAsActivity, "Recognition unavailable", Toast.LENGTH_SHORT).show()
            }
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
        if (isStrikethroughGesture(stroke)) {
            handleStrikethrough(stroke)
        } else {
            allStrokes.add(stroke)
            recognizeAll()
        }
    }

    private fun isStrikethroughGesture(stroke: InkStroke): Boolean {
        return GestureHandler.isStrikethroughShape(stroke)
    }

    private fun handleStrikethrough(gestureStroke: InkStroke) {
        val gestureMinX = gestureStroke.minX
        val gestureMaxX = gestureStroke.maxX

        val overlapping = allStrokes.filter { stroke ->
            stroke.maxX >= gestureMinX && stroke.minX <= gestureMaxX
        }

        if (overlapping.isEmpty()) {
            // Remove just the gesture stroke from the view
            handwritingInput.removeStrokes(setOf(gestureStroke.strokeId))
            return
        }

        val idsToRemove = overlapping.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)

        allStrokes.removeAll { it.strokeId in idsToRemove }

        handwritingInput.removeStrokes(idsToRemove)

        recognizeAll()
        Log.i(TAG, "Strikethrough: removed ${overlapping.size} strokes")
    }

    private fun recognizeAll() {
        if (!recognizerReady) return
        if (allStrokes.isEmpty()) {
            currentName = intent.getStringExtra(EXTRA_CURRENT_NAME) ?: ""
            nameDisplay.text = currentName
            return
        }

        // Build a single InkLine from all strokes, sorted left-to-right
        val sorted = allStrokes.sortedBy { it.minX }
        val line = InkLine.build(sorted)

        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    recognizer.recognizeLine(line)
                }
                currentName = text.trim()
                nameDisplay.text = currentName
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }
}
