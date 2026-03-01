package com.writer.ui.writing

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.writer.R
import com.writer.model.DocumentModel
import com.writer.recognition.HandwritingRecognizer
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.launch

class WritingActivity : AppCompatActivity() {

    private lateinit var inkCanvas: HandwritingCanvasView
    private lateinit var recognizedTextView: RecognizedTextView
    private lateinit var statusText: TextView

    private lateinit var documentModel: DocumentModel
    private lateinit var recognizer: HandwritingRecognizer
    private var coordinator: WritingCoordinator? = null

    // Split resize state
    private var defaultTextHeight = 0
    private var defaultCanvasHeight = 0
    private var splitOffset = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_writing)

        inkCanvas = findViewById(R.id.inkCanvas)
        recognizedTextView = findViewById(R.id.recognizedTextView)
        statusText = findViewById(R.id.statusText)

        documentModel = DocumentModel()
        recognizer = HandwritingRecognizer()

        // Capture default heights after initial layout, then wire up the gutter
        recognizedTextView.post {
            defaultTextHeight = recognizedTextView.height
            defaultCanvasHeight = inkCanvas.height
            setupTextGutter()
        }

        // Initialize recognizer then start the coordinator
        showStatus("Loading model...")
        lifecycleScope.launch {
            try {
                recognizer.initialize(documentModel.language)
                hideStatus()
                startCoordinator()
            } catch (e: Exception) {
                showStatus("Model error")
                Toast.makeText(
                    this@WritingActivity,
                    "Failed to load recognition model: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                startCoordinatorWithoutRecognition()
            }
        }
    }

    private fun setupTextGutter() {
        recognizedTextView.onGutterDrag = { delta ->
            splitOffset = (splitOffset + delta).coerceIn(0f, defaultCanvasHeight.toFloat())

            val newTextHeight = defaultTextHeight + splitOffset.toInt()
            val newCanvasHeight = defaultCanvasHeight - splitOffset.toInt()

            val textParams = recognizedTextView.layoutParams as LinearLayout.LayoutParams
            textParams.height = newTextHeight
            textParams.weight = 0f
            recognizedTextView.layoutParams = textParams

            val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
            canvasParams.height = newCanvasHeight.coerceAtLeast(0)
            canvasParams.weight = 0f
            inkCanvas.layoutParams = canvasParams
        }
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.text = ""
        statusText.visibility = View.GONE
    }

    private fun startCoordinator() {
        coordinator = WritingCoordinator(
            documentModel = documentModel,
            recognizer = recognizer,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            scope = lifecycleScope,
            onStatusUpdate = { status ->
                runOnUiThread {
                    if (status.isEmpty()) hideStatus() else showStatus(status)
                }
            }
        )
        coordinator?.start()
    }

    private fun startCoordinatorWithoutRecognition() {
        inkCanvas.onStrokeCompleted = { _ ->
            val count = inkCanvas.getStrokeCount()
            showStatus("Strokes: $count (no recognition)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator?.stop()
        recognizer.close()
    }
}
