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

object DocumentStorage {

    private const val TAG = "DocumentStorage"
    private const val DOCS_DIR = "documents"
    private const val LEGACY_FILE = "document.json"
    private const val SEARCH_INDEX_FILE = "search-index.json"
    private const val INKUP_EXT = "inkup"

    private fun docsDir(context: Context): File {
        val dir = File(context.filesDir, DOCS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun docFile(context: Context, name: String): File {
        return File(docsDir(context), "$name.json")
    }

    private fun inkupFile(context: Context, name: String): File {
        return File(docsDir(context), "$name.$INKUP_EXT")
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

    fun save(context: Context, name: String, data: DocumentData): Boolean {
        try {
            val file = inkupFile(context, name)
            val atomicFile = AtomicFile(file)

            // Encode to protobuf binary
            val bytes = data.toProto().encode()

            // AtomicFile handles tmp-write + rename atomically,
            // and keeps a .bak for recovery on failure.
            val stream = atomicFile.startWrite()
            try {
                stream.write(bytes)
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }

            Log.i(TAG, "Saved ${data.main.strokes.size} strokes to $name.inkup")
            // Update search index with per-line recognized text
            updateSearchIndex(context, name, buildLineIndex(data))
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save document $name", e)
            return false
        }
    }

    fun load(context: Context, name: String): DocumentData? {
        // Try protobuf (.inkup) first, then fall back to legacy JSON
        try {
            val inkup = inkupFile(context, name)
            val inkupBackup = File(inkup.parent, "${inkup.name}.new")
            // AtomicFile auto-recovers from interrupted writes (.new backup)
            if (inkup.exists() || inkupBackup.exists()) {
                val bytes = AtomicFile(inkup).readFully()
                val data = DocumentProto.ADAPTER.decode(bytes).toDomain()
                Log.i(TAG, "Loaded ${data.main.strokes.size} strokes from $name.inkup")
                return data
            }

            val json = docFile(context, name)
            if (json.exists()) {
                val data = deserializeFromJson(json.readText())
                Log.i(TAG, "Loaded ${data.main.strokes.size} strokes from $name.json (legacy)")
                return data
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
        // Collect .inkup and .json files, dedup by name (prefer .inkup for lastModified)
        val byName = mutableMapOf<String, Long>()
        for (file in files) {
            if (file.extension == INKUP_EXT ||
                (file.extension == "json" && file.name != SEARCH_INDEX_FILE)) {
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
    // Format: { "docName": { "lineIndex": "lowercase text", ... }, ... }

    /** A search match: line index and the text on that line. */
    data class SearchMatch(val lineIndex: Int, val text: String)

    /** Load the search index. Returns doc name → list of (lineIndex, lowercased text). */
    fun loadSearchIndex(context: Context): Map<String, List<SearchMatch>> {
        return try {
            val file = File(docsDir(context), SEARCH_INDEX_FILE)
            if (!file.exists()) return emptyMap()
            val json = org.json.JSONObject(file.readText())
            val result = mutableMapOf<String, List<SearchMatch>>()
            for (docName in json.keys()) {
                val docEntry = json.get(docName)
                if (docEntry is org.json.JSONObject) {
                    val matches = mutableListOf<SearchMatch>()
                    for (lineKey in docEntry.keys()) {
                        // Cue lines are prefixed with "c" (e.g., "c0"), main lines are plain numbers
                        val lineIndex = lineKey.removePrefix("c").toIntOrNull() ?: continue
                        matches.add(SearchMatch(lineIndex, docEntry.getString(lineKey)))
                    }
                    result[docName] = matches.sortedBy { it.lineIndex }
                } else if (docEntry is String) {
                    // Legacy flat format — treat as single entry
                    if (docEntry.isNotEmpty()) {
                        result[docName] = listOf(SearchMatch(0, docEntry))
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load search index", e)
            emptyMap()
        }
    }

    /** Build per-line text index from DocumentData (main + cue). */
    private fun buildLineIndex(data: DocumentData): org.json.JSONObject {
        val linesJson = org.json.JSONObject()
        for ((lineIdx, text) in data.main.lineTextCache) {
            if (text.isNotEmpty()) linesJson.put(lineIdx.toString(), text.lowercase())
        }
        for ((lineIdx, text) in data.cue.lineTextCache) {
            if (text.isNotEmpty()) {
                // Prefix cue lines with "c" to distinguish from main
                linesJson.put("c$lineIdx", text.lowercase())
            }
        }
        return linesJson
    }

    private fun updateSearchIndex(context: Context, name: String, linesJson: org.json.JSONObject) {
        try {
            val file = File(docsDir(context), SEARCH_INDEX_FILE)
            val json = if (file.exists()) org.json.JSONObject(file.readText()) else org.json.JSONObject()
            json.put(name, linesJson)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update search index", e)
        }
    }

    private fun removeFromSearchIndex(context: Context, name: String) {
        try {
            val file = File(docsDir(context), SEARCH_INDEX_FILE)
            if (!file.exists()) return
            val json = org.json.JSONObject(file.readText())
            json.remove(name)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update search index", e)
        }
    }

    private fun renameInSearchIndex(context: Context, oldName: String, newName: String) {
        try {
            val file = File(docsDir(context), SEARCH_INDEX_FILE)
            if (!file.exists()) return
            val json = org.json.JSONObject(file.readText())
            val entry = json.opt(oldName)
            json.remove(oldName)
            if (entry != null) json.put(newName, entry)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update search index", e)
        }
    }

    /** Check if the search index needs rebuilding (file missing, stale, or wrong format). */
    fun ensureSearchIndex(context: Context) {
        val indexFile = File(docsDir(context), SEARCH_INDEX_FILE)
        if (!indexFile.exists()) {
            rebuildSearchIndex(context)
            return
        }
        // Check if any documents are missing from the index
        try {
            val json = org.json.JSONObject(indexFile.readText())
            val docFiles = docsDir(context).listFiles()
                ?.filter {
                    it.extension == INKUP_EXT ||
                    (it.extension == "json" && it.name != SEARCH_INDEX_FILE)
                }
                ?: emptyList()
            val needsRebuild = docFiles.any { !json.has(it.nameWithoutExtension) }
                || docFiles.any { json.opt(it.nameWithoutExtension) is String } // old flat format
            if (needsRebuild) rebuildSearchIndex(context)
        } catch (_: Exception) {
            rebuildSearchIndex(context)
        }
    }

    /** Rebuild the search index from all document files. Called once if index is missing. */
    fun rebuildSearchIndex(context: Context) {
        try {
            val dir = docsDir(context)
            val json = org.json.JSONObject()
            val seen = mutableSetOf<String>()
            // Index .inkup files first (preferred format)
            dir.listFiles()?.filter { it.extension == INKUP_EXT }?.forEach { file ->
                val name = file.nameWithoutExtension
                seen.add(name)
                val linesJson = extractLineIndexFromInkupFile(file)
                json.put(name, linesJson)
            }
            // Then legacy .json files (only if not already indexed via .inkup)
            dir.listFiles()?.filter { it.extension == "json" && it.name != SEARCH_INDEX_FILE && it.nameWithoutExtension !in seen }?.forEach { file ->
                val linesJson = extractLineIndexFromFile(file)
                json.put(file.nameWithoutExtension, linesJson)
            }
            File(dir, SEARCH_INDEX_FILE).writeText(json.toString())
            Log.i(TAG, "Rebuilt search index with ${json.length()} documents")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rebuild search index", e)
        }
    }

    /** Extract per-line recognized text from a protobuf .inkup file. */
    private fun extractLineIndexFromInkupFile(file: java.io.File): org.json.JSONObject {
        val linesJson = org.json.JSONObject()
        try {
            val doc = DocumentProto.ADAPTER.decode(file.readBytes())
            doc.main?.let { main ->
                for ((key, value) in main.line_text_cache) {
                    if (value.isNotEmpty()) linesJson.put("$key", value.lowercase())
                }
            }
            doc.cue?.let { cue ->
                for ((key, value) in cue.line_text_cache) {
                    if (value.isNotEmpty()) linesJson.put("c$key", value.lowercase())
                }
            }
        } catch (_: Exception) {}
        return linesJson
    }

    /** Extract per-line recognized text from a document JSON file. */
    private fun extractLineIndexFromFile(file: java.io.File): org.json.JSONObject {
        val linesJson = org.json.JSONObject()
        try {
            val json = org.json.JSONObject(file.readText())
            val mainJson = json.optJSONObject("main")
            if (mainJson != null) {
                extractTextCacheToLines(mainJson.optJSONObject("lineTextCache"), "", linesJson)
            } else {
                extractTextCacheToLines(json.optJSONObject("lineTextCache"), "", linesJson)
            }
            val cueJson = json.optJSONObject("cue")
            if (cueJson != null) {
                extractTextCacheToLines(cueJson.optJSONObject("lineTextCache"), "c", linesJson)
            }
        } catch (_: Exception) {}
        return linesJson
    }

    private fun extractTextCacheToLines(
        cacheObj: org.json.JSONObject?, prefix: String, out: org.json.JSONObject
    ) {
        if (cacheObj == null) return
        for (key in cacheObj.keys()) {
            val text = cacheObj.optString(key, "")
            if (text.isNotEmpty()) out.put("$prefix$key", text.lowercase())
        }
    }

    fun delete(context: Context, name: String): Boolean {
        val inkup = inkupFile(context, name)
        val json = docFile(context, name)
        // AtomicFile.delete() removes the base file and its .new backup
        AtomicFile(inkup).delete()
        val deletedJson = json.delete()
        val deletedAny = !inkup.exists() || deletedJson
        if (deletedAny) removeFromSearchIndex(context, name)
        return deletedAny
    }

    /** Check if a document exists in any format (.inkup, .inkup.new backup, or .json). */
    private fun docExists(context: Context, name: String): Boolean {
        val inkup = inkupFile(context, name)
        return inkup.exists() ||
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
        // Rename .inkup file if it exists, else .json
        val oldInkup = inkupFile(context, oldName)
        val oldJson = docFile(context, oldName)
        val source = when {
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
            renameInSearchIndex(context, oldName, newName)
        }
        return renamed
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

            // Write .inkup file (protobuf binary)
            val inkupFileName = "$name.$INKUP_EXT"
            val existingInkup = folder.findFile(inkupFileName)
            val inkupSyncFile = existingInkup
                ?: folder.createFile("application/octet-stream", inkupFileName)
            if (inkupSyncFile != null) {
                context.contentResolver.openOutputStream(inkupSyncFile.uri, "wt")?.use { out ->
                    out.write(data.toProto().encode())
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
     * Restore documents from the sync folder. Reads .writer (JSON) and .inkup (protobuf)
     * files and saves them as local documents, skipping any that already exist.
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
                fileName.endsWith(".$INKUP_EXT") -> {
                    name = fileName.removeSuffix(".$INKUP_EXT")
                    data = try {
                        val bytes = context.contentResolver.openInputStream(file.uri)
                            ?.readBytes() ?: continue
                        DocumentProto.ADAPTER.decode(bytes).toDomain()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read .inkup file: $fileName", e)
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
