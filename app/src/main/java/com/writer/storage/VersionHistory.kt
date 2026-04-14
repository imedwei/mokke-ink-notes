package com.writer.storage

import org.automerge.ChangeHash
import org.automerge.ChangeHashFactory
import org.automerge.Document
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Checkpoint/restore for Automerge documents. Each checkpoint captures
 * the document heads at a point in time so users can navigate back.
 *
 * Checkpoints are persisted per document as JSON files in [historyDir].
 */
class VersionHistory(private val historyDir: File) {

    data class Checkpoint(
        val label: String,
        val timestamp: Long,
        val heads: Array<ChangeHash>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Checkpoint) return false
            return label == other.label && timestamp == other.timestamp && heads.contentEquals(other.heads)
        }

        override fun hashCode(): Int {
            var result = label.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + heads.contentHashCode()
            return result
        }
    }

    fun createCheckpoint(docName: String, doc: Document, label: String): Checkpoint {
        val cp = Checkpoint(
            label = label,
            timestamp = System.currentTimeMillis(),
            heads = doc.heads,
        )
        val list = loadFromDisk(docName).toMutableList()
        list.add(cp)
        saveToDisk(docName, list)
        return cp
    }

    fun listCheckpoints(docName: String): List<Checkpoint> = loadFromDisk(docName)

    fun deleteCheckpoints(docName: String) {
        checkpointFile(docName).delete()
    }

    /**
     * Fork the document at the given checkpoint's heads, returning a new
     * [Document] representing the state at that point in time.
     */
    fun restoreCheckpoint(doc: Document, checkpoint: Checkpoint): Document {
        return doc.fork(checkpoint.heads)
    }

    // --- Persistence ---

    private fun checkpointFile(docName: String): File =
        File(historyDir, "$docName.checkpoints.json")

    private fun loadFromDisk(docName: String): List<Checkpoint> {
        val file = checkpointFile(docName)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                val headsArray = obj.getJSONArray("heads")
                val heads = (0 until headsArray.length()).map { j ->
                    ChangeHashFactory.create(hexToBytes(headsArray.getString(j)))
                }.toTypedArray()
                Checkpoint(
                    label = obj.getString("label"),
                    timestamp = obj.getLong("timestamp"),
                    heads = heads,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveToDisk(docName: String, checkpoints: List<Checkpoint>) {
        historyDir.mkdirs()
        val json = JSONArray()
        for (cp in checkpoints) {
            val obj = JSONObject()
            obj.put("label", cp.label)
            obj.put("timestamp", cp.timestamp)
            val headsArray = JSONArray()
            for (head in cp.heads) {
                headsArray.put(bytesToHex(head.bytes))
            }
            obj.put("heads", headsArray)
            json.put(obj)
        }
        checkpointFile(docName).writeText(json.toString(2))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
