package com.writer.storage

import org.automerge.ChangeHash
import org.automerge.Document
import java.io.File

/**
 * Persistence layer for Automerge documents. Files use `.amok` extension
 * (Automerge MOKke) — raw Automerge binary, not ZIP.
 *
 * [saveIncremental] appends only changes since last full save to a `.amok.inc` file.
 * [load] reads the base `.amok` and applies any `.amok.inc` changes.
 */
class AutomergeStorage(private val docsDir: File) {

    companion object {
        private const val EXT = "amok"
        private const val INC_EXT = "amok.inc"
    }

    /** Heads recorded at the last [save] — used by [saveIncremental] to compute deltas. */
    private val lastSavedHeads = mutableMapOf<String, Array<ChangeHash>>()

    fun save(name: String, doc: Document) {
        docsDir.mkdirs()
        File(docsDir, "$name.$EXT").writeBytes(doc.save())
        File(docsDir, "$name.$INC_EXT").delete()
        lastSavedHeads[name] = doc.heads
    }

    fun load(name: String): Document? {
        val baseFile = File(docsDir, "$name.$EXT")
        if (!baseFile.exists()) return null
        val doc = Document.load(baseFile.readBytes())
        val incFile = File(docsDir, "$name.$INC_EXT")
        if (incFile.exists()) {
            doc.applyEncodedChanges(incFile.readBytes())
        }
        lastSavedHeads[name] = doc.heads
        return doc
    }

    fun saveIncremental(name: String, doc: Document) {
        val heads = lastSavedHeads[name] ?: run {
            // No prior heads — fall back to full save
            save(name, doc)
            return
        }
        val changes = doc.encodeChangesSince(heads)
        if (changes.isNotEmpty()) {
            docsDir.mkdirs()
            File(docsDir, "$name.$INC_EXT").appendBytes(changes)
        }
        lastSavedHeads[name] = doc.heads
    }

    fun exists(name: String): Boolean =
        File(docsDir, "$name.$EXT").exists()

    fun delete(name: String) {
        File(docsDir, "$name.$EXT").delete()
        File(docsDir, "$name.$INC_EXT").delete()
        lastSavedHeads.remove(name)
    }

    fun list(): List<String> {
        if (!docsDir.isDirectory) return emptyList()
        return docsDir.listFiles { f -> f.extension == EXT }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
