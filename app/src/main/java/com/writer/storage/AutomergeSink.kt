package com.writer.storage

import android.net.Uri
import com.writer.model.DocumentData
import com.writer.ui.writing.AutoSaver

/**
 * [AutoSaver.Sink] backed by Automerge persistence.
 *
 * [save] converts the full [DocumentData] snapshot to an Automerge document
 * and persists it as a raw binary — no ZIP, no audio re-read.
 *
 * [export] delegates to [exportSink] which writes the .mok ZIP for SAF interchange.
 */
class AutomergeSink(
    private val storage: AutomergeStorage,
    private val exportSink: AutoSaver.Sink,
) : AutoSaver.Sink {

    override fun save(name: String, state: DocumentData): Boolean {
        return try {
            val doc = AutomergeAdapter.toAutomerge(state)
            storage.save(name, doc)
            doc.free()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
        exportSink.export(name, state, markdown, syncUri)
    }
}
