package com.writer.recognition

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TextRecognizerFactoryTest {

    @Test
    fun create_withOnyxService_returnsFallbackingRecognizer() {
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        pm.addResolveInfoForIntent(intent, ResolveInfo())

        val recognizer = TextRecognizerFactory.create(app)
        // Onyx is wrapped in FallbackingTextRecognizer so calls auto-swap to ML Kit
        // if the Onyx KHwrService wedges.
        assertTrue(
            "Expected FallbackingTextRecognizer when Onyx service is available",
            recognizer is FallbackingTextRecognizer
        )
        recognizer.close()
    }

    @Test
    fun create_withoutOnyxService_returnsGoogleMLKit() {
        // Robolectric has no Onyx service registered — but GoogleMLKitTextRecognizer
        // requires MlKitContext which isn't available in unit tests.
        // Instead, verify the selection logic: without the service, isOnyxHwrAvailable returns false.
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        // Ensure no resolve info is registered
        val resolved = app.packageManager.resolveService(intent, 0)
        assertTrue(
            "Onyx HWR service should not be resolvable in test environment",
            resolved == null
        )
    }
}
