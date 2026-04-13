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

        // URL.openStream() follows redirects automatically (including cross-domain).
        // We use it for the actual download. For progress reporting, we first do a
        // HEAD-like probe to get Content-Length from the final URL.
        val downloadUrl = "$baseUrl/$name"
        val totalBytes = getContentLength(downloadUrl)
        Log.i(TAG, "Downloading $name ($totalBytes bytes)...")

        URL(downloadUrl).openStream().use { input ->
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
                Log.i(TAG, "Downloaded $downloaded bytes to ${tmpFile.name}")
            }
        }
        if (!isValidModelFile(tmpFile, name)) {
            val size = tmpFile.length()
            // Log the first 4 bytes for debugging
            val header = ByteArray(4)
            if (size > 0) tmpFile.inputStream().use { it.read(header) }
            val magic = String(header, Charsets.US_ASCII)
            Log.e(TAG, "Validation failed for $name: size=$size, magic='$magic', expected ORT='ORTM'")
            tmpFile.delete()
            throw IllegalStateException("Downloaded $name is invalid ($size bytes, magic='$magic')")
        }
        if (!tmpFile.renameTo(destFile)) {
            // renameTo can fail silently — fall back to copy+delete
            tmpFile.copyTo(destFile, overwrite = true)
            tmpFile.delete()
        }
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

    /** Get Content-Length by following redirects to the final URL. Returns -1 if unknown. */
    private fun getContentLength(url: String): Long {
        try {
            var currentUrl = url
            for (i in 0 until MAX_REDIRECTS) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = 5_000
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (code in 301..308) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    currentUrl = if (location.startsWith("http")) location
                                 else URL(URL(currentUrl), location).toString()
                    continue
                }
                val length = conn.contentLength.toLong()
                conn.disconnect()
                return length
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get content length: ${e.message}")
        }
        return -1
    }

    /** Minimum valid sizes — a truncated or redirect-page file will be smaller. */
    private val MIN_FILE_SIZES = mapOf(
        "encoder" to 25_000_000L,
        "decoder" to 200_000L,
        "joiner"  to 100_000L,
        "tokens"  to 4_000L
    )

    /** Validate a model file: check size and format header. */
    fun isValidModelFile(file: File, name: String): Boolean {
        val prefix = name.substringBefore("-").substringBefore(".")
        val minSize = MIN_FILE_SIZES[prefix] ?: 0L
        if (file.length() < minSize) return false
        if (name.endsWith(".ort") || name.endsWith(".onnx")) {
            // ORT format: 4-byte size prefix + "ORTM" magic at offset 4
            // ONNX format: protobuf, starts with 0x08 at offset 0
            val header = ByteArray(8)
            file.inputStream().use { it.read(header) }
            val magicAt4 = String(header, 4, 4, Charsets.US_ASCII)
            val isOrt = magicAt4 == "ORTM"
            val isOnnx = header[0] == 0x08.toByte()
            if (!isOrt && !isOnnx) return false
        }
        return true
    }
}
