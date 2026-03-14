package com.writer.recognition

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.writer.model.InkLine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected test that exercises the full text recognition pipeline on a Boox device:
 * service binding → initialization → protobuf encoding → SharedMemory IPC → result parsing.
 *
 * Skipped on non-Boox devices where KHwrService is unavailable.
 */
@RunWith(AndroidJUnit4::class)
class OnyxHwrTextRecognizerTest {

    private lateinit var recognizer: OnyxHwrTextRecognizer
    private var hwrAvailable = false

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        hwrAvailable = isHwrServiceAvailable(context)
        assumeTrue("KHwrService not available — skipping on non-Boox device", hwrAvailable)
        recognizer = OnyxHwrTextRecognizer(context)
    }

    @After
    fun tearDown() {
        if (hwrAvailable) {
            recognizer.close()
        }
    }

    @Test
    fun initialize_bindsAndActivates() = runBlocking {
        recognizer.initialize("en-US")
    }

    @Test
    fun recognizeLine_emptyStrokes_returnsEmpty() = runBlocking {
        recognizer.initialize("en-US")
        val line = InkLine(emptyList(), RectF())
        val result = recognizer.recognizeLine(line, "")
        assertTrue("Empty strokes should return empty string", result.isEmpty())
    }

    @Test
    fun recognizeLine_capturedHelloTest_recognizesCorrectly() = runBlocking {
        recognizer.initialize("en-US")
        val fixture = FixtureLoader.load("hello_test")
        assertEquals(fixture.expectedText, recognizer.recognizeLine(fixture.inkLine, ""))
    }

    // --- Helpers ---

    private fun isHwrServiceAvailable(context: android.content.Context): Boolean {
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
