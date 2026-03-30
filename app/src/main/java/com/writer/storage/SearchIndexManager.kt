package com.writer.storage

import android.content.Context
import android.util.Log
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.concurrent.futures.await
import com.writer.model.DocumentData
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Manages the AppSearch LocalStorage index for document search.
 *
 * Replaces the old JSON-based search-index.json with a proper indexed
 * search engine backed by AppSearch's IcingSearchEngine.
 *
 * All AppSearch operations run on a dedicated single-thread dispatcher
 * to avoid IcingSearchEngine native contention with the main thread.
 */
object SearchIndexManager {

    private const val TAG = "SearchIndexManager"
    private const val DATABASE_NAME = "inkup-notes"

    /** Dedicated thread for all AppSearch operations — avoids native contention with main thread. */
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "appsearch-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var session: AppSearchSession? = null
    private val sessionMutex = Mutex()

    private suspend fun getSession(context: Context): AppSearchSession {
        sessionMutex.withLock {
            session?.let { return it }
            val s = withContext(dispatcher) {
                LocalStorage.createSearchSessionAsync(
                    LocalStorage.SearchContext.Builder(context.applicationContext, DATABASE_NAME)
                        .build()
                ).await()
            }
            val setSchemaRequest = SetSchemaRequest.Builder()
                .addDocumentClasses(NoteDocument::class.java)
                .build()
            withContext(dispatcher) {
                s.setSchemaAsync(setSchemaRequest).await()
            }
            session = s
            return s
        }
    }

    /** Index a document for search. Called after every save. */
    suspend fun indexDocument(context: Context, name: String, data: DocumentData) {
        try {
            val lineDataJson = buildLineDataJson(data)
            val bodyText = buildBodyText(data)
            val doc = NoteDocument(
                id = name,
                title = name,
                body = bodyText,
                lastModified = System.currentTimeMillis(),
                lineData = lineDataJson.toString()
            )
            val s = getSession(context)
            withContext(dispatcher) {
                s.putAsync(
                    PutDocumentsRequest.Builder().addDocuments(doc).build()
                ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to index document $name", e)
        }
    }

    /** Remove a document from the search index. */
    suspend fun removeDocument(context: Context, name: String) {
        try {
            val s = getSession(context)
            withContext(dispatcher) {
                s.removeAsync(
                    RemoveByDocumentIdRequest.Builder(NoteDocument.NAMESPACE)
                        .addIds(name)
                        .build()
                ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove document $name from index", e)
        }
    }

    /** Rename a document in the index (remove old, put new with same content). */
    suspend fun renameDocument(context: Context, oldName: String, newName: String) {
        try {
            val s = getSession(context)
            withContext(dispatcher) {
                // Read old document's data before removing
                val oldResults = searchById(s, oldName)
                val oldDoc = oldResults.firstOrNull()

                // Remove old entry
                s.removeAsync(
                    RemoveByDocumentIdRequest.Builder(NoteDocument.NAMESPACE)
                        .addIds(oldName)
                        .build()
                ).await()

                // Re-index under new name, preserving content
                if (oldDoc != null) {
                    val newDoc = NoteDocument(
                        id = newName,
                        title = newName,
                        body = oldDoc.body,
                        lastModified = System.currentTimeMillis(),
                        lineData = oldDoc.lineData
                    )
                    s.putAsync(
                        PutDocumentsRequest.Builder().addDocuments(newDoc).build()
                    ).await()
                }
                s.requestFlushAsync().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rename document $oldName → $newName in index", e)
        }
    }

    /**
     * Search for documents matching the query.
     * Returns doc name → list of matching lines (with line index and text).
     */
    suspend fun search(context: Context, query: String): Map<String, List<DocumentStorage.SearchMatch>> {
        if (query.isBlank()) return emptyMap()
        try {
            val s = getSession(context)
            return withContext(dispatcher) {
                val searchSpec = SearchSpec.Builder()
                    .setRankingStrategy(SearchSpec.RANKING_STRATEGY_DOCUMENT_SCORE)
                    .build()
                val searchResults = s.search(query, searchSpec)
                val result = mutableMapOf<String, List<DocumentStorage.SearchMatch>>()

                var page = searchResults.nextPageAsync.await()
                while (page.isNotEmpty()) {
                    for (searchResult in page) {
                        val doc = searchResult.genericDocument
                            .toDocumentClass(NoteDocument::class.java)
                        val lineMatches = findLineMatches(doc.lineData, query)
                        result[doc.id] = lineMatches
                    }
                    page = searchResults.nextPageAsync.await()
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for query: $query", e)
            return emptyMap()
        }
    }

    /** Rebuild the entire index from document files on disk. */
    suspend fun rebuildIndex(context: Context) {
        try {
            val s = getSession(context)
            withContext(dispatcher) {
                // Clear existing index
                s.removeAsync(
                    "",  // empty query removes all
                    SearchSpec.Builder().build()
                ).await()
            }

            // Re-index all documents from disk
            val docsDir = context.filesDir.resolve("documents")
            if (!docsDir.exists()) return

            val files = docsDir.listFiles() ?: return
            val seen = mutableSetOf<String>()

            // Index .inkup files first (preferred format)
            for (file in files.filter { it.extension == "inkup" }) {
                val name = file.nameWithoutExtension
                seen.add(name)
                val data = DocumentStorage.load(context, name) ?: continue
                indexDocument(context, name, data)
            }

            // Index legacy .json files not already seen
            for (file in files.filter { it.extension == "json" && it.name != "search-index.json" }) {
                val name = file.nameWithoutExtension
                if (name in seen) continue
                val data = DocumentStorage.load(context, name) ?: continue
                indexDocument(context, name, data)
            }

            withContext(dispatcher) {
                s.requestFlushAsync().await()
            }
            Log.i(TAG, "Rebuilt search index: ${seen.size} documents")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rebuild search index", e)
        }
    }

    /** Close the AppSearch session. */
    fun close() {
        runBlocking {
            sessionMutex.withLock {
                session?.close()
                session = null
            }
        }
    }

    // ── Helpers (package-visible for testing) ─────────────────────────────

    /** Build the full-text body from all line text (lowercased, newline-separated). */
    fun buildBodyText(data: DocumentData): String {
        val lines = mutableListOf<String>()
        for ((_, text) in data.main.lineTextCache) {
            if (text.isNotEmpty()) lines.add(text.lowercase())
        }
        for ((_, text) in data.cue.lineTextCache) {
            if (text.isNotEmpty()) lines.add(text.lowercase())
        }
        return lines.joinToString("\n")
    }

    /** Build the per-line JSON map for post-query line matching. */
    fun buildLineDataJson(data: DocumentData): JSONObject {
        val json = JSONObject()
        for ((lineIdx, text) in data.main.lineTextCache) {
            if (text.isNotEmpty()) json.put(lineIdx.toString(), text.lowercase())
        }
        for ((lineIdx, text) in data.cue.lineTextCache) {
            if (text.isNotEmpty()) json.put("c$lineIdx", text.lowercase())
        }
        return json
    }

    /** Find which lines in the lineData JSON match the query (substring match). */
    fun findLineMatches(lineDataJson: String, query: String): List<DocumentStorage.SearchMatch> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return try {
            val json = JSONObject(lineDataJson)
            val matches = mutableListOf<DocumentStorage.SearchMatch>()
            for (key in json.keys()) {
                val text = json.getString(key)
                if (text.lowercase().contains(lowerQuery)) {
                    val lineIndex = key.removePrefix("c").toIntOrNull() ?: continue
                    matches.add(DocumentStorage.SearchMatch(lineIndex, text))
                }
            }
            matches.sortedBy { it.lineIndex }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Look up a single document by ID. */
    private suspend fun searchById(session: AppSearchSession, id: String): List<NoteDocument> {
        return try {
            val spec = SearchSpec.Builder()
                .addFilterNamespaces(NoteDocument.NAMESPACE)
                .build()
            val results = session.getByDocumentIdAsync(
                androidx.appsearch.app.GetByDocumentIdRequest.Builder(NoteDocument.NAMESPACE)
                    .addIds(id)
                    .build()
            ).await()
            val doc = results.successes[id] ?: return emptyList()
            listOf(doc.toDocumentClass(NoteDocument::class.java))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
