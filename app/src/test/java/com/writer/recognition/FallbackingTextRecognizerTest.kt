package com.writer.recognition

import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [FallbackingTextRecognizer]: while the primary is healthy, calls
 * route to the primary; once the primary reports itself unresponsive, calls
 * route to a lazily-initialized fallback and the wedge callback fires once.
 */
class FallbackingTextRecognizerTest {

    private class FakeRecognizer(
        val name: String,
        val response: String = "$name-result",
    ) : TextRecognizer {
        var initialized = false
        var initializeCount = 0
        var lastLanguageTag: String? = null
        var recognizeCount = 0
        var closed = false

        override suspend fun initialize(languageTag: String) {
            initialized = true
            initializeCount++
            lastLanguageTag = languageTag
        }

        override suspend fun recognizeLine(line: InkLine, preContext: String): String {
            check(initialized) { "$name not initialized" }
            recognizeCount++
            return response
        }

        override fun close() { closed = true }
    }

    private fun line() = InkLine.build(
        listOf(InkStroke(points = listOf(StrokePoint(0f, 0f, 0.5f, 0L))))
    )

    @Test
    fun routesToPrimary_whenHealthy() = runBlocking {
        val primary = FakeRecognizer("primary")
        val fallback = FakeRecognizer("fallback")
        val wrapper = FallbackingTextRecognizer(primary, fallbackFactory = { fallback })
        wrapper.initialize("en-US")

        val result = wrapper.recognizeLine(line(), "")

        assertEquals("primary-result", result)
        assertEquals(1, primary.recognizeCount)
        assertEquals(0, fallback.recognizeCount)
        assertFalse("fallback should not init while primary healthy", fallback.initialized)
    }

    @Test
    fun swapsToFallback_afterUnresponsiveReport() = runBlocking {
        val primary = FakeRecognizer("primary")
        val fallback = FakeRecognizer("fallback")
        val wrapper = FallbackingTextRecognizer(primary, fallbackFactory = { fallback })
        wrapper.initialize("en-US")
        wrapper.recognizeLine(line(), "")  // primary handled

        wrapper.reportPrimaryUnresponsive()
        val result = wrapper.recognizeLine(line(), "")

        assertEquals("fallback-result", result)
        assertEquals(1, primary.recognizeCount)  // unchanged after wedge
        assertEquals(1, fallback.recognizeCount)
        assertTrue("fallback should init lazily on first wedge call", fallback.initialized)
        assertEquals("en-US", fallback.lastLanguageTag)
    }

    @Test
    fun fallbackInitializedOnceAcrossManyCalls() = runBlocking {
        val primary = FakeRecognizer("primary")
        val fallback = FakeRecognizer("fallback")
        val factoryInvocations = intArrayOf(0)
        val wrapper = FallbackingTextRecognizer(primary, fallbackFactory = {
            factoryInvocations[0]++; fallback
        })
        wrapper.initialize("en-US")
        wrapper.reportPrimaryUnresponsive()

        repeat(5) { wrapper.recognizeLine(line(), "") }

        assertEquals(1, factoryInvocations[0])
        assertEquals(1, fallback.initializeCount)
        assertEquals(5, fallback.recognizeCount)
    }

    @Test
    fun onWedgeCallback_firesExactlyOnce() = runBlocking {
        val primary = FakeRecognizer("primary")
        val fallback = FakeRecognizer("fallback")
        var fired = 0
        val wrapper = FallbackingTextRecognizer(
            primary, fallbackFactory = { fallback }, onWedge = { fired++ }
        )

        wrapper.reportPrimaryUnresponsive()
        wrapper.reportPrimaryUnresponsive()
        wrapper.reportPrimaryUnresponsive()

        assertEquals(1, fired)
    }

    @Test
    fun close_closesBothPrimaryAndInitializedFallback() = runBlocking {
        val primary = FakeRecognizer("primary")
        val fallback = FakeRecognizer("fallback")
        val wrapper = FallbackingTextRecognizer(primary, fallbackFactory = { fallback })
        wrapper.initialize("en-US")
        wrapper.reportPrimaryUnresponsive()
        wrapper.recognizeLine(line(), "")  // forces fallback init

        wrapper.close()

        assertTrue(primary.closed)
        assertTrue(fallback.closed)
    }

    @Test
    fun close_doesNotCloseFallback_ifNeverInitialized() = runBlocking {
        val primary = FakeRecognizer("primary")
        val fallback = FakeRecognizer("fallback")
        val wrapper = FallbackingTextRecognizer(primary, fallbackFactory = { fallback })
        wrapper.initialize("en-US")
        wrapper.recognizeLine(line(), "")  // primary handled

        wrapper.close()

        assertTrue(primary.closed)
        assertFalse(fallback.closed)
    }
}
