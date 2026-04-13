package com.writer.storage

import com.writer.model.AudioRecording
import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.TextBlock
import com.writer.model.WordInfo
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType

/**
 * Bidirectional conversion between [DocumentData] and Automerge [Document].
 *
 * Only synced content is stored in Automerge. Local-only state (scrollOffsetY,
 * highestLineIndex, currentLineIndex, userRenamed) and derived state
 * (lineTextCache, everHiddenLines) are excluded.
 */
object AutomergeAdapter {

    private const val SCHEMA_VERSION = 1

    fun toAutomerge(data: DocumentData): Document {
        val doc = Document()
        val tx = doc.startTransaction()
        tx.set(ObjectId.ROOT, "_schemaVersion", SCHEMA_VERSION)
        val mainId = tx.set(ObjectId.ROOT, "main", ObjectType.MAP)
        writeColumn(tx, mainId, data.main)
        val cueId = tx.set(ObjectId.ROOT, "cue", ObjectType.MAP)
        writeColumn(tx, cueId, data.cue)
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
        val main = readColumn(doc, ObjectId.ROOT, "main")
        val cue = readColumn(doc, ObjectId.ROOT, "cue")
        val audioRecordings = readAudioRecordings(doc)
        return DocumentData(
            main = main,
            cue = cue,
            audioRecordings = audioRecordings,
        )
    }

    // --- Write helpers ---

    private fun writeColumn(tx: org.automerge.Transaction, columnId: ObjectId, col: ColumnData) {
        val strokesId = tx.set(columnId, "strokes", ObjectType.LIST)
        for ((i, stroke) in col.strokes.withIndex()) {
            val sId = tx.insert(strokesId, i.toLong(), ObjectType.MAP)
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

    // --- Read helpers ---

    private fun readColumn(doc: Document, parent: ObjectId, key: String): ColumnData {
        val columnId = doc.get(parent, key).orElse(null)?.asMapId() ?: return ColumnData()
        val strokes = readStrokes(doc, columnId)
        val textBlocks = readTextBlocks(doc, columnId)
        val diagramAreas = readDiagramAreas(doc, columnId)
        return ColumnData(
            strokes = strokes,
            textBlocks = textBlocks,
            diagramAreas = diagramAreas,
        )
    }

    private fun readStrokes(doc: Document, columnId: ObjectId): List<InkStroke> {
        val strokesId = doc.get(columnId, "strokes").orElse(null)?.asListId() ?: return emptyList()
        return doc.listItems(strokesId).orElse(emptyArray()).mapNotNull { item ->
            val sId = item.asMapId() ?: return@mapNotNull null
            val strokeId = doc.getString(sId, "strokeId") ?: ""
            val strokeWidth = doc.getDouble(sId, "strokeWidth")?.toFloat() ?: 3f
            val strokeType = doc.getString(sId, "strokeType")?.let {
                try { StrokeType.valueOf(it) } catch (_: Exception) { StrokeType.FREEHAND }
            } ?: StrokeType.FREEHAND
            val isGeometric = doc.getBool(sId, "isGeometric") ?: false
            val points = readPoints(doc, sId)
            InkStroke(strokeId, points, strokeWidth, isGeometric = isGeometric, strokeType = strokeType)
        }
    }

    private fun readPoints(doc: Document, strokeId: ObjectId): List<StrokePoint> {
        val pointsId = doc.get(strokeId, "points").orElse(null)?.asListId() ?: return emptyList()
        return doc.listItems(pointsId).orElse(emptyArray()).mapNotNull { item ->
            val ptId = item.asMapId() ?: return@mapNotNull null
            StrokePoint(
                x = doc.getDouble(ptId, "x")?.toFloat() ?: 0f,
                y = doc.getDouble(ptId, "y")?.toFloat() ?: 0f,
                pressure = doc.getDouble(ptId, "pressure")?.toFloat() ?: 0f,
                timestamp = doc.getDouble(ptId, "timestamp")?.toLong() ?: 0L,
            )
        }
    }

    private fun readTextBlocks(doc: Document, columnId: ObjectId): List<TextBlock> {
        val tbsId = doc.get(columnId, "textBlocks").orElse(null)?.asListId() ?: return emptyList()
        return doc.listItems(tbsId).orElse(emptyArray()).mapNotNull { item ->
            val tbId = item.asMapId() ?: return@mapNotNull null
            val words = readWords(doc, tbId)
            TextBlock(
                id = doc.getString(tbId, "id") ?: "",
                startLineIndex = doc.getInt(tbId, "startLineIndex") ?: 0,
                heightInLines = doc.getInt(tbId, "heightInLines") ?: 0,
                text = doc.getString(tbId, "text") ?: "",
                audioFile = doc.getString(tbId, "audioFile") ?: "",
                audioStartMs = doc.getDouble(tbId, "audioStartMs")?.toLong() ?: 0L,
                audioEndMs = doc.getDouble(tbId, "audioEndMs")?.toLong() ?: 0L,
                words = words,
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
}
