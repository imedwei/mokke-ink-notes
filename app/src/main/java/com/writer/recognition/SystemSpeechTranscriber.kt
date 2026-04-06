package com.writer.recognition

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * [AudioTranscriber] backed by Android's [SpeechRecognizer].
 *
 * Uses the system speech recognition service (Google, Samsung, etc.).
 * Streams partial results in real time and delivers a final result on stop.
 */
class SystemSpeechTranscriber(private val context: Context) : AudioTranscriber {

    private val tag = "SystemSpeechTranscriber"

    private var recognizer: SpeechRecognizer? = null
    override var onPartialResult: ((String) -> Unit)? = null
    override var onFinalResult: ((String) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null
    /** Called when the recognizer is ready and has claimed the mic. */
    var onReady: (() -> Unit)? = null
    /** Called with RMS dB level on each audio frame — use for quality monitoring. */
    var onRmsChanged: ((Float) -> Unit)? = null
    override var isListening: Boolean = false
        private set

    override fun start(languageTag: String) {
        if (isListening) stop()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        this.recognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(tag, "Ready for speech")
                onReady?.invoke()
            }

            override fun onBeginningOfSpeech() {
                Log.d(tag, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                onRmsChanged?.invoke(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(tag, "Speech ended")
            }

            override fun onError(error: Int) {
                Log.w(tag, "Recognition error: $error")
                isListening = false
                onError?.invoke(error)
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull() ?: ""
                Log.d(tag, "Final result: $text")
                isListening = false
                onFinalResult?.invoke(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull() ?: return
                onPartialResult?.invoke(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        isListening = true
        recognizer.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
        isListening = false
    }

    override fun close() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }
}
