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
import androidx.core.util.AtomicFile
import com.writer.model.proto.DocumentProto
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocumentInfo(
    val name: String,
    val lastModified: Long,
    /** Concatenated recognized text from the document (for search). */
    val textContent: String = ""
)

@OptIn(DelicateCoroutinesApi::class)
object DocumentStorage {

    private const val TAG = "DocumentStorage"
    private const val DOCS_DIR = "documents"
    private const val LEGACY_FILE = "document.json"

    private const val MOKKE_EXT = "mok"
    private const val LEGACY_INKUP_EXT = "inkup"

    private fun docsDir(context: Context): File {
        val dir = File(context.filesDir, DOCS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun docFile(context: Context, name: String): File {
        return File(docsDir(context), "$name.json")
    }

    private fun mokFile(context: Context, name: String): File {
        return File(docsDir(context), "$name.$MOKKE_EXT")
    }

    private fun legacyInkupFile(context: Context, name: String): File {
        return File(docsDir(context), "$name.$LEGACY_INKUP_EXT")
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

    fun save(
        context: Context,
        name: String,
        data: DocumentData,
        audioFiles: Map<String, ByteArray> = emptyMap()
    ): Boolean {
        try {
            val file = mokFile(context, name)

            // Preserve existing audio files when none are provided.
            // Auto-saves only pass document data; without this, they would
            // overwrite the bundle and lose previously recorded audio.
            val allAudio = if (audioFiles.isEmpty() && file.exists()) {
                try {
                    val existing = file.inputStream().use { DocumentBundle.readZip(it) }
                    existing.audioFiles
                } catch (_: Exception) { emptyMap() }
            } else if (audioFiles.isNotEmpty() && file.exists()) {
                // Merge: keep existing audio + add new
                try {
                    val existing = file.inputStream().use { DocumentBundle.readZip(it) }
                    existing.audioFiles + audioFiles
                } catch (_: Exception) { audioFiles }
            } else {
                audioFiles
            }

            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                DocumentBundle.writeZip(stream, data, allAudio)
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }

            Log.i(TAG, "Saved ${data.main.strokes.size} strokes to $name.$MOKKE_EXT")
            // Update search index with per-line recognized text
            GlobalScope.launch(Dispatchers.IO) {
                SearchIndexManager.indexDocument(context, name, data)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save document $name", e)
            return false
        }
    }

    fun load(context: Context, name: String): DocumentData? {
        return loadBundle(context, name)?.data
    }

    /** Load a document with its audio files. */
    fun loadBundle(context: Context, name: String): BundleResult? {
        // Try .mok first, then legacy .inkup, then legacy .json
        try {
            val mok = mokFile(context, name)
            val mokBackup = File(mok.parent, "${mok.name}.new")
            if (mok.exists() || mokBackup.exists()) {
                val bytes = AtomicFile(mok).readFully()
                val result = DocumentBundle.read(bytes)
                Log.i(TAG, "Loaded ${result.data.main.strokes.size} strokes from $name.$MOKKE_EXT")
                return result
            }

            // Legacy .inkup (raw protobuf or ZIP)
            val inkup = legacyInkupFile(context, name)
            val inkupBackup = File(inkup.parent, "${inkup.name}.new")
            if (inkup.exists() || inkupBackup.exists()) {
                val bytes = AtomicFile(inkup).readFully()
                val result = DocumentBundle.read(bytes)
                Log.i(TAG, "Loaded ${result.data.main.strokes.size} strokes from $name.$LEGACY_INKUP_EXT (legacy)")
                return result
            }

            val json = docFile(context, name)
            if (json.exists()) {
                val data = deserializeFromJson(json.readText())
                Log.i(TAG, "Loaded ${data.main.strokes.size} strokes from $name.json (legacy)")
                return BundleResult(data = data)
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load document $name", e)
            return null
        }
    }

    fun listDocuments(context: Context): List<DocumentInfo> {
        val dir = docsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        // Collect .mok, .inkup, and .json files, dedup by name (prefer .mok for lastModified)
        val byName = mutableMapOf<String, Long>()
        for (file in files) {
            if (file.extension == MOKKE_EXT || file.extension == LEGACY_INKUP_EXT ||
                (file.extension == "json" && file.name != "search-index.json")) {
                val name = file.nameWithoutExtension
                val existing = byName[name]
                if (existing == null || file.lastModified() > existing) {
                    byName[name] = file.lastModified()
                }
            }
        }
        return byName.map { (name, lastModified) -> DocumentInfo(name, lastModified) }
            .sortedByDescending { it.lastModified }
    }

    // --- Search index ---

    /** A search match: line index and the text on that line. */
    data class SearchMatch(val lineIndex: Int, val text: String)

    fun delete(context: Context, name: String): Boolean {
        val mok = mokFile(context, name)
        val inkup = legacyInkupFile(context, name)
        val json = docFile(context, name)
        // AtomicFile.delete() removes the base file and its .new backup
        AtomicFile(mok).delete()
        AtomicFile(inkup).delete()
        val deletedJson = json.delete()
        val deletedAny = !mok.exists() || !inkup.exists() || deletedJson
        if (deletedAny) {
            GlobalScope.launch(Dispatchers.IO) {
                SearchIndexManager.removeDocument(context, name)
            }
        }
        return deletedAny
    }

    /** Check if a document exists in any format (.mok, .inkup, or .json). */
    private fun docExists(context: Context, name: String): Boolean {
        val mok = mokFile(context, name)
        val inkup = legacyInkupFile(context, name)
        return mok.exists() ||
            File(mok.parent, "${mok.name}.new").exists() ||
            inkup.exists() ||
            File(inkup.parent, "${inkup.name}.new").exists() ||
            docFile(context, name).exists()
    }

    fun generateName(context: Context): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val base = "Document $dateStr"
        if (!docExists(context, base)) return base

        var i = 2
        while (docExists(context, "$base ($i)")) i++
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
        if (!docExists(context, base)) return base

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dated = "$base $dateStr"
        if (!docExists(context, dated)) return dated

        var i = 2
        while (docExists(context, "$dated ($i)")) i++
        return "$dated ($i)"
    }

    /** Rename a document file on disk. Returns true on success. */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        // Rename .mok file if it exists, else .inkup, else .json
        val oldMokke = mokFile(context, oldName)
        val oldInkup = legacyInkupFile(context, oldName)
        val oldJson = docFile(context, oldName)
        val source = when {
            oldMokke.exists() -> oldMokke
            oldInkup.exists() -> oldInkup
            oldJson.exists() -> oldJson
            else -> return false
        }
        val target = File(source.parent, "${newName}.${source.extension}")
        if (target.exists()) return false
        val renamed = source.renameTo(target)
        if (renamed) {
            // Clean up AtomicFile's .new backup for the old name
            File(source.parent, "${source.name}.new").delete()
            GlobalScope.launch(Dispatchers.IO) {
                SearchIndexManager.renameDocument(context, oldName, newName)
            }
        }
        return renamed
    }

    // --- Sync folder export ---

    fun exportToSyncFolder(
        context: Context,
        name: String,
        data: DocumentData,
        markdownText: String,
        syncFolderUri: Uri,
        audioFiles: Map<String, ByteArray> = emptyMap()
    ) {
        try {
            val folder = DocumentFile.fromTreeUri(context, syncFolderUri) ?: return

            // Write .mok file (ZIP bundle)
            val mokFileName = "$name.$MOKKE_EXT"
            val existingMokke = folder.findFile(mokFileName)
            val mokSyncFile = existingMokke
                ?: folder.createFile("application/octet-stream", mokFileName)
            if (mokSyncFile != null) {
                context.contentResolver.openOutputStream(mokSyncFile.uri, "wt")?.use { out ->
                    DocumentBundle.writeZip(out, data, audioFiles)
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

    // --- Sync folder import ---

    /**
     * Restore documents from the sync folder. Reads .writer (JSON), .mok (ZIP bundle),
     * and legacy .inkup (protobuf) files and saves them, skipping any that already exist.
     * Returns the number of documents restored.
     */
    fun restoreFromSyncFolder(context: Context, syncFolderUri: Uri): Int {
        val folder = DocumentFile.fromTreeUri(context, syncFolderUri) ?: return 0
        var count = 0
        for (file in folder.listFiles()) {
            val fileName = file.name ?: continue
            val name: String
            val data: DocumentData?
            when {
                fileName.endsWith(".writer") -> {
                    name = fileName.removeSuffix(".writer")
                    data = try {
                        val json = context.contentResolver.openInputStream(file.uri)
                            ?.bufferedReader()?.readText() ?: continue
                        deserializeFromJson(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read .writer file: $fileName", e)
                        continue
                    }
                }
                fileName.endsWith(".$MOKKE_EXT") || fileName.endsWith(".$LEGACY_INKUP_EXT") -> {
                    name = when {
                        fileName.endsWith(".$MOKKE_EXT") -> fileName.removeSuffix(".$MOKKE_EXT")
                        else -> fileName.removeSuffix(".$LEGACY_INKUP_EXT")
                    }
                    data = try {
                        val bytes = context.contentResolver.openInputStream(file.uri)
                            ?.readBytes() ?: continue
                        DocumentBundle.read(bytes).data
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read file: $fileName", e)
                        continue
                    }
                }
                else -> continue
            }
            if (data == null) continue
            // Skip if document already exists locally
            if (docExists(context, name)) continue
            save(context, name, data)
            count++
            Log.i(TAG, "Restored document: $name")
        }
        return count
    }

    // --- Serialization ---

    @androidx.annotation.VisibleForTesting
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

    @androidx.annotation.VisibleForTesting
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
