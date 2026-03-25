package com.writer.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocumentInfo(
    val name: String,
    val lastModified: Long
)

object DocumentStorage {

    private const val TAG = "DocumentStorage"
    private const val DOCS_DIR = "documents"
    private const val LEGACY_FILE = "document.json"

    private fun docsDir(context: Context): File {
        val dir = File(context.filesDir, DOCS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun docFile(context: Context, name: String): File {
        return File(docsDir(context), "$name.json")
    }

    // --- Migration ---

    fun migrateIfNeeded(context: Context): String? {
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.exists()) return null

        val targetName = "Document 1"
        val target = docFile(context, targetName)
        if (!target.exists()) {
            legacy.copyTo(target)
        }
        legacy.delete()
        Log.i(TAG, "Migrated $LEGACY_FILE → $DOCS_DIR/$targetName.json")
        return targetName
    }

    // --- Multi-document CRUD ---

    fun save(context: Context, name: String, data: DocumentData) {
        try {
            val json = serializeToJson(data)
            val file = docFile(context, name)
            file.writeText(json.toString())
            Log.i(TAG, "Saved ${data.main.strokes.size} strokes to $name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save document $name", e)
        }
    }

    fun load(context: Context, name: String): DocumentData? {
        try {
            val file = docFile(context, name)
            if (!file.exists()) return null
            val data = deserializeFromJson(file.readText())
            Log.i(TAG, "Loaded ${data.main.strokes.size} strokes from $name")
            return data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load document $name", e)
            return null
        }
    }

    fun listDocuments(context: Context): List<DocumentInfo> {
        val dir = docsDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { DocumentInfo(it.nameWithoutExtension, it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    fun delete(context: Context, name: String): Boolean {
        return docFile(context, name).delete()
    }

    fun generateName(context: Context): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val base = "Document $dateStr"
        if (!docFile(context, base).exists()) return base

        var i = 2
        while (docFile(context, "$base ($i)").exists()) i++
        return "$base ($i)"
    }

    /** Convert a heading string to a safe file name. */
    fun headingToFileName(heading: String): String {
        return heading.trim()
            .replace(Regex("[/\\\\]"), "-")
            .replace(Regex("[*?\"<>|:]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
    }

    /** Generate a unique file name from a heading, appending date if needed. */
    fun generateNameFromHeading(context: Context, heading: String): String? {
        val base = headingToFileName(heading)
        if (base.isEmpty()) return null
        if (!docFile(context, base).exists()) return base

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dated = "$base $dateStr"
        if (!docFile(context, dated).exists()) return dated

        var i = 2
        while (docFile(context, "$dated ($i)").exists()) i++
        return "$dated ($i)"
    }

    /** Rename a document file on disk. Returns true on success. */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        val oldFile = docFile(context, oldName)
        val newFile = docFile(context, newName)
        if (!oldFile.exists() || newFile.exists()) return false
        return oldFile.renameTo(newFile)
    }

    // --- Sync folder export ---

    fun exportToSyncFolder(
        context: Context,
        name: String,
        data: DocumentData,
        markdownText: String,
        syncFolderUri: Uri
    ) {
        try {
            val folder = DocumentFile.fromTreeUri(context, syncFolderUri) ?: return

            // Write .writer file (proprietary JSON)
            val writerFileName = "$name.writer"
            val existingWriter = folder.findFile(writerFileName)
            val writerFile = existingWriter
                ?: folder.createFile("application/octet-stream", writerFileName)
            if (writerFile != null) {
                context.contentResolver.openOutputStream(writerFile.uri, "wt")?.use { out ->
                    out.write(serializeToJson(data).toString().toByteArray())
                }
            }

            // Write .md file (recognized text)
            val mdFileName = "$name.md"
            val existingMd = folder.findFile(mdFileName)
            val mdFile = existingMd
                ?: folder.createFile("text/markdown", mdFileName)
            if (mdFile != null) {
                context.contentResolver.openOutputStream(mdFile.uri, "wt")?.use { out ->
                    out.write(markdownText.toByteArray())
                }
            }

            Log.i(TAG, "Exported $name to sync folder")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export $name to sync folder", e)
        }
    }

    // --- Serialization ---

    internal fun serializeToJson(data: DocumentData): JSONObject {
        val json = JSONObject()

        json.put("scrollOffsetY", data.scrollOffsetY.toDouble())
        json.put("highestLineIndex", data.highestLineIndex)
        json.put("currentLineIndex", data.currentLineIndex)
        json.put("userRenamed", data.userRenamed)

        json.put("main", serializeColumnToJson(data.main))

        val hasCueContent = data.cue.strokes.isNotEmpty() ||
                data.cue.lineTextCache.isNotEmpty() ||
                data.cue.diagramAreas.isNotEmpty()
        if (hasCueContent) {
            json.put("cue", serializeColumnToJson(data.cue))
        }

        return json
    }

    private fun serializeColumnToJson(col: ColumnData): JSONObject {
        val json = JSONObject()

        val cacheObj = JSONObject()
        for ((key, value) in col.lineTextCache) {
            cacheObj.put(key.toString(), value)
        }
        json.put("lineTextCache", cacheObj)

        val hiddenArr = JSONArray()
        for (line in col.everHiddenLines) {
            hiddenArr.put(line)
        }
        json.put("everHiddenLines", hiddenArr)

        json.put("strokes", serializeStrokesToJson(col.strokes))

        val diagramArr = JSONArray()
        for (area in col.diagramAreas) {
            val areaObj = JSONObject()
            areaObj.put("id", area.id)
            areaObj.put("startLineIndex", area.startLineIndex)
            areaObj.put("heightInLines", area.heightInLines)
            diagramArr.put(areaObj)
        }
        json.put("diagramAreas", diagramArr)

        return json
    }

    private fun serializeStrokesToJson(strokes: List<InkStroke>): JSONArray {
        val strokesArr = JSONArray()
        for (stroke in strokes) {
            val strokeObj = JSONObject()
            strokeObj.put("strokeId", stroke.strokeId)
            strokeObj.put("strokeWidth", stroke.strokeWidth.toDouble())

            val pointsArr = JSONArray()
            for (pt in stroke.points) {
                val ptObj = JSONObject()
                ptObj.put("x", pt.x.toDouble())
                ptObj.put("y", pt.y.toDouble())
                ptObj.put("pressure", pt.pressure.toDouble())
                ptObj.put("timestamp", pt.timestamp)
                pointsArr.put(ptObj)
            }
            strokeObj.put("points", pointsArr)
            if (stroke.strokeType != StrokeType.FREEHAND) {
                strokeObj.put("strokeType", stroke.strokeType.name)
            }
            if (stroke.isGeometric) {
                strokeObj.put("isGeometric", true)
            }
            strokesArr.put(strokeObj)
        }
        return strokesArr
    }

    internal fun deserializeFromJson(text: String): DocumentData {
        val json = JSONObject(text)

        val scrollOffsetY = json.optDouble("scrollOffsetY", 0.0).toFloat()
        val highestLineIndex = json.optInt("highestLineIndex", -1)
        val currentLineIndex = json.optInt("currentLineIndex", -1)
        val userRenamed = json.optBoolean("userRenamed", false)

        // Detect format: new format has "main" object, legacy has flat "strokes" array
        val mainColumn = if (json.has("main")) {
            deserializeColumnFromJson(json.getJSONObject("main"))
        } else {
            // Legacy format: per-column fields are at top level
            deserializeColumnFromJson(json)
        }

        val cueColumn = if (json.has("cue")) {
            deserializeColumnFromJson(json.getJSONObject("cue"))
        } else {
            ColumnData()
        }

        return DocumentData(
            main = mainColumn,
            cue = cueColumn,
            scrollOffsetY = scrollOffsetY,
            highestLineIndex = highestLineIndex,
            currentLineIndex = currentLineIndex,
            userRenamed = userRenamed
        )
    }

    private fun deserializeColumnFromJson(json: JSONObject): ColumnData {
        val lineTextCache = mutableMapOf<Int, String>()
        val cacheObj = json.optJSONObject("lineTextCache")
        if (cacheObj != null) {
            for (key in cacheObj.keys()) {
                lineTextCache[key.toInt()] = cacheObj.getString(key)
            }
        }

        val everHiddenLines = mutableSetOf<Int>()
        val hiddenArr = json.optJSONArray("everHiddenLines")
        if (hiddenArr != null) {
            for (i in 0 until hiddenArr.length()) {
                everHiddenLines.add(hiddenArr.getInt(i))
            }
        }

        val strokes = deserializeStrokesFromJson(json.optJSONArray("strokes"))

        val diagramAreas = mutableListOf<DiagramArea>()
        val diagramArr = json.optJSONArray("diagramAreas")
        if (diagramArr != null) {
            for (i in 0 until diagramArr.length()) {
                val areaObj = diagramArr.getJSONObject(i)
                diagramAreas.add(
                    DiagramArea(
                        id = areaObj.getString("id"),
                        startLineIndex = areaObj.getInt("startLineIndex"),
                        heightInLines = areaObj.getInt("heightInLines")
                    )
                )
            }
        }

        return ColumnData(
            strokes = strokes,
            lineTextCache = lineTextCache,
            everHiddenLines = everHiddenLines,
            diagramAreas = diagramAreas
        )
    }

    private fun deserializeStrokesFromJson(strokesArr: JSONArray?): List<InkStroke> {
        if (strokesArr == null) return emptyList()
        val strokes = mutableListOf<InkStroke>()
        for (i in 0 until strokesArr.length()) {
            val strokeObj = strokesArr.getJSONObject(i)
            val strokeId = strokeObj.getString("strokeId")
            val strokeWidth = strokeObj.optDouble("strokeWidth", 5.0).toFloat()

            val pointsArr = strokeObj.getJSONArray("points")
            val points = mutableListOf<StrokePoint>()
            for (j in 0 until pointsArr.length()) {
                val ptObj = pointsArr.getJSONObject(j)
                points.add(
                    StrokePoint(
                        x = ptObj.getDouble("x").toFloat(),
                        y = ptObj.getDouble("y").toFloat(),
                        pressure = ptObj.getDouble("pressure").toFloat(),
                        timestamp = ptObj.getLong("timestamp")
                    )
                )
            }

            val strokeTypeName = strokeObj.optString("strokeType", "")
            val strokeType = try {
                if (strokeTypeName.isNotEmpty()) StrokeType.valueOf(strokeTypeName)
                else StrokeType.FREEHAND
            } catch (_: IllegalArgumentException) { StrokeType.FREEHAND }

            strokes.add(
                InkStroke(
                    strokeId = strokeId,
                    points = points,
                    strokeWidth = strokeWidth,
                    strokeType = strokeType,
                    isGeometric = strokeObj.optBoolean("isGeometric", false)
                )
            )
        }
        return strokes
    }
}
