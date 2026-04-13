package com.writer.recognition

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SherpaModelManagerTest {

    private lateinit var manager: SherpaModelManager

    /** Minimal fake — just needs to be a valid StreamingRecognizer instance. */
    private fun fakeRecognizer(): StreamingRecognizer = object : StreamingRecognizer {
        override fun createStream() = object : RecognizerStream {
            override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {}
            override fun inputFinished() {}
            override fun release() {}
        }
        override fun isReady(stream: RecognizerStream) = false
        override fun decode(stream: RecognizerStream) {}
        override fun isEndpoint(stream: RecognizerStream) = false
        override fun getResult(stream: RecognizerStream) = RecognitionResult("")
        override fun reset(stream: RecognizerStream) {}
        override fun release() {}
    }

    @Before
    fun setUp() {
        manager = SherpaModelManager()
    }

    @Test
    fun `initial state is UNLOADED`() {
        assertEquals(SherpaModelManager.State.UNLOADED, manager.state)
    }

    @Test
    fun `getRecognizer returns null when unloaded`() {
        assertNull(manager.getRecognizer())
    }

    @Test
    fun `release from UNLOADED is safe`() {
        manager.release() // should not throw
        assertEquals(SherpaModelManager.State.UNLOADED, manager.state)
    }

    @Test
    fun `loadWithFactory transitions to READY`() {
        val fake = fakeRecognizer()
        manager.loadWithFactory { fake }
        assertEquals(SherpaModelManager.State.READY, manager.state)
    }

    @Test
    fun `getRecognizer returns non-null when READY`() {
        manager.loadWithFactory { fakeRecognizer() }
        assertNotNull(manager.getRecognizer())
    }

    @Test
    fun `release transitions back to UNLOADED`() {
        manager.loadWithFactory { fakeRecognizer() }
        manager.release()
        assertEquals(SherpaModelManager.State.UNLOADED, manager.state)
        assertNull(manager.getRecognizer())
    }

    @Test
    fun `double load is no-op when READY`() {
        val first = fakeRecognizer()
        manager.loadWithFactory { first }
        manager.loadWithFactory { fakeRecognizer() } // should not replace
        assertSame(first, manager.getRecognizer())
    }

    @Test
    fun `factory error transitions to ERROR`() {
        manager.loadWithFactory { throw RuntimeException("model broken") }
        assertEquals(SherpaModelManager.State.ERROR, manager.state)
        assertNull(manager.getRecognizer())
    }

    @Test
    fun `can reload after ERROR`() {
        manager.loadWithFactory { throw RuntimeException("fail") }
        assertEquals(SherpaModelManager.State.ERROR, manager.state)

        val good = fakeRecognizer()
        manager.loadWithFactory { good }
        assertEquals(SherpaModelManager.State.READY, manager.state)
        assertSame(good, manager.getRecognizer())
    }

    @Test
    fun `release after ERROR is safe`() {
        manager.loadWithFactory { throw RuntimeException("fail") }
        manager.release()
        assertEquals(SherpaModelManager.State.UNLOADED, manager.state)
    }

    @Test
    fun `onReady callback fires when transitioning to READY`() {
        var called = false
        manager.onReady = { called = true }
        manager.loadWithFactory { fakeRecognizer() }
        assertTrue(called)
    }

    @Test
    fun `onReady callback does not fire on error`() {
        var called = false
        manager.onReady = { called = true }
        manager.loadWithFactory { throw RuntimeException("fail") }
        assertFalse(called)
    }

    // ── Model file validation ──────────────────────────────────────

    @Test
    fun `isValidModelFile rejects empty file`() {
        val file = java.io.File.createTempFile("test", ".ort")
        try {
            file.writeBytes(ByteArray(0))
            assertFalse(ModelDownloader.isValidModelFile(file, "encoder-foo.ort"))
        } finally { file.delete() }
    }

    @Test
    fun `isValidModelFile rejects small file`() {
        val file = java.io.File.createTempFile("test", ".ort")
        try {
            file.writeBytes(ByteArray(100)) // way too small for an encoder
            assertFalse(ModelDownloader.isValidModelFile(file, "encoder-foo.ort"))
        } finally { file.delete() }
    }

    @Test
    fun `isValidModelFile rejects HTML redirect page`() {
        val file = java.io.File.createTempFile("test", ".ort")
        try {
            // HuggingFace redirect: starts with "<" not "ORTM"
            val html = "<html><body>Redirecting...</body></html>"
            file.writeText(html.repeat(10000)) // make it large enough to pass size check
            assertFalse(ModelDownloader.isValidModelFile(file, "joiner-foo.ort"))
        } finally { file.delete() }
    }

    @Test
    fun `isValidModelFile accepts valid ORT header`() {
        val file = java.io.File.createTempFile("test", ".ort")
        try {
            // ORT magic + padding to pass size check for tokens
            val data = ByteArray(5000)
            data[0] = 'O'.code.toByte()
            data[1] = 'R'.code.toByte()
            data[2] = 'T'.code.toByte()
            data[3] = 'M'.code.toByte()
            file.writeBytes(data)
            assertTrue(ModelDownloader.isValidModelFile(file, "tokens.txt"))
        } finally { file.delete() }
    }

    @Test
    fun `isValidModelFile accepts tokens txt`() {
        val file = java.io.File.createTempFile("test", ".txt")
        try {
            val tokens = (0..500).joinToString("\n") { "$it ▁token$it" }
            file.writeText(tokens)
            assertTrue(ModelDownloader.isValidModelFile(file, "tokens.txt"))
        } finally { file.delete() }
    }
}
