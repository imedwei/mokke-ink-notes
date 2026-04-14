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
 *
 * Only one document's sync is kept alive at a time. Switching to a different
 * document frees the previous one to avoid JNI memory leaks.
 */
class AutomergeSink(
    private val storage: AutomergeStorage,
    private val exportSink: AutoSaver.Sink,
) : AutoSaver.Sink {

    private var currentName: String? = null
    private var currentSync: AutomergeSync? = null

    override fun save(name: String, state: DocumentData): Boolean {
        return try {
            val sync = getOrCreateSync(name)
            sync.sync(state)
            storage.saveIncremental(name, sync.document)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
        if (name == currentName && currentSync != null) {
            storage.save(name, currentSync!!.document)
        }
        exportSink.export(name, state, markdown, syncUri)
    }

    /** Get the live document for external checkpointing. */
    fun getDocument(name: String): org.automerge.Document? =
        if (name == currentName) currentSync?.document else null

    private fun getOrCreateSync(name: String): AutomergeSync {
        if (name == currentName && currentSync != null) return currentSync!!

        // Free previous document's native resources
        currentSync?.free()

        val sync = AutomergeSync()
        val existing = storage.load(name)
        if (existing != null) sync.load(existing)

        currentName = name
        currentSync = sync
        return sync
    }
}
