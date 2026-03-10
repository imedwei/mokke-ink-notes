package com.writer

import android.app.Application
import android.os.Build
import android.util.Log
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
    }
}
