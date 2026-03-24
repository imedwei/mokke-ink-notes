package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.StrokeDownsampler

/**
 * Always-on ring buffer that records raw strokes and processing decisions.
 *
 * Every stroke is captured via [recordStroke] before any processing (scratch-out,
 * shape snap, recognition). Processing decisions are recorded via [recordEvent]
 * as they happen. When the user taps "Report Bug", [snapshot] freezes the current
 * state into a serializable [Snapshot].
 *
 * The ring buffer has a fixed capacity ([maxStrokes]). When full, the oldest
 * strokes and their associated events are dropped.
 */
class StrokeEventLog(private val maxStrokes: Int = 50) {

    enum class EventType {
        /** Stroke added to document as freehand */
        ADDED,
        /** Stroke detected as scratch-out; detail = erased stroke IDs */
        SCRATCH_OUT,
        /** Stroke snapped to geometric shape; detail = shape type */
        SHAPE_SNAPPED,
        /** Raw stroke replaced with snapped version; detail = new stroke type */
        REPLACED,
        /** Text recognized on a line; detail = "line:N text:..." */
        RECOGNIZED,
        /** Diagram area auto-created; detail = "start:N height:M" */
        DIAGRAM_CREATED,
        /** Diagram area expanded; detail = "old:S-E new:S-E" */
        DIAGRAM_EXPANDED,
        /** Stroke consumed by gesture handler (strikethrough, etc.) */
        GESTURE_CONSUMED
    }

    data class RawStrokeEntry(
        val index: Int,
        val points: List<StrokePoint>,
        val timestampMs: Long
    )

    data class ProcessingEvent(
        val strokeIndex: Int,
        val type: EventType,
        val detail: String,
        val timestampMs: Long,
        /** Elapsed processing time in ms for this event, or -1 if not measured. */
        val elapsedMs: Long = -1
    )

    data class Snapshot(
        val strokes: List<RawStrokeEntry>,
        val events: List<ProcessingEvent>
    )

    private val strokes = ArrayDeque<RawStrokeEntry>()
    private val events = ArrayDeque<ProcessingEvent>()
    private var nextIndex = 0
    /** Minimum valid stroke index — events below this are stale and filtered on snapshot. */
    private var minValidIndex = 0

    /**
     * Record a raw stroke before any processing.
     * The stroke is downsampled to reduce memory usage.
     * @return the stroke index for use in [recordEvent]
     */
    fun recordStroke(points: List<StrokePoint>): Int {
        val index = nextIndex++

        // Downsample to reduce memory: collapse dwells + RDP simplification
        val tempStroke = InkStroke(points = points)
        val downsampled = StrokeDownsampler.downsample(tempStroke)

        val entry = RawStrokeEntry(
            index = index,
            points = downsampled.points,
            timestampMs = System.currentTimeMillis()
        )

        strokes.addLast(entry)

        // Evict oldest if over capacity — O(1) per eviction
        while (strokes.size > maxStrokes) {
            val evicted = strokes.removeFirst()
            minValidIndex = evicted.index + 1
        }

        return index
    }

    /**
     * Record a processing decision for a stroke.
     * @param strokeIndex the index returned by [recordStroke], or -1 for events
     *                    not tied to a specific stroke (e.g. recognition)
     */
    fun recordEvent(strokeIndex: Int, type: EventType, detail: String = "", elapsedMs: Long = -1) {
        events.addLast(ProcessingEvent(
            strokeIndex = strokeIndex,
            type = type,
            detail = detail,
            timestampMs = System.currentTimeMillis(),
            elapsedMs = elapsedMs
        ))

        // Cap events at 3x stroke capacity
        while (events.size > maxStrokes * 3) {
            events.removeFirst()
        }
    }

    /** Freeze the current ring buffer state into a serializable snapshot. */
    fun snapshot(): Snapshot {
        // Lazily filter stale events (for evicted strokes) at snapshot time
        // instead of O(n) removal on every eviction.
        val validEvents = events.filter {
            it.strokeIndex < 0 || it.strokeIndex >= minValidIndex
        }
        return Snapshot(
            strokes = strokes.toList(),
            events = validEvents
        )
    }

    /** Number of strokes currently in the buffer. */
    val strokeCount: Int get() = strokes.size

    /** Number of events currently in the buffer. */
    val eventCount: Int get() = events.size

    /** Clear all strokes and events. */
    fun clear() {
        strokes.clear()
        events.clear()
        nextIndex = 0
        minValidIndex = 0
    }
}
