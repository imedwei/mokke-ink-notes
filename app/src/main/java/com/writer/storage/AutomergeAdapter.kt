package com.writer.storage

import com.writer.model.AnchorMode
import com.writer.model.AnchorTarget
import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokeType
import com.writer.model.TextBlock
import com.writer.model.WordInfo
import com.writer.model.minY
import com.writer.model.maxY
import com.writer.view.ScreenMetrics
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType

/**
 * Bidirectional conversion between [DocumentData] and Automerge [Document].
 *
 * Strokes use delta-encoded packed points in line-height-normalized coordinates
 * with `originLine` for efficient space insert.
 *
 * Only synced content is stored in Automerge. Local-only state (scrollOffsetY,
 * highestLineIndex, currentLineIndex, userRenamed) and derived state
 * (lineTextCache, everHiddenLines) are excluded.
 */
object AutomergeAdapter {

    private const val SCHEMA_VERSION = 3

    fun toAutomerge(data: DocumentData): Document {
        val doc = Document()
        val tx = doc.startTransaction()
        tx.set(ObjectId.ROOT, "_schemaVersion", SCHEMA_VERSION)
        val mainId = tx.set(ObjectId.ROOT, "main", ObjectType.MAP)
        writeColumn(tx, mainId, data.main)
        val cueId = tx.set(ObjectId.ROOT, "cue", ObjectType.MAP)
        writeColumn(tx, cueId, data.cue)
        val transcriptId = tx.set(ObjectId.ROOT, "transcript", ObjectType.MAP)
        writeColumn(tx, transcriptId, data.transcript)
        val recsId = tx.set(ObjectId.ROOT, "audioRecordings", ObjectType.LIST)
        for ((i, rec) in data.audioRecordings.withIndex()) {
            val recId = tx.insert(recsId, i.toLong(), ObjectType.MAP)
            tx.set(recId, "audioFile", rec.audioFile)
            tx.set(recId, "startTimeMs", rec.startTimeMs.toDouble())
            tx.set(recId, "durationMs", rec.durationMs.toDouble())
        }
        tx.commit()
        return doc
    }

    fun fromAutomerge(doc: Document): DocumentData {
        val rawMain = readColumn(doc, ObjectId.ROOT, "main")
        val rawCue = readColumn(doc, ObjectId.ROOT, "cue")
        val rawTranscript = readColumn(doc, ObjectId.ROOT, "transcript")
        val audioRecordings = readAudioRecordings(doc)
        val (main, cue, transcript) = migrateTranscriptIfNeeded(rawMain, rawCue, rawTranscript)
        return DocumentData(
            main = main,
            cue = cue,
            transcript = transcript,
            audioRecordings = audioRecordings,
        )
    }

    /**
     * Viewport-filtered read. Only unpacks strokes whose line range intersects
     * [viewportStartLine]..[viewportEndLine]. Other strokes are skipped.
     */
    fun fromAutomerge(
        doc: Document,
        viewportStartLine: Int,
        viewportEndLine: Int,
    ): DocumentData {
        val rawMain = readColumn(doc, ObjectId.ROOT, "main", viewportStartLine, viewportEndLine)
        val rawCue = readColumn(doc, ObjectId.ROOT, "cue", viewportStartLine, viewportEndLine)
        val rawTranscript = readColumn(doc, ObjectId.ROOT, "transcript", viewportStartLine, viewportEndLine)
        val audioRecordings = readAudioRecordings(doc)
        val (main, cue, transcript) = migrateTranscriptIfNeeded(rawMain, rawCue, rawTranscript)
        return DocumentData(
            main = main,
            cue = cue,
            transcript = transcript,
            audioRecordings = audioRecordings,
        )
    }

    /** v2→v3 Automerge schema migration — same rule as the proto migration:
     *  any TextBlocks still in main/cue move to transcript with anchorTarget stamped. */
    private fun migrateTranscriptIfNeeded(
        main: ColumnData, cue: ColumnData, transcript: ColumnData,
    ): Triple<ColumnData, ColumnData, ColumnData> {
        if (main.textBlocks.isEmpty() && cue.textBlocks.isEmpty()) {
            return Triple(main, cue, transcript)
        }
        val fromMain = main.textBlocks.map {
            it.copy(
                anchorTarget = AnchorTarget.MAIN,
                anchorLineIndex = if (it.anchorLineIndex >= 0) it.anchorLineIndex else it.startLineIndex,
                anchorMode = AnchorMode.AUTO,
            )
        }
        val fromCue = cue.textBlocks.map {
            it.copy(
                anchorTarget = AnchorTarget.CUE,
                anchorLineIndex = if (it.anchorLineIndex >= 0) it.anchorLineIndex else it.startLineIndex,
                anchorMode = AnchorMode.AUTO,
            )
        }
        return Triple(
            main.copy(textBlocks = emptyList()),
            cue.copy(textBlocks = emptyList()),
            transcript.copy(textBlocks = transcript.textBlocks + fromMain + fromCue),
        )
    }

    // --- Write helpers ---

    private fun writeColumn(tx: org.automerge.Transaction, columnId: ObjectId, col: ColumnData) {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin

        val strokesId = tx.set(columnId, "strokes", ObjectType.LIST)
        for ((i, stroke) in col.strokes.withIndex()) {
            val originLine = computeOriginLine(stroke, ls, tm)
            val heightInLines = computeHeightInLines(stroke, originLine, ls, tm)

            val sId = tx.insert(strokesId, i.toLong(), ObjectType.MAP)
            tx.set(sId, "strokeId", stroke.strokeId)
            tx.set(sId, "strokeType", stroke.strokeType.name)
            tx.set(sId, "isGeometric", stroke.isGeometric)
            tx.set(sId, "originLine", originLine)
            tx.set(sId, "heightInLines", heightInLines)
            tx.set(sId, "pointsData", PointPacking.pack(stroke.points, originLine, ls, tm))
        }

        val textBlocksId = tx.set(columnId, "textBlocks", ObjectType.LIST)
        for ((i, tb) in col.textBlocks.withIndex()) {
            val tbId = tx.insert(textBlocksId, i.toLong(), ObjectType.MAP)
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

        val diagramsId = tx.set(columnId, "diagramAreas", ObjectType.LIST)
        for ((i, da) in col.diagramAreas.withIndex()) {
            val daId = tx.insert(diagramsId, i.toLong(), ObjectType.MAP)
            tx.set(daId, "id", da.id)
            tx.set(daId, "startLineIndex", da.startLineIndex)
            tx.set(daId, "heightInLines", da.heightInLines)
        }
    }

    internal fun computeOriginLine(stroke: InkStroke, ls: Float, tm: Float): Int =
        ((stroke.minY - tm) / ls).toInt().coerceAtLeast(0)

    internal fun computeHeightInLines(stroke: InkStroke, originLine: Int, ls: Float, tm: Float): Int {
        val endLine = ((stroke.maxY - tm) / ls).toInt().coerceAtLeast(0)
        return (endLine - originLine + 1).coerceAtLeast(1)
    }

    // --- Read helpers ---

    private fun readColumn(
        doc: Document, parent: ObjectId, key: String,
        viewportStartLine: Int = Int.MIN_VALUE,
        viewportEndLine: Int = Int.MAX_VALUE,
    ): ColumnData {
        val columnId = doc.get(parent, key).orElse(null)?.asMapId() ?: return ColumnData()
        val strokes = readStrokes(doc, columnId, viewportStartLine, viewportEndLine)
        val textBlocks = readTextBlocks(doc, columnId)
        val diagramAreas = readDiagramAreas(doc, columnId)
        return ColumnData(
            strokes = strokes,
            textBlocks = textBlocks,
            diagramAreas = diagramAreas,
        )
    }

    private fun readStrokes(
        doc: Document, columnId: ObjectId,
        viewportStartLine: Int, viewportEndLine: Int,
    ): List<InkStroke> {
        val strokesId = doc.get(columnId, "strokes").orElse(null)?.asListId() ?: return emptyList()
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin

        return doc.listItems(strokesId).orElse(emptyArray()).mapNotNull { item ->
            val sId = item.asMapId() ?: return@mapNotNull null
            val strokeId = doc.getString(sId, "strokeId") ?: ""
            val strokeType = doc.getString(sId, "strokeType")?.let {
                try { StrokeType.valueOf(it) } catch (_: Exception) { StrokeType.FREEHAND }
            } ?: StrokeType.FREEHAND
            val isGeometric = doc.getBool(sId, "isGeometric") ?: false
            val originLine = doc.getInt(sId, "originLine") ?: 0
            val heightInLines = doc.getInt(sId, "heightInLines") ?: 1

            // Viewport filter: skip strokes outside visible range
            if (originLine + heightInLines < viewportStartLine || originLine > viewportEndLine) {
                return@mapNotNull null
            }

            val pointsData = doc.getBytes(sId, "pointsData")
            val points = if (pointsData != null) {
                PointPacking.unpack(pointsData, originLine, ls, tm)
            } else {
                emptyList()
            }

            InkStroke(strokeId, points, isGeometric = isGeometric, strokeType = strokeType)
        }
    }

    private fun readTextBlocks(doc: Document, columnId: ObjectId): List<TextBlock> {
        val tbsId = doc.get(columnId, "textBlocks").orElse(null)?.asListId() ?: return emptyList()
        return doc.listItems(tbsId).orElse(emptyArray()).mapNotNull { item ->
            val tbId = item.asMapId() ?: return@mapNotNull null
            val words = readWords(doc, tbId)
            val anchorTarget = doc.getString(tbId, "anchorTarget")?.let {
                try { AnchorTarget.valueOf(it) } catch (_: Exception) { AnchorTarget.MAIN }
            } ?: AnchorTarget.MAIN
            val anchorMode = doc.getString(tbId, "anchorMode")?.let {
                try { AnchorMode.valueOf(it) } catch (_: Exception) { AnchorMode.AUTO }
            } ?: AnchorMode.AUTO
            TextBlock(
                id = doc.getString(tbId, "id") ?: "",
                startLineIndex = doc.getInt(tbId, "startLineIndex") ?: 0,
                heightInLines = doc.getInt(tbId, "heightInLines") ?: 0,
                text = doc.getString(tbId, "text") ?: "",
                audioFile = doc.getString(tbId, "audioFile") ?: "",
                audioStartMs = doc.getDouble(tbId, "audioStartMs")?.toLong() ?: 0L,
                audioEndMs = doc.getDouble(tbId, "audioEndMs")?.toLong() ?: 0L,
                words = words,
                anchorLineIndex = doc.getInt(tbId, "anchorLineIndex") ?: -1,
                anchorTarget = anchorTarget,
                anchorMode = anchorMode,
            )
        }
    }

    private fun readWords(doc: Document, tbId: ObjectId): List<WordInfo> {
        val wordsId = doc.get(tbId, "words").orElse(null)?.asListId() ?: return emptyList()
        return doc.listItems(wordsId).orElse(emptyArray()).mapNotNull { item ->
            val wId = item.asMapId() ?: return@mapNotNull null
            WordInfo(
                text = doc.getString(wId, "text") ?: "",
                confidence = doc.getDouble(wId, "confidence")?.toFloat() ?: 1f,
                startMs = doc.getDouble(wId, "startMs")?.toLong() ?: 0L,
                endMs = doc.getDouble(wId, "endMs")?.toLong() ?: 0L,
            )
        }
    }

    private fun readDiagramAreas(doc: Document, columnId: ObjectId): List<DiagramArea> {
        val dasId = doc.get(columnId, "diagramAreas").orElse(null)?.asListId() ?: return emptyList()
        return doc.listItems(dasId).orElse(emptyArray()).mapNotNull { item ->
            val daId = item.asMapId() ?: return@mapNotNull null
            DiagramArea(
                id = doc.getString(daId, "id") ?: "",
                startLineIndex = doc.getInt(daId, "startLineIndex") ?: 0,
                heightInLines = doc.getInt(daId, "heightInLines") ?: 0,
            )
        }
    }

    private fun readAudioRecordings(doc: Document): List<AudioRecording> {
        val recsId = doc.get(ObjectId.ROOT, "audioRecordings").orElse(null)?.asListId()
            ?: return emptyList()
        return doc.listItems(recsId).orElse(emptyArray()).mapNotNull { item ->
            val recId = item.asMapId() ?: return@mapNotNull null
            AudioRecording(
                audioFile = doc.getString(recId, "audioFile") ?: "",
                startTimeMs = doc.getDouble(recId, "startTimeMs")?.toLong() ?: 0L,
                durationMs = doc.getDouble(recId, "durationMs")?.toLong() ?: 0L,
            )
        }
    }

    // --- Extension helpers for concise reads ---

    private fun AmValue?.asMapId(): ObjectId? =
        (this as? AmValue.Map)?.id

    private fun AmValue?.asListId(): ObjectId? =
        (this as? AmValue.List)?.id

    private fun Document.getString(obj: ObjectId, key: String): String? =
        get(obj, key).orElse(null)?.let { (it as? AmValue.Str)?.value }

    private fun Document.getDouble(obj: ObjectId, key: String): Double? =
        get(obj, key).orElse(null)?.let { (it as? AmValue.F64)?.value }

    private fun Document.getInt(obj: ObjectId, key: String): Int? =
        get(obj, key).orElse(null)?.let { (it as? AmValue.Int)?.value?.toInt() }

    private fun Document.getBool(obj: ObjectId, key: String): Boolean? =
        get(obj, key).orElse(null)?.let { (it as? AmValue.Bool)?.value }

    private fun Document.getBytes(obj: ObjectId, key: String): ByteArray? =
        get(obj, key).orElse(null)?.let { (it as? AmValue.Bytes)?.value }
}
