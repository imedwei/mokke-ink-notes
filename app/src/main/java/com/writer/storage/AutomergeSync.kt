package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.TextBlock
import com.writer.view.ScreenMetrics
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
        val transcriptId = getMapId(document, ObjectId.ROOT, "transcript")

        if (mainId != null) syncColumn(tx, document, mainId, prev.main, newState.main)
        if (cueId != null) syncColumn(tx, document, cueId, prev.cue, newState.cue)
        if (transcriptId != null) syncColumn(tx, document, transcriptId, prev.transcript, newState.transcript)
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

    // --- Stroke sync (add/remove/shift) ---

    private fun syncStrokes(
        tx: Transaction, doc: Document, listId: ObjectId,
        prev: List<InkStroke>, next: List<InkStroke>
    ) {
        if (prev == next) return

        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin

        val prevById = prev.associateBy { it.strokeId }
        val nextById = next.associateBy { it.strokeId }
        val prevSet = prevById.keys
        val nextSet = nextById.keys
        val removed = prevSet - nextSet
        val added = nextSet - prevSet
        val kept = prevSet.intersect(nextSet)

        // Delete removed strokes (iterate backwards to preserve indices)
        if (removed.isNotEmpty()) {
            val currentIds = readStrokeIds(doc, listId)
            for (i in currentIds.indices.reversed()) {
                if (currentIds[i] in removed) {
                    tx.delete(listId, i.toLong())
                }
            }
        }

        // Update shifted strokes (same ID, different Y — space insert)
        if (kept.isNotEmpty()) {
            val currentEntries = readStrokeEntries(doc, listId)
            for ((mapId, strokeId) in currentEntries) {
                if (strokeId !in kept) continue
                val prevStroke = prevById[strokeId] ?: continue
                val nextStroke = nextById[strokeId] ?: continue
                if (prevStroke == nextStroke) continue

                // Recompute originLine and heightInLines from shifted coordinates
                val newOriginLine = AutomergeAdapter.computeOriginLine(nextStroke, ls, tm)
                val newHeightInLines = AutomergeAdapter.computeHeightInLines(nextStroke, newOriginLine, ls, tm)
                tx.set(mapId, "originLine", newOriginLine)
                tx.set(mapId, "heightInLines", newHeightInLines)
                // Repack points relative to new originLine
                tx.set(mapId, "pointsData", PointPacking.pack(nextStroke.points, newOriginLine, ls, tm))
            }
        }

        // Append added strokes
        if (added.isNotEmpty()) {
            var insertIdx = doc.length(listId)
            for (id in next.map { it.strokeId }) {
                if (id in added) {
                    val stroke = nextById[id]!!
                    writeStroke(tx, listId, insertIdx, stroke, ls, tm)
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

    private fun readStrokeEntries(doc: Document, listId: ObjectId): List<Pair<ObjectId, String>> {
        return doc.listItems(listId).orElse(emptyArray()).mapNotNull { item ->
            val mapId = (item as? AmValue.Map)?.id ?: return@mapNotNull null
            val id = doc.get(mapId, "strokeId").orElse(null)?.let { (it as? AmValue.Str)?.value }
                ?: return@mapNotNull null
            mapId to id
        }
    }

    private fun writeStroke(tx: Transaction, listId: ObjectId, index: Long, stroke: InkStroke, ls: Float, tm: Float) {
        val originLine = AutomergeAdapter.computeOriginLine(stroke, ls, tm)
        val heightInLines = AutomergeAdapter.computeHeightInLines(stroke, originLine, ls, tm)

        val sId = tx.insert(listId, index, ObjectType.MAP)
        tx.set(sId, "strokeId", stroke.strokeId)
        tx.set(sId, "strokeType", stroke.strokeType.name)
        tx.set(sId, "isGeometric", stroke.isGeometric)
        tx.set(sId, "originLine", originLine)
        tx.set(sId, "heightInLines", heightInLines)
        tx.set(sId, "pointsData", PointPacking.pack(stroke.points, originLine, ls, tm))
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
                    tx.set(mapId, "anchorLineIndex", tb.anchorLineIndex)
                    tx.set(mapId, "anchorTarget", tb.anchorTarget.name)
                    tx.set(mapId, "anchorMode", tb.anchorMode.name)
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
        tx.set(tbId, "anchorLineIndex", tb.anchorLineIndex)
        tx.set(tbId, "anchorTarget", tb.anchorTarget.name)
        tx.set(tbId, "anchorMode", tb.anchorMode.name)
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
