package com.writer.recognition

import android.util.Log
import com.writer.model.InkLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps a primary recognizer and swaps to a fallback once the primary signals
 * it has stopped responding. Used to keep recognition alive when the Onyx
 * KHwrService wedges in a state where bind+init succeed but batchRecognize
 * never returns — a rebind doesn't unstick the service, so once we detect it
 * we route subsequent calls through ML Kit instead.
 *
 * The fallback is initialized lazily on first wedge (ML Kit downloads a ~20 MB
 * model the first time, so we don't pay for it unless we need to).
 *
 * @param onWedge fired exactly once when [reportPrimaryUnresponsive] is called.
 *                The host UI uses this to surface a status banner / toast.
 */
class FallbackingTextRecognizer(
    private val primary: TextRecognizer,
    private val fallbackFactory: () -> TextRecognizer,
    var onWedge: (() -> Unit)? = null,
) : TextRecognizer {

    companion object { private const val TAG = "FallbackingTextRecognizer" }

    private var languageTag: String = "en-US"
    @Volatile private var wedged = false
    @Volatile private var fallback: TextRecognizer? = null
    private val fallbackMutex = Mutex()

    /** Called by the primary recognizer when it has stopped responding.
     *  Marks the wrapper as wedged so subsequent recognize calls route to
     *  the fallback, and fires [onWedge] (idempotently). */
    fun reportPrimaryUnresponsive() {
        if (wedged) return
        wedged = true
        Log.w(TAG, "Primary recognizer unresponsive; will fall back to ML Kit")
        onWedge?.invoke()
    }

    override suspend fun initialize(languageTag: String) {
        this.languageTag = languageTag
        primary.initialize(languageTag)
    }

    override suspend fun recognizeLine(line: InkLine, preContext: String): String {
        if (wedged) return ensureFallback().recognizeLine(line, preContext)
        return primary.recognizeLine(line, preContext)
    }

    private suspend fun ensureFallback(): TextRecognizer {
        fallback?.let { return it }
        return fallbackMutex.withLock {
            fallback ?: fallbackFactory().also {
                it.initialize(languageTag)
                fallback = it
            }
        }
    }

    override fun close() {
        try { primary.close() } catch (_: Exception) {}
        try { fallback?.close() } catch (_: Exception) {}
    }
}
