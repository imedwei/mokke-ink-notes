package com.writer.recognition

import com.writer.model.InkLine

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

    /** Release resources (models, service bindings, etc.). */
    fun close()
}
