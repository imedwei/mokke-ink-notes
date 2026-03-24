package com.writer.ui.writing

import kotlin.math.abs

/**
 * Controls when undo snapshots are created, coalescing rapid writing strokes
 * into a single undo step while ensuring destructive/structural actions always
 * get their own snapshot.
 *
 * Core principle: **one undo step = one user intent**.
 *
 * See docs/undo-redo-design.md for the full design rationale.
 */
class UndoCoalescer(
    private val undoManager: UndoManager,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS
) {
    companion object {
        const val DEFAULT_COALESCE_WINDOW_MS = 2000L
    }

    enum class ActionType {
        /** Normal writing stroke added. Coalesces with adjacent rapid strokes. */
        STROKE_ADDED,
        /** Scratch-out erase. Always creates snapshot (destructive). */
        SCRATCH_OUT,
        /** Shape snap replacement. Always creates snapshot. */
        STROKE_REPLACED,
        /** Gesture mutation (strikethrough, scribble-delete). Always creates snapshot (destructive). */
        GESTURE_CONSUMED,
        /** Diagram area created. Always creates snapshot (structural). */
        DIAGRAM_CREATED,
        /** Sticky zone expanded. Always creates snapshot (structural). */
        ZONE_EXPANDED,
        /** Space inserted or removed. Always creates snapshot (structural). */
        SPACE_INSERTED
    }

    private var lastActionType: ActionType? = null
    private var lastSnapshotTimeMs: Long = 0L
    private var lastLineIndex: Int = -1

    // For testing: allow advancing the clock
    private var testTimeOffsetMs: Long = 0L

    private fun now(): Long = System.currentTimeMillis() + testTimeOffsetMs

    /** Advance the internal clock for testing purposes. */
    internal fun advanceTimeForTesting(ms: Long) {
        testTimeOffsetMs += ms
    }

    /**
     * Conditionally save an undo snapshot based on action type and coalescing rules.
     *
     * @param action the type of mutation about to happen
     * @param lineIndex the line index of the stroke (used for spatial coalescing)
     * @param snapshot the current document state to save if a snapshot is warranted
     */
    fun maybeSave(action: ActionType, lineIndex: Int, snapshot: UndoManager.Snapshot) {
        if (shouldCreateSnapshot(action, lineIndex)) {
            undoManager.saveSnapshot(snapshot)
            lastSnapshotTimeMs = now()
        }
        lastActionType = action
        lastLineIndex = lineIndex
    }

    private fun shouldCreateSnapshot(action: ActionType, lineIndex: Int): Boolean {
        // Non-writing actions always create a snapshot
        if (action != ActionType.STROKE_ADDED) return true
        // First action or action type changed
        if (lastActionType != ActionType.STROKE_ADDED) return true
        // Time gap exceeds coalesce window
        if (now() - lastSnapshotTimeMs > coalesceWindowMs) return true
        // Jumped to a distant line (more than 1 line away)
        if (abs(lineIndex - lastLineIndex) > 1) return true
        // Coalesce with previous
        return false
    }

    /** Reset coalescer state (e.g., on document switch). */
    fun reset() {
        lastActionType = null
        lastSnapshotTimeMs = 0L
        lastLineIndex = -1
        testTimeOffsetMs = 0L
    }
}
