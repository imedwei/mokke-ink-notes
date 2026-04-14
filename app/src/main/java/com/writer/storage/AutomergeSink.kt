package com.writer.storage

import android.net.Uri
import com.writer.model.DocumentData
import com.writer.ui.writing.AutoSaver

/**
 * [AutoSaver.Sink] backed by a live Automerge document with incremental saves.
 *
 * [save] diffs the incoming [DocumentData] against the live document and writes
 * only the delta. Checkpointing is handled externally (idle-timeout in WritingActivity).
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
        val sync = syncs[name]
        if (sync != null) {
            storage.save(name, sync.document)
        }
        exportSink.export(name, state, markdown, syncUri)
    }

    /** Get the live document for external checkpointing. */
    fun getDocument(name: String): org.automerge.Document? = syncs[name]?.document
}
