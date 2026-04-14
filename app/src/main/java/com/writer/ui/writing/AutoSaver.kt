package com.writer.ui.writing

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.writer.model.DocumentData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Debounced, async document saver. Snapshots are created on the main thread
 * by the caller; all I/O runs on [Dispatchers.IO].
 */
class AutoSaver(
    private val scope: CoroutineScope,
    private val sink: Sink,
    private val delayMs: Long = 5000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Performs the actual I/O — extracted so tests can substitute a fake. */
    interface Sink {
        fun save(name: String, state: DocumentData): Boolean
        fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri)
    }

    data class Snapshot(
        val name: String,
        val state: DocumentData,
        val markdown: String? = null,
        val syncUri: Uri? = null,
    )

    /** True when content has changed since last SAF export. */
    var syncDirty: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var saveJob: Job? = null
    private var pendingSnapshot: (() -> Snapshot?)? = null

    private val debounceRunnable = Runnable {
        val snapshot = pendingSnapshot?.invoke() ?: return@Runnable
        saveAsync(snapshot)
    }

    /**
     * Schedule a save after [delayMs]. [snapshotProvider] is called on the main
     * thread when the timer fires, so it can safely read coordinator state.
     * Resets the timer if already pending.
     */
    fun schedule(snapshotProvider: () -> Snapshot?) {
        syncDirty = true
        pendingSnapshot = snapshotProvider
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, delayMs)
    }

    /** Save asynchronously right now — cancels any pending debounce and in-flight save. */
    fun saveAsync(snapshot: Snapshot) {
        syncDirty = true
        handler.removeCallbacks(debounceRunnable)
        saveJob?.cancel()
        saveJob = scope.launch(ioDispatcher) {
            performSave(snapshot)
        }
    }

    /** Save synchronously on [Dispatchers.IO], blocking the caller until complete. */
    fun saveBlocking(snapshot: Snapshot) {
        handler.removeCallbacks(debounceRunnable)
        saveJob?.cancel()
        saveJob = null
        runBlocking(ioDispatcher) {
            performSave(snapshot)
        }
    }

    fun cancel() {
        handler.removeCallbacks(debounceRunnable)
        saveJob?.cancel()
        saveJob = null
        pendingSnapshot = null
    }

    /**
     * Export to SAF if content has changed since last export.
     * Called on document close / app background — not during auto-save.
     */
    fun exportIfDirty(snapshotProvider: () -> Snapshot?) {
        if (!syncDirty) return
        val snapshot = snapshotProvider() ?: return
        if (snapshot.syncUri != null && snapshot.markdown != null) {
            sink.export(snapshot.name, snapshot.state, snapshot.markdown, snapshot.syncUri)
        }
        syncDirty = false
    }

    /** Local save only — no SAF export. */
    private fun performSave(snapshot: Snapshot): Boolean {
        return sink.save(snapshot.name, snapshot.state)
    }
}
