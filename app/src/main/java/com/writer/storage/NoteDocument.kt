package com.writer.storage

import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema

/**
 * AppSearch document representing a handwritten note.
 *
 * - [title] and [body] are indexed for full-text prefix search.
 * - [lineData] stores the per-line JSON map (not indexed) for post-query
 *   line-level matching and scroll-to-line support.
 */
@Document
data class NoteDocument(
    @Document.Namespace
    val namespace: String = NAMESPACE,

    @Document.Id
    val id: String,

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES
    )
    val title: String,

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES
    )
    val body: String,

    @Document.LongProperty
    val lastModified: Long = System.currentTimeMillis(),

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE
    )
    val lineData: String = "{}"
) {
    companion object {
        const val NAMESPACE = "notes"
    }
}
