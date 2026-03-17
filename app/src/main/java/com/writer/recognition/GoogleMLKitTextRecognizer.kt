package com.writer.recognition

import android.util.Log
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.writer.model.InkLine
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit Digital Ink recognition engine.
 * Works on all Android devices. Downloads language models on first use.
 */
class GoogleMLKitTextRecognizer : TextRecognizer {

    companion object {
        private const val TAG = "GoogleMLKitTextRecognizer"
        private const val PRE_CONTEXT_LENGTH = 20
    }

    private var recognizer: DigitalInkRecognizer? = null
    private val modelManager = ModelManager()

    override suspend fun initialize(languageTag: String) {
        val model = modelManager.ensureModelDownloaded(languageTag)
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        Log.i(TAG, "Recognizer initialized for $languageTag")
    }

    override suspend fun recognizeLine(line: InkLine, preContext: String): String {
        val rec = recognizer ?: throw IllegalStateException("Recognizer not initialized")

        val inkBuilder = Ink.builder()
        for (stroke in line.strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            for (point in stroke.points) {
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timestamp))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        val ink = inkBuilder.build()

        val bb = line.boundingBox
        val contextBuilder = RecognitionContext.builder()
            .setPreContext(if (preContext.isNotEmpty()) preContext.takeLast(PRE_CONTEXT_LENGTH) else "")
        if (bb.width() > 0 && bb.height() > 0) {
            contextBuilder.setWritingArea(WritingArea(bb.width(), bb.height()))
        }

        val result = rec.recognize(ink, contextBuilder.build()).await()
        val text = result.getCandidates().firstOrNull()?.getText() ?: ""
        Log.d(TAG, "Recognized: \"$text\" (${result.getCandidates().size} candidates)")
        return text
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }
}
