package com.writer.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object TextRecognizerFactory {
    fun create(context: Context): TextRecognizer {
        if (isOnyxHwrAvailable(context)) return OnyxHwrTextRecognizer(context.applicationContext)
        return GoogleMLKitTextRecognizer()
    }

    @Suppress("DEPRECATION")
    private fun isOnyxHwrAvailable(context: Context): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveService(
                intent, PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            context.packageManager.resolveService(intent, 0)
        } != null
    }
}
