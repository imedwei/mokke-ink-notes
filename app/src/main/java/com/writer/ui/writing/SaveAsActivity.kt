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
import com.writer.recognition.TextRecognizerFactory
import com.writer.ui.writing.GestureHandler
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
        handwritingInput.placeholderText = currentName

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
        val rec = recognizer ?: return
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
                    rec.recognizeLine(line)
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
        recognizer?.close()
    }
}
