package com.writer.storage

import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed blob store for audio files. Files are stored by SHA-256 hash,
 * providing deduplication and sync-friendly references.
 */
class AudioBlobStore(private val blobDir: File) {

    /** Store bytes and return the SHA-256 hex hash. Deduplicates automatically. */
    fun store(bytes: ByteArray): String {
        val hash = sha256Hex(bytes)
        val file = blobFile(hash)
        if (!file.exists()) {
            blobDir.mkdirs()
            file.writeBytes(bytes)
        }
        return hash
    }

    fun load(hash: String): ByteArray? {
        val file = blobFile(hash)
        return if (file.exists()) file.readBytes() else null
    }

    fun exists(hash: String): Boolean = blobFile(hash).exists()

    fun delete(hash: String) {
        blobFile(hash).delete()
    }

    /** Remove blobs not in [referencedHashes]. */
    fun garbageCollect(referencedHashes: Set<String>) {
        if (!blobDir.isDirectory) return
        blobDir.listFiles()?.forEach { file ->
            if (file.isFile && file.nameWithoutExtension !in referencedHashes) {
                file.delete()
            }
        }
    }

    /** Resolve a blob hash to a file for playback. Returns null if not found. */
    fun toFile(hash: String): File? {
        val file = blobFile(hash)
        return if (file.exists()) file else null
    }

    private fun blobFile(hash: String): File = File(blobDir, "$hash.blob")

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val HASH_REGEX = Regex("^[0-9a-f]{64}$")

        /** True if [ref] is a SHA-256 content hash (64 hex chars), not a filename. */
        fun isContentHash(ref: String): Boolean = HASH_REGEX.matches(ref)
    }
}
