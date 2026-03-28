package com.writer.ui.writing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Debug ContentProvider that serves the latest bug report file via:
 *   adb shell content read --uri content://com.writer.dev.debug/bugreport
 *
 * Pair with the broadcast receiver to generate remotely:
 *   adb shell am broadcast -W -a com.writer.dev.GENERATE_BUG_REPORT
 */
class DebugBugReportProvider : ContentProvider() {

    companion object {
        private const val TAG = "DebugBugReport"
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.path != "/bugreport") return null

        val ctx = context ?: return null
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "bug-reports")
        val latest = dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.lastModified() }

        if (latest == null) {
            Log.w(TAG, "No bug reports found in ${dir.absolutePath}")
            return null
        }

        return ParcelFileDescriptor.open(latest, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, proj: Array<out String>?, sel: String?,
                       selArgs: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, selArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?,
                        sel: String?, selArgs: Array<out String>?): Int = 0
}
