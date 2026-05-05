package com.writer.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object TextRecognizerFactory {
    /** @param onWedge fires when the Onyx HWR service stops responding and we
     *                 swap to ML Kit. Host UI uses this to surface a banner. */
    fun create(context: Context, onWedge: (() -> Unit)? = null): TextRecognizer {
        if (!isOnyxHwrAvailable(context)) return GoogleMLKitTextRecognizer()
        val onyx = OnyxHwrTextRecognizer(context.applicationContext)
        val wrapper = FallbackingTextRecognizer(
            primary = onyx,
            fallbackFactory = { GoogleMLKitTextRecognizer() },
            onWedge = onWedge,
        )
        onyx.onServiceUnresponsive = { wrapper.reportPrimaryUnresponsive() }
        return wrapper
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
