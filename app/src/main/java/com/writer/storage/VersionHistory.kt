package com.writer.storage

import org.automerge.ChangeHash
import org.automerge.Document

/**
 * Checkpoint/restore for Automerge documents. Each checkpoint captures
 * the document heads at a point in time so users can navigate back.
 */
class VersionHistory {

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

    private val checkpoints = mutableListOf<Checkpoint>()

    fun createCheckpoint(doc: Document, label: String): Checkpoint {
        val cp = Checkpoint(
            label = label,
            timestamp = System.currentTimeMillis(),
            heads = doc.heads,
        )
        checkpoints.add(cp)
        return cp
    }

    fun listCheckpoints(): List<Checkpoint> = checkpoints.toList()

    /**
     * Fork the document at the given checkpoint's heads, returning a new
     * [Document] representing the state at that point in time.
     */
    fun restoreCheckpoint(doc: Document, checkpoint: Checkpoint): Document {
        return doc.fork(checkpoint.heads)
    }
}
