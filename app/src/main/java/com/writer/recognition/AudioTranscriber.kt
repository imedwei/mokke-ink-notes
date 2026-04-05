package com.writer.recognition

/**
 * Abstraction for audio-to-text transcription engines.
 *
 * Implementations:
 *  - [SystemSpeechTranscriber] — Android SpeechRecognizer (on-device, streaming)
 *
 * Mirrors [TextRecognizer] pattern: start/stop lifecycle with callback-based results.
 */
interface AudioTranscriber {

    /**
     * Start listening and transcribing.
     * Partial results arrive via [onPartialResult], final result via [onFinalResult].
     *
     * @param languageTag BCP-47 language tag, e.g. "en-US"
     */
    fun start(languageTag: String)

    /** Stop listening. Triggers [onFinalResult] with the last recognized text. */
    fun stop()

    /** Called with intermediate recognition results while speaking. */
    var onPartialResult: ((String) -> Unit)?

    /** Called with the final recognized text when recognition completes. */
    var onFinalResult: ((String) -> Unit)?

    /** Called when an error occurs during recognition. */
    var onError: ((Int) -> Unit)?

    /** Release resources. */
    fun close()

    /** Whether the transcriber is currently listening. */
    val isListening: Boolean
}
