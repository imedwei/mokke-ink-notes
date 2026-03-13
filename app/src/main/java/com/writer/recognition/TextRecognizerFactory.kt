package com.writer.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TextRecognizerFactory {
    fun create(context: Context): TextRecognizer {
        if (isOnyxHwrAvailable(context)) return OnyxHwrTextRecognizer(context.applicationContext)
        return GoogleMLKitTextRecognizer()
    }

    private fun isOnyxHwrAvailable(context: Context): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        return context.packageManager.resolveService(
            intent, PackageManager.ResolveInfoFlags.of(0)
        ) != null
    }
}
