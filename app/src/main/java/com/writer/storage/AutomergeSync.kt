package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.TextBlock
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Transaction

/**
 * Maintains a live Automerge [Document] and syncs [DocumentData] snapshots
 * into it with minimal deltas. Diffs by unique ID (strokeId, textBlock.id,
 * diagramArea.id) and applies only additions/removals as transactions.
 *
 * Strokes and diagram areas are write-once — only add/remove is tracked.
 * Text blocks may be updated (text, words change from recognition).
 */
class AutomergeSync {

    var document: Document = Document()
        private set

    private var lastState: DocumentData? = null
    private var initialized = false

    /** Load an existing document (e.g., from disk) as the live document. */
    fun load(doc: Document) {
        if (initialized) document.free()
        document = doc
        lastState = AutomergeAdapter.fromAutomerge(doc)
        initialized = true
    }

    /**
     * Sync [newState] into the live document. On first call, writes the full
     * document. On subsequent calls, applies only the diff.
     */
    fun sync(newState: DocumentData) {
        val prev = lastState
        if (!initialized || prev == null) {
            if (initialized) document.free()
            document = AutomergeAdapter.toAutomerge(newState)
            lastState = newState
            initialized = true
            return
        }

        // Diff and apply
        val tx = document.startTransaction()
        val mainId = getMapId(document, ObjectId.ROOT, "main")
        val cueId = getMapId(document, ObjectId.ROOT, "cue")

        if (mainId != null) syncColumn(tx, document, mainId, prev.main, newState.main)
        if (cueId != null) syncColumn(tx, document, cueId, prev.cue, newState.cue)
        syncAudioRecordings(tx, document, prev.audioRecordings, newState.audioRecordings)

        if (tx.commit().isPresent) {
            lastState = newState
        } else {
            // No changes — update lastState anyway to avoid re-diffing
            lastState = newState
        }
    }

    fun free() {
        if (initialized) {
            document.free()
            initialized = false
            lastState = null
        }
    }

    // --- Column sync ---

    private fun syncColumn(
        tx: Transaction, doc: Document, columnId: ObjectId,
        prev: ColumnData, next: ColumnData
    ) {
        val strokesId = getListId(doc, columnId, "strokes")
        if (strokesId != null) syncStrokes(tx, doc, strokesId, prev.strokes, next.strokes)

        val textBlocksId = getListId(doc, columnId, "textBlocks")
        if (textBlocksId != null) syncTextBlocks(tx, doc, textBlocksId, prev.textBlocks, next.textBlocks)

        val diagramsId = getListId(doc, columnId, "diagramAreas")
        if (diagramsId != null) syncDiagramAreas(tx, doc, diagramsId, prev.diagramAreas, next.diagramAreas)
    }

    // --- Stroke sync (write-once: add/remove only) ---

    private fun syncStrokes(
        tx: Transaction, doc: Document, listId: ObjectId,
        prev: List<InkStroke>, next: List<InkStroke>
    ) {
        val prevIds = prev.map { it.strokeId }
        val nextIds = next.map { it.strokeId }
        if (prevIds == nextIds) return

        val prevSet = prevIds.toSet()
        val nextSet = nextIds.toSet()
        val removed = prevSet - nextSet
        val added = nextSet - prevSet

        // Delete removed strokes (iterate backwards to preserve indices)
        if (removed.isNotEmpty()) {
            val currentIds = readStrokeIds(doc, listId)
            for (i in currentIds.indices.reversed()) {
                if (currentIds[i] in removed) {
                    tx.delete(listId, i.toLong())
                }
            }
        }

        // Append added strokes
        if (added.isNotEmpty()) {
            val nextById = next.associateBy { it.strokeId }
            var insertIdx = doc.length(listId)
            // Delete changes the length, so re-read
            if (removed.isNotEmpty()) insertIdx = doc.length(listId)
            for (id in nextIds) {
                if (id in added) {
                    val stroke = nextById[id]!!
                    writeStroke(tx, listId, insertIdx, stroke)
                    insertIdx++
                }
            }
        }
    }

    private fun readStrokeIds(doc: Document, listId: ObjectId): List<String> {
        return doc.listItems(listId).orElse(emptyArray()).mapNotNull { item ->
            val mapId = (item as? AmValue.Map)?.id ?: return@mapNotNull null
            doc.get(mapId, "strokeId").orElse(null)?.let { (it as? AmValue.Str)?.value }
        }
    }

    private fun writeStroke(tx: Transaction, listId: ObjectId, index: Long, stroke: InkStroke) {
        val sId = tx.insert(listId, index, ObjectType.MAP)
        tx.set(sId, "strokeId", stroke.strokeId)
        tx.set(sId, "strokeWidth", stroke.strokeWidth.toDouble())
        tx.set(sId, "strokeType", stroke.strokeType.name)
        tx.set(sId, "isGeometric", stroke.isGeometric)
        val pointsId = tx.set(sId, "points", ObjectType.LIST)
        for ((j, pt) in stroke.points.withIndex()) {
            val ptId = tx.insert(pointsId, j.toLong(), ObjectType.MAP)
            tx.set(ptId, "x", pt.x.toDouble())
            tx.set(ptId, "y", pt.y.toDouble())
            tx.set(ptId, "pressure", pt.pressure.toDouble())
            tx.set(ptId, "timestamp", pt.timestamp.toDouble())
        }
    }

    // --- TextBlock sync (add/remove/update) ---

    private fun syncTextBlocks(
        tx: Transaction, doc: Document, listId: ObjectId,
        prev: List<TextBlock>, next: List<TextBlock>
    ) {
        val prevById = prev.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        val prevSet = prevById.keys
        val nextSet = nextById.keys

        if (prev == next) return

        val removed = prevSet - nextSet
        val added = nextSet - prevSet
        val kept = prevSet.intersect(nextSet)

        // Delete removed (backwards)
        if (removed.isNotEmpty()) {
            val currentIds = readTextBlockIds(doc, listId)
            for (i in currentIds.indices.reversed()) {
                if (currentIds[i] in removed) {
                    tx.delete(listId, i.toLong())
                }
            }
        }

        // Update changed
        if (kept.isNotEmpty()) {
            val currentEntries = readTextBlockEntries(doc, listId)
            for ((mapId, tbId) in currentEntries) {
                if (tbId in kept && prevById[tbId] != nextById[tbId]) {
                    val tb = nextById[tbId]!!
                    tx.set(mapId, "startLineIndex", tb.startLineIndex)
                    tx.set(mapId, "heightInLines", tb.heightInLines)
                    tx.set(mapId, "text", tb.text)
                    tx.set(mapId, "audioFile", tb.audioFile)
                    tx.set(mapId, "audioStartMs", tb.audioStartMs.toDouble())
                    tx.set(mapId, "audioEndMs", tb.audioEndMs.toDouble())
                    // Rewrite words list
                    val wordsId = tx.set(mapId, "words", ObjectType.LIST)
                    for ((j, w) in tb.words.withIndex()) {
                        val wId = tx.insert(wordsId, j.toLong(), ObjectType.MAP)
                        tx.set(wId, "text", w.text)
                        tx.set(wId, "confidence", w.confidence.toDouble())
                        tx.set(wId, "startMs", w.startMs.toDouble())
                        tx.set(wId, "endMs", w.endMs.toDouble())
                    }
                }
            }
        }

        // Append added
        if (added.isNotEmpty()) {
            var insertIdx = doc.length(listId)
            if (removed.isNotEmpty()) insertIdx = doc.length(listId)
            for (id in next.map { it.id }) {
                if (id in added) {
                    val tb = nextById[id]!!
                    writeTextBlock(tx, listId, insertIdx, tb)
                    insertIdx++
                }
            }
        }
    }

    private fun readTextBlockIds(doc: Document, listId: ObjectId): List<String> {
        return doc.listItems(listId).orElse(emptyArray()).mapNotNull { item ->
            val mapId = (item as? AmValue.Map)?.id ?: return@mapNotNull null
            doc.get(mapId, "id").orElse(null)?.let { (it as? AmValue.Str)?.value }
        }
    }

    private fun readTextBlockEntries(doc: Document, listId: ObjectId): List<Pair<ObjectId, String>> {
        return doc.listItems(listId).orElse(emptyArray()).mapNotNull { item ->
            val mapId = (item as? AmValue.Map)?.id ?: return@mapNotNull null
            val id = doc.get(mapId, "id").orElse(null)?.let { (it as? AmValue.Str)?.value }
                ?: return@mapNotNull null
            mapId to id
        }
    }

    private fun writeTextBlock(tx: Transaction, listId: ObjectId, index: Long, tb: TextBlock) {
        val tbId = tx.insert(listId, index, ObjectType.MAP)
        tx.set(tbId, "id", tb.id)
        tx.set(tbId, "startLineIndex", tb.startLineIndex)
        tx.set(tbId, "heightInLines", tb.heightInLines)
        tx.set(tbId, "text", tb.text)
        tx.set(tbId, "audioFile", tb.audioFile)
        tx.set(tbId, "audioStartMs", tb.audioStartMs.toDouble())
        tx.set(tbId, "audioEndMs", tb.audioEndMs.toDouble())
        val wordsId = tx.set(tbId, "words", ObjectType.LIST)
        for ((j, w) in tb.words.withIndex()) {
            val wId = tx.insert(wordsId, j.toLong(), ObjectType.MAP)
            tx.set(wId, "text", w.text)
            tx.set(wId, "confidence", w.confidence.toDouble())
            tx.set(wId, "startMs", w.startMs.toDouble())
            tx.set(wId, "endMs", w.endMs.toDouble())
        }
    }

    // --- DiagramArea sync (add/remove only) ---

    private fun syncDiagramAreas(
        tx: Transaction, doc: Document, listId: ObjectId,
        prev: List<DiagramArea>, next: List<DiagramArea>
    ) {
        if (prev == next) return

        val prevSet = prev.map { it.id }.toSet()
        val nextSet = next.map { it.id }.toSet()
        val removed = prevSet - nextSet
        val added = nextSet - prevSet

        if (removed.isNotEmpty()) {
            val currentIds = doc.listItems(listId).orElse(emptyArray()).mapNotNull { item ->
                val mapId = (item as? AmValue.Map)?.id ?: return@mapNotNull null
                doc.get(mapId, "id").orElse(null)?.let { (it as? AmValue.Str)?.value }
            }
            for (i in currentIds.indices.reversed()) {
                if (currentIds[i] in removed) tx.delete(listId, i.toLong())
            }
        }

        if (added.isNotEmpty()) {
            val nextById = next.associateBy { it.id }
            var insertIdx = doc.length(listId)
            if (removed.isNotEmpty()) insertIdx = doc.length(listId)
            for (id in next.map { it.id }) {
                if (id in added) {
                    val da = nextById[id]!!
                    val daId = tx.insert(listId, insertIdx, ObjectType.MAP)
                    tx.set(daId, "id", da.id)
                    tx.set(daId, "startLineIndex", da.startLineIndex)
                    tx.set(daId, "heightInLines", da.heightInLines)
                    insertIdx++
                }
            }
        }
    }

    // --- AudioRecording sync ---

    private fun syncAudioRecordings(
        tx: Transaction, doc: Document,
        prev: List<AudioRecording>, next: List<AudioRecording>
    ) {
        if (prev == next) return
        val listId = getListId(doc, ObjectId.ROOT, "audioRecordings") ?: return

        val prevSet = prev.map { it.audioFile }.toSet()
        val nextSet = next.map { it.audioFile }.toSet()
        val removed = prevSet - nextSet
        val added = nextSet - prevSet

        if (removed.isNotEmpty()) {
            val currentFiles = doc.listItems(listId).orElse(emptyArray()).mapNotNull { item ->
                val mapId = (item as? AmValue.Map)?.id ?: return@mapNotNull null
                doc.get(mapId, "audioFile").orElse(null)?.let { (it as? AmValue.Str)?.value }
            }
            for (i in currentFiles.indices.reversed()) {
                if (currentFiles[i] in removed) tx.delete(listId, i.toLong())
            }
        }

        if (added.isNotEmpty()) {
            val nextByFile = next.associateBy { it.audioFile }
            var insertIdx = doc.length(listId)
            if (removed.isNotEmpty()) insertIdx = doc.length(listId)
            for (file in next.map { it.audioFile }) {
                if (file in added) {
                    val rec = nextByFile[file]!!
                    val recId = tx.insert(listId, insertIdx, ObjectType.MAP)
                    tx.set(recId, "audioFile", rec.audioFile)
                    tx.set(recId, "startTimeMs", rec.startTimeMs.toDouble())
                    tx.set(recId, "durationMs", rec.durationMs.toDouble())
                    insertIdx++
                }
            }
        }
    }

    // --- Helpers ---

    private fun getMapId(doc: Document, parent: ObjectId, key: String): ObjectId? =
        (doc.get(parent, key).orElse(null) as? AmValue.Map)?.id

    private fun getListId(doc: Document, parent: ObjectId, key: String): ObjectId? =
        (doc.get(parent, key).orElse(null) as? AmValue.List)?.id
}
