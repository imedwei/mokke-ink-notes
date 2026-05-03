package com.writer

import android.app.Application
import android.os.Build
import android.util.Log
import com.writer.ui.writing.PerfCounters
import com.writer.view.ScreenMetrics
import org.lsposed.hiddenapibypass.HiddenApiBypass

class WriterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenMetrics.init(resources.displayMetrics, resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
            Log.i("WriterApplication", "Hidden API bypass applied")
        }
        // Route inksdk's pen/event/paint metrics into mokke's PerfCounters
        // so bug reports and the PerfDump logcat see one unified label table.
        com.inksdk.ink.PerfCounters.sink = com.inksdk.ink.PerfSink { label, nanos ->
            PerfCounters.recordByLabel(label, nanos)
        }
    }
}
