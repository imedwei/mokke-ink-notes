package com.writer.recognition

import com.writer.model.InkLine

/** A single recognition candidate with optional confidence score. */
data class RecognitionCandidate(val text: String, val score: Float?)

/** Bounding box for a recognized word, in document coordinate space. */
data class WordBoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/** Per-word confidence info for rendering low-confidence indicators. */
data class WordConfidence(
    val word: String,
    val confidence: Float,  // 0.0 = very low, 1.0 = very high
    val wordIndex: Int,
    /** Bounding box from recognizer (MyScript only, null for ML Kit). */
    val boundingBox: WordBoundingBox? = null
)

/** Recognition result containing one or more ranked candidates. */
data class RecognitionResult(
    val candidates: List<RecognitionCandidate>,
    /** Per-word confidence computed from candidate agreement. */
    val wordConfidences: List<WordConfidence> = emptyList(),
    /** Direct mapping from word index to stroke IDs, built post-recognition
     *  by matching recognizer bounding boxes against input strokes.
     *  Empty when bounding boxes are unavailable (ML Kit, stale results). */
    val wordStrokeMapping: Map<Int, Set<String>> = emptyMap()
) {
    /** The top candidate's text, or empty string if no candidates. */
    val text: String get() = candidates.firstOrNull()?.text ?: ""
    /** The top candidate's score, or null if unavailable. */
    val topScore: Float? get() = candidates.firstOrNull()?.score
}

/**
 * Abstraction for handwriting-to-text recognition engines.
 *
 * Implementations:
 *  - [GoogleMLKitTextRecognizer] — Google ML Kit Digital Ink (bundled model, works on all devices)
 *  - [OnyxHwrTextRecognizer] — Boox firmware's built-in MyScript engine via AIDL IPC
 *                           (Onyx Boox devices only, significantly better accuracy)
 */
interface TextRecognizer {

    /**
     * Initialize the recognition engine. Must be called before [recognizeLine].
     * May download models, bind to services, etc.
     *
     * @param languageTag BCP-47 language tag, e.g. "en-US"
     * @throws Exception if initialization fails
     */
    suspend fun initialize(languageTag: String)

    /**
     * Recognize a single line of handwriting.
     *
     * @param line       the strokes and bounding box for one line
     * @param preContext up to 20 characters of preceding text for language model context
     * @return recognized text (trimmed), or empty string if recognition fails
     */
    suspend fun recognizeLine(line: InkLine, preContext: String = ""): String

    /**
     * Recognize with full candidate list and confidence scores.
     * Default implementation wraps [recognizeLine] as a single candidate.
     */
    suspend fun recognizeLineWithCandidates(line: InkLine, preContext: String = ""): RecognitionResult {
        return RecognitionResult(listOf(RecognitionCandidate(recognizeLine(line, preContext), null)))
    }

    /** Release resources (models, service bindings, etc.). */
    fun close()
}
