package com.writer.ui.writing

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.writer.model.DocumentData
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
        pendingSnapshot = snapshotProvider
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, delayMs)
    }

    /** Save asynchronously right now — cancels any pending debounce and in-flight save. */
    fun saveAsync(snapshot: Snapshot) {
        handler.removeCallbacks(debounceRunnable)
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            performSave(snapshot)
        }
    }

    /** Save synchronously on [Dispatchers.IO], blocking the caller until complete. */
    fun saveBlocking(snapshot: Snapshot) {
        handler.removeCallbacks(debounceRunnable)
        saveJob?.cancel()
        saveJob = null
        runBlocking(Dispatchers.IO) {
            performSave(snapshot)
        }
    }

    fun cancel() {
        handler.removeCallbacks(debounceRunnable)
        saveJob?.cancel()
        saveJob = null
        pendingSnapshot = null
    }

    private fun performSave(snapshot: Snapshot): Boolean {
        val saved = sink.save(snapshot.name, snapshot.state)
        if (saved && snapshot.syncUri != null && snapshot.markdown != null) {
            sink.export(snapshot.name, snapshot.state, snapshot.markdown, snapshot.syncUri)
        }
        return saved
    }
}
