package com.writer.ui.writing

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
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
    private lateinit var textScrollView: ScrollView
    private lateinit var statusText: TextView
    private lateinit var clearButton: Button

    private lateinit var documentModel: DocumentModel
    private lateinit var recognizer: HandwritingRecognizer
    private var coordinator: WritingCoordinator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_writing)

        inkCanvas = findViewById(R.id.inkCanvas)
        recognizedTextView = findViewById(R.id.recognizedTextView)
        textScrollView = findViewById(R.id.textScrollView)
        statusText = findViewById(R.id.statusText)
        clearButton = findViewById(R.id.clearButton)

        documentModel = DocumentModel()
        recognizer = HandwritingRecognizer()

        clearButton.setOnClickListener {
            inkCanvas.clear()
            documentModel.activeStrokes.clear()
            documentModel.paragraphs.clear()
            recognizedTextView.setParagraphs(emptyList())
            coordinator?.reset()
            statusText.text = "Cleared"
        }

        // Initialize recognizer then start the coordinator
        statusText.text = "Loading model..."
        lifecycleScope.launch {
            try {
                recognizer.initialize(documentModel.language)
                statusText.text = "Ready"
                startCoordinator()
            } catch (e: Exception) {
                statusText.text = "Model error"
                Toast.makeText(
                    this@WritingActivity,
                    "Failed to load recognition model: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                startCoordinatorWithoutRecognition()
            }
        }
    }

    private fun startCoordinator() {
        coordinator = WritingCoordinator(
            documentModel = documentModel,
            recognizer = recognizer,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            textScrollView = textScrollView,
            scope = lifecycleScope,
            onStatusUpdate = { status ->
                runOnUiThread {
                    statusText.text = status
                }
            }
        )
        coordinator?.start()

        inkCanvas.post {
            statusText.text = "Ready"
        }
    }

    private fun startCoordinatorWithoutRecognition() {
        inkCanvas.onStrokeCompleted = { _ ->
            val count = inkCanvas.getStrokeCount()
            statusText.text = "Strokes: $count (no recognition)"
        }

        inkCanvas.post {
            statusText.text = "Ready - no recognition"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator?.stop()
        recognizer.close()
    }
}
