package com.writer.storage

import android.net.Uri
import com.writer.model.DocumentData
import com.writer.ui.writing.AutoSaver

/**
 * [AutoSaver.Sink] backed by a live Automerge document with incremental saves.
 *
 * [save] diffs the incoming [DocumentData] against the live document and writes
 * only the delta — typically a few hundred bytes for one new stroke.
 *
 * [export] delegates to [exportSink] which writes the .mok ZIP for SAF interchange.
 */
class AutomergeSink(
    private val storage: AutomergeStorage,
    private val exportSink: AutoSaver.Sink,
) : AutoSaver.Sink {

    private val syncs = mutableMapOf<String, AutomergeSync>()

    override fun save(name: String, state: DocumentData): Boolean {
        return try {
            val sync = syncs.getOrPut(name) {
                AutomergeSync().also { s ->
                    // Load existing document if available
                    val existing = storage.load(name)
                    if (existing != null) s.load(existing)
                }
            }
            sync.sync(state)
            storage.saveIncremental(name, sync.document)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
        // Full save before export for durability
        val sync = syncs[name]
        if (sync != null) {
            storage.save(name, sync.document)
        }
        exportSink.export(name, state, markdown, syncUri)
    }
}
