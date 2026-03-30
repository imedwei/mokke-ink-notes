package com.writer.storage

import android.content.Context
import android.net.Uri
import com.writer.model.DocumentData
import com.writer.ui.writing.AutoSaver

/** Bridges [AutoSaver.Sink] to [DocumentStorage] for production use. */
class DocumentStorageSink(private val context: Context) : AutoSaver.Sink {

    override fun save(name: String, state: DocumentData): Boolean {
        return DocumentStorage.save(context, name, state)
    }

    override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
        DocumentStorage.exportToSyncFolder(context, name, state, markdown, syncUri)
    }
}
