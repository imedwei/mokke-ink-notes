package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.recognition.RecognitionResult
import com.writer.recognition.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Build recognition context from recent cached text above [lineIndex]. */
internal fun buildPreContext(lineTextCache: Map<Int, String>, lineIndex: Int): String {
    return lineTextCache.entries
        .filter { it.key < lineIndex && it.value.isNotBlank() && it.value != "[?]" }
        .sortedBy { it.key }
        .joinToString(" ") { it.value }
        .takeLast(20)
}

/** Callback interface for LineRecognitionManager to communicate with its host. */
interface RecognitionManagerHost {
    val lineTextCache: MutableMap<Int, String>
    val userRenamed: Boolean
    val everHiddenLines: Set<Int>
    fun onHeadingDetected(heading: String)
    fun isDiagramLine(lineIndex: Int): Boolean
    fun onRecognitionComplete(lineIndex: Int)
}

/**
 * Manages text recognition for handwriting lines. All recognition flows pass through this class.
 *
 * ## Recognition triggers
 *
 * | Trigger              | Method                        | Event                                                    |
 * |----------------------|-------------------------------|----------------------------------------------------------|
 * | Idle timeout (2s)    | `eagerRecognizeLine`          | User stops writing → `DisplayManager.onIdle()`           |
 * | Line change          | `eagerRecognizeLine`          | `onStrokeCompleted` detects stroke on a different line    |
 * | Re-recognition       | `recognizeRenderedLine`       | Previous recognition completes with stale strokes         |
 * | Document load / save | `recognizeAllLines`           | App init, reload, language change, auto-save              |
 * | Replacement edit     | `recognizer.recognizeLine`    | Idle fires with active `pendingWordEdit`                  |
 *
 * ## Skip conditions
 *
 * - **Already cached** — `eagerRecognizeLine` returns if `lineTextCache` has an entry.
 * - **Already recognizing** — queued to [pendingRerecognize] instead of starting duplicate work.
 * - **Current writing line** — `recognizeAllLines()` skips [RecognitionManagerHost.currentLineIndex]
 *   to avoid caching partial results while the user is still writing.
 * - **Diagram line / no strokes** — [doRecognizeLine] returns null.
 *
 * ## Re-recognition
 *
 * When [eagerRecognizeLine] or [recognizeRenderedLine] is called for a line that is already
 * in [recognizingLines], the line is added to [pendingRerecognize]. When the in-flight
 * recognition completes, it checks this set and calls [recognizeRenderedLine] (which forces
 * cache removal) to re-recognize with the current stroke set.
 */
class LineRecognitionManager(
    private val columnModel: ColumnModel,
    private val recognizer: TextRecognizer,
    private val lineSegmenter: LineSegmenter,
    private val strokeClassifier: StrokeClassifier,
    private val scope: CoroutineScope,
    private val host: RecognitionManagerHost,
    private val canvasWidthProvider: () -> Float
) {
    companion object {
        private const val TAG = "LineRecognitionManager"
    }

    /** Track which lines are currently being recognized (avoid duplicates). */
    internal val recognizingLines = mutableSetOf<Int>()

    /** Full recognition results with candidates, keyed by line index. */
    val lineRecognitionResults = mutableMapOf<Int, RecognitionResult>()

    fun isRecognizing(lineIndex: Int): Boolean = recognizingLines.contains(lineIndex)
    fun markRecognizing(lineIndex: Int) { recognizingLines.add(lineIndex) }
    /** Lines that need re-recognition after current recognition finishes. */
    val pendingRerecognize = mutableSetOf<Int>()
    /** Stroke count when recognition last started, per line. Used to skip redundant re-recognition. */
    private val lastRecognizedStrokeCount = mutableMapOf<Int, Int>()

    fun eagerRecognizeLine(lineIndex: Int) {
        if (host.lineTextCache.containsKey(lineIndex)) return
        if (recognizingLines.contains(lineIndex)) {
            // Recognition is in flight with potentially stale strokes —
            // queue re-recognition so the completed handler picks it up.
            pendingRerecognize.add(lineIndex)
            return
        }
        recognizingLines.add(lineIndex)

        scope.launch {
            val text = doRecognizeLine(lineIndex) ?: return@launch
            Log.d(TAG, "Eager recognized line $lineIndex: \"$text\"")
            host.onRecognitionComplete(lineIndex)
            if (lineIndex in pendingRerecognize) {
                pendingRerecognize.remove(lineIndex)
                // Skip re-recognition if stroke count hasn't changed
                val currentCount = lineSegmenter.getStrokesForLine(columnModel.activeStrokes, lineIndex).size
                if (currentCount != lastRecognizedStrokeCount[lineIndex]) {
                    host.lineTextCache.remove(lineIndex)
                    Log.d(TAG, "Re-recognizing line $lineIndex (strokes changed: ${lastRecognizedStrokeCount[lineIndex]} → $currentCount)")
                    recognizeRenderedLine(lineIndex)
                }
            }
        }
    }

    fun recognizeRenderedLine(lineIndex: Int) {
        if (recognizingLines.contains(lineIndex)) {
            pendingRerecognize.add(lineIndex)
            return
        }
        recognizingLines.add(lineIndex)
        host.lineTextCache.remove(lineIndex)

        scope.launch {
            val text = doRecognizeLine(lineIndex) ?: return@launch
            Log.d(TAG, "Rendered line recognized $lineIndex: \"$text\"")
            host.onRecognitionComplete(lineIndex)
            if (lineIndex in pendingRerecognize) {
                pendingRerecognize.remove(lineIndex)
                val currentCount = lineSegmenter.getStrokesForLine(columnModel.activeStrokes, lineIndex).size
                if (currentCount != lastRecognizedStrokeCount[lineIndex]) {
                    recognizeRenderedLine(lineIndex)
                }
            }
        }
    }

    /**
     * Core recognition: get strokes for [lineIndex], filter markers, recognize,
     * and cache the result. Returns the recognized text, or null if there were
     * no strokes or recognition failed.
     */
    suspend fun doRecognizeLine(lineIndex: Int): String? {
        if (host.isDiagramLine(lineIndex)) {
            recognizingLines.remove(lineIndex)
            return null
        }
        try {
            val allStrokes = lineSegmenter.getStrokesForLine(
                columnModel.activeStrokes, lineIndex
            )
            if (allStrokes.isEmpty()) {
                recognizingLines.remove(lineIndex)
                return null
            }
            val prevLineStrokes = lineSegmenter.getStrokesForLine(
                columnModel.activeStrokes, lineIndex - 1
            ).takeIf { it.isNotEmpty() }
            val strokes = strokeClassifier.filterMarkerStrokes(allStrokes, canvasWidthProvider(), prevLineStrokes)
            if (strokes.isEmpty()) {
                recognizingLines.remove(lineIndex)
                return null
            }

            val line = lineSegmenter.buildInkLine(strokes, lineIndex)
            val preContext = buildPreContext(lineIndex)
            lastRecognizedStrokeCount[lineIndex] = strokes.size
            val result = withContext(Dispatchers.IO) {
                recognizer.recognizeLineWithCandidates(line, preContext)
            }
            val text = result.text

            host.lineTextCache[lineIndex] = text
            lineRecognitionResults[lineIndex] = result
            recognizingLines.remove(lineIndex)
            checkHeadingRename(lineIndex, text, allStrokes)
            return text
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed for line $lineIndex", e)
            host.lineTextCache[lineIndex] = "[?]"
            recognizingLines.remove(lineIndex)
            pendingRerecognize.remove(lineIndex)
            return null
        }
    }

    private fun checkHeadingRename(lineIndex: Int, text: String, strokes: List<InkStroke>) {
        if (host.userRenamed) return
        if (lineIndex != 0) return
        if (text.isEmpty() || text == "[?]") return
        host.onHeadingDetected(text)
    }

    fun buildPreContext(lineIndex: Int): String =
        buildPreContext(host.lineTextCache, lineIndex)

    fun reset() {
        recognizingLines.clear()
        pendingRerecognize.clear()
    }
}
