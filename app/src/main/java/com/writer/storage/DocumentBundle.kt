package com.writer.storage

import com.writer.model.DocumentData
import com.writer.model.proto.DocumentProto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Read/write result: a document plus any audio files bundled alongside it.
 */
data class BundleResult(
    val data: DocumentData,
    val audioFiles: Map<String, ByteArray> = emptyMap()
)

/**
 * ZIP-based document bundle I/O.
 *
 * The `.mok` file is a ZIP container:
 * ```
 * document.pb          ← protobuf binary
 * audio/rec-001.opus   ← optional audio files
 * audio/rec-002.opus
 * ```
 *
 * Legacy `.inkup` files are raw protobuf (no ZIP wrapper).
 * [isZipFormat] detects format via magic bytes.
 */
object DocumentBundle {

    private const val DOCUMENT_ENTRY = "document.pb"
    private const val AUDIO_PREFIX = "audio/"

    /** Check if bytes start with ZIP magic (`PK` = 0x50 0x4B). */
    fun isZipFormat(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** Write a ZIP bundle to [out]. */
    fun writeZip(
        out: OutputStream,
        data: DocumentData,
        audioFiles: Map<String, ByteArray> = emptyMap()
    ) {
        val protoBytes = data.toProto().encode()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DOCUMENT_ENTRY))
            zip.write(protoBytes)
            zip.closeEntry()

            for ((name, bytes) in audioFiles) {
                zip.putNextEntry(ZipEntry("$AUDIO_PREFIX$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Read a ZIP bundle from [input]. */
    fun readZip(input: InputStream): BundleResult {
        var documentData: DocumentData? = null
        val audioFiles = mutableMapOf<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == DOCUMENT_ENTRY -> {
                        val bytes = zip.readBytes()
                        documentData = DocumentProto.ADAPTER.decode(bytes).toDomain()
                    }
                    entry.name.startsWith(AUDIO_PREFIX) && !entry.isDirectory -> {
                        val audioName = entry.name.removePrefix(AUDIO_PREFIX)
                        if (audioName.contains("..") || audioName.contains("/") || audioName.contains("\\") || java.io.File(audioName).name != audioName) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        audioFiles[audioName] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return BundleResult(
            data = documentData ?: DocumentData(main = com.writer.model.ColumnData()),
            audioFiles = audioFiles
        )
    }

    /** Read legacy raw protobuf bytes (no ZIP wrapper). */
    fun readLegacyProtobuf(bytes: ByteArray): BundleResult {
        val data = DocumentProto.ADAPTER.decode(bytes).toDomain()
        return BundleResult(data = data)
    }

    /** Auto-detect format and read. */
    fun read(bytes: ByteArray): BundleResult = if (isZipFormat(bytes)) {
        readZip(ByteArrayInputStream(bytes))
    } else {
        readLegacyProtobuf(bytes)
    }
}
