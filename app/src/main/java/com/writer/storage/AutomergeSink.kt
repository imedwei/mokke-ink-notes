package com.writer.storage

import android.net.Uri
import com.writer.model.DocumentData
import com.writer.ui.writing.AutoSaver

/**
 * [AutoSaver.Sink] backed by a live Automerge document with incremental saves.
 *
 * [save] diffs the incoming [DocumentData] against the live document and writes
 * only the delta. Creates a checkpoint after each save that changes the document.
 *
 * [export] delegates to [exportSink] which writes the .mok ZIP for SAF interchange.
 */
class AutomergeSink(
    private val storage: AutomergeStorage,
    private val exportSink: AutoSaver.Sink,
    private val versionHistory: VersionHistory,
) : AutoSaver.Sink {

    private val syncs = mutableMapOf<String, AutomergeSync>()

    override fun save(name: String, state: DocumentData): Boolean {
        return try {
            val sync = syncs.getOrPut(name) {
                AutomergeSync().also { s ->
                    val existing = storage.load(name)
                    if (existing != null) s.load(existing)
                }
            }
            val headsBefore = sync.document.heads
            sync.sync(state)
            storage.saveIncremental(name, sync.document)

            // Checkpoint if document actually changed
            val headsAfter = sync.document.heads
            if (!headsBefore.contentEquals(headsAfter)) {
                val label = java.text.SimpleDateFormat(
                    "h:mm:ss a", java.util.Locale.getDefault()
                ).format(java.util.Date())
                versionHistory.createCheckpoint(name, sync.document, label)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
        val sync = syncs[name]
        if (sync != null) {
            storage.save(name, sync.document)
        }
        exportSink.export(name, state, markdown, syncUri)
    }
}
