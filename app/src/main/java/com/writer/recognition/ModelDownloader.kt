package com.writer.recognition

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared utilities for downloading and validating speech recognition model files.
 *
 * Used by [SherpaModelManager] (streaming) and [OfflineModelManager] (offline).
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000

    /**
     * Download a file with manual redirect following and progress reporting.
     *
     * HuggingFace serves files via multiple redirects (307 → 302 → 200) across
     * domains. Android's [HttpURLConnection] doesn't follow cross-domain redirects,
     * so we handle them manually.
     *
     * Downloads to a `.tmp` file first, validates, then atomically renames.
     */
    fun downloadWithProgress(
        baseUrl: String,
        name: String,
        destFile: File,
        onProgress: ((String) -> Unit)? = null
    ) {
        Log.i(TAG, "Downloading $name from $baseUrl...")
        val label = name.substringBefore(".")
        onProgress?.invoke("Downloading $label…")

        val tmpFile = File(destFile.parentFile, "$name.tmp")

        // Follow redirects manually
        var url = "$baseUrl/$name"
        var totalBytes = -1L
        var finalConn: HttpURLConnection? = null
        for (i in 0 until MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                url = if (location.startsWith("http")) location
                       else URL(URL(url), location).toString()
                continue
            }
            totalBytes = conn.contentLength.toLong()
            finalConn = conn
            break
        }
        val conn = finalConn ?: throw IllegalStateException("Too many redirects for $name")

        try {
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var downloaded = 0L
                    var lastReportPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (totalBytes > 0) {
                            val pct = (downloaded * 100 / totalBytes).toInt()
                            if (pct != lastReportPct && pct % 10 == 0) {
                                lastReportPct = pct
                                onProgress?.invoke("Downloading $label… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!isValidModelFile(tmpFile, name)) {
            tmpFile.delete()
            throw IllegalStateException("Downloaded $name is invalid (${tmpFile.length()} bytes)")
        }
        tmpFile.renameTo(destFile)
        Log.i(TAG, "Downloaded $name (${destFile.length()} bytes)")
    }

    /**
     * Ensure all model files exist and are valid. Downloads missing or corrupt files.
     */
    fun ensureModelFiles(
        dir: File,
        baseUrl: String,
        fileNames: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): File {
        dir.mkdirs()
        for (name in fileNames) {
            val file = File(dir, name)
            if (file.exists() && isValidModelFile(file, name)) continue
            if (file.exists()) {
                Log.w(TAG, "Deleting invalid model file $name (${file.length()} bytes)")
                file.delete()
            }
            downloadWithProgress(baseUrl, name, file, onProgress)
        }
        return dir
    }

    /** Minimum valid sizes — a truncated or redirect-page file will be smaller. */
    private val MIN_FILE_SIZES = mapOf(
        "encoder" to 25_000_000L,
        "decoder" to 200_000L,
        "joiner"  to 100_000L,
        "tokens"  to 4_000L
    )

    /** Validate a model file: check size and magic bytes. */
    fun isValidModelFile(file: File, name: String): Boolean {
        val prefix = name.substringBefore("-").substringBefore(".")
        val minSize = MIN_FILE_SIZES[prefix] ?: 0L
        if (file.length() < minSize) return false
        if (name.endsWith(".ort") || name.endsWith(".onnx")) {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            val magic = String(header, Charsets.US_ASCII)
            if (name.endsWith(".ort") && magic != "ORTM") return false
            if (name.endsWith(".onnx") && header[0] != 0x08.toByte()) return false
        }
        return true
    }
}
