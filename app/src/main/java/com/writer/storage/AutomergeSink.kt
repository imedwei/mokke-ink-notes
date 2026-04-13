package com.writer.storage

import android.net.Uri
import com.writer.model.DocumentData
import com.writer.ui.writing.AutoSaver
import org.automerge.Document

/**
 * [AutoSaver.Sink] backed by Automerge persistence.
 *
 * Each [save] converts the full [DocumentData] snapshot to an Automerge document
 * and persists it. Full-document saves are used because the auto-save path receives
 * complete snapshots rather than incremental edits.
 *
 * [export] does a full save (for durability) before SAF would export.
 */
class AutomergeSink(private val storage: AutomergeStorage) : AutoSaver.Sink {

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
        val doc = AutomergeAdapter.toAutomerge(state)
        storage.save(name, doc)
        doc.free()
    }
}
