package com.writer.recognition

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests [SherpaTranscriber] with injected [StreamingRecognizer], [PcmSource],
 * and main thread executor. No Android dependencies — pure JVM.
 *
 * Key regression: finals detected on the recording thread just before stop()
 * must be delivered synchronously in stop(), not dropped because a postMain
 * callback arrived after the caller cleared lectureMode.
 */
class SherpaTranscriberTest {

    @get:Rule val timeout = Timeout(10, TimeUnit.SECONDS)

    private lateinit var modelManager: SherpaModelManager
    private val postedRunnables = ConcurrentLinkedQueue<Runnable>()

    /** Captures postMain runnables instead of running them, so we can control timing. */
    private val capturingExecutor: (Runnable) -> Unit = { postedRunnables.add(it) }

    /** Runs postMain runnables immediately on the calling thread. */
    private val immediateExecutor: (Runnable) -> Unit = { it.run() }

    @Before
    fun setUp() {
        modelManager = SherpaModelManager()
        postedRunnables.clear()
    }

    /**
     * Fake recognizer that triggers an endpoint after [endpointAfterChunks] chunks.
     */
    private class FakeRecognizer(
        private val endpointAfterChunks: Int = 3,
        private val endpointText: String = "HELLO WORLD",
        private val endpointTokens: Array<String> = arrayOf(" HELLO", " WORLD"),
        private val endpointTimestamps: FloatArray = floatArrayOf(0.5f, 1.0f)
    ) : StreamingRecognizer {
        var chunksReceived = 0
        private var decoded = false // tracks whether decode() has consumed the buffered audio

        override fun createStream() = object : RecognizerStream {
            override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
                chunksReceived++
                decoded = false // new audio available
            }
            override fun inputFinished() {}
            override fun release() {}
        }

        override fun isReady(stream: RecognizerStream) = !decoded && chunksReceived > 0
        override fun decode(stream: RecognizerStream) { decoded = true }
        override fun isEndpoint(stream: RecognizerStream) = chunksReceived >= endpointAfterChunks

        override fun getResult(stream: RecognizerStream) = RecognitionResult(
            text = if (chunksReceived >= endpointAfterChunks) endpointText else "",
            tokens = if (chunksReceived >= endpointAfterChunks) endpointTokens else emptyArray(),
            timestamps = if (chunksReceived >= endpointAfterChunks) endpointTimestamps else floatArrayOf()
        )

        override fun reset(stream: RecognizerStream) { chunksReceived = 0 }
        override fun release() {}
    }

    /** Produces silence for N reads then blocks until stop(). */
    private class FakePcmSource(private val chunksToEmit: Int) : SherpaTranscriber.PcmSource {
        private val doneLatch = CountDownLatch(1)
        var chunksRead = 0

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            if (chunksRead >= chunksToEmit) {
                doneLatch.await(5, TimeUnit.SECONDS)
                return 0
            }
            chunksRead++
            buffer.fill(0, offset, (offset + size).coerceAtMost(buffer.size))
            return size
        }

        override fun stop() { doneLatch.countDown() }
        override fun release() {}
    }

    private fun createTranscriber(
        recognizer: StreamingRecognizer,
        pcmSource: SherpaTranscriber.PcmSource,
        executor: (Runnable) -> Unit = capturingExecutor,
        offlineRecognizer: OfflineRecognizer? = null
    ): SherpaTranscriber {
        modelManager.loadWithFactory { recognizer }
        val offlineMgr = if (offlineRecognizer != null) {
            OfflineModelManager().also { it.loadWithFactory { offlineRecognizer } }
        } else null
        return SherpaTranscriber(
            context = null, // not used when pcmSourceFactory is provided
            modelManager = modelManager,
            pcmSourceFactory = { pcmSource },
            mainThreadExecutor = executor,
            offlineModelManager = offlineMgr
        )
    }

    @Test
    fun `finals queued before stop are delivered synchronously in stop`() {
        val recognizer = FakeRecognizer(
            endpointAfterChunks = 2, endpointText = "IMPORTANT RESULT",
            endpointTokens = arrayOf(" IMPORTANT", " RESULT"),
            endpointTimestamps = floatArrayOf(0.5f, 1.0f)
        )
        val pcmSource = FakePcmSource(chunksToEmit = 4)

        val transcriber = createTranscriber(recognizer, pcmSource)
        val finals = mutableListOf<String>()
        var lectureMode = true
        transcriber.onFinalResultWithWords = { text, _ ->
            if (lectureMode) finals.add(text)
        }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(500) // let recording thread detect endpoint

        // DO NOT flush posted runnables — simulates the race
        transcriber.stop()

        // Now clear lectureMode (what WritingActivity does after stop)
        lectureMode = false

        // Run any late posted runnables — should be no-ops since queue is drained
        while (true) { postedRunnables.poll()?.run() ?: break }

        assertTrue("Final must be delivered before lectureMode cleared", finals.isNotEmpty())
        assertTrue(finals.any { it.contains("important", ignoreCase = true) })
    }

    @Test
    fun `stop with no pending finals is safe`() {
        val recognizer = FakeRecognizer(endpointAfterChunks = 100)
        val pcmSource = FakePcmSource(chunksToEmit = 2)

        val transcriber = createTranscriber(recognizer, pcmSource)
        transcriber.onFinalResultWithWords = { _, _ -> }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(300)
        transcriber.stop()
    }

    @Test
    fun `start and stop delivers finals via callback`() {
        val recognizer = FakeRecognizer(endpointAfterChunks = 2, endpointText = "HELLO WORLD")
        val pcmSource = FakePcmSource(chunksToEmit = 5)

        val transcriber = createTranscriber(recognizer, pcmSource, immediateExecutor)
        val finals = mutableListOf<String>()
        transcriber.onFinalResultWithWords = { text, _ -> finals.add(text) }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(500)
        transcriber.stop()

        assertTrue("Should deliver at least one final", finals.isNotEmpty())
        assertTrue(finals.any { it.contains("hello", ignoreCase = true) })
    }

    @Test
    fun `partials are emitted during recording`() {
        val recognizer = FakeRecognizer(endpointAfterChunks = 100, endpointText = "partial text")
        // Override getResult to return text before endpoint
        val partialRecognizer = object : StreamingRecognizer by recognizer {
            override fun getResult(stream: RecognizerStream) =
                RecognitionResult(text = "GROWING TEXT", tokens = emptyArray(), timestamps = floatArrayOf())
            override fun isEndpoint(stream: RecognizerStream) = false
        }
        val pcmSource = FakePcmSource(chunksToEmit = 10)

        val transcriber = createTranscriber(partialRecognizer, pcmSource, immediateExecutor)
        val partials = mutableListOf<String>()
        transcriber.onPartialResult = { partials.add(it) }
        transcriber.onFinalResultWithWords = { _, _ -> }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(500)
        transcriber.stop()

        assertTrue("Should emit partials during recording", partials.isNotEmpty())
    }

    // ── Two-pass tests ──────────────────────────────────────────────

    /** Fake offline recognizer that returns improved text. */
    private class FakeOfflineRecognizer(
        private val improvedText: String = "improved result"
    ) : OfflineRecognizer {
        var decodeCalls = 0
        var lastSamplesSize = 0
        override fun decode(samples: FloatArray, sampleRate: Int): OfflineResult {
            decodeCalls++
            lastSamplesSize = samples.size
            return OfflineResult(text = improvedText)
        }
        override fun release() {}
    }

    @Test
    fun `two-pass emits offline result instead of streaming result`() {
        val recognizer = FakeRecognizer(
            endpointAfterChunks = 2, endpointText = "STREAMING RESULT",
            endpointTokens = arrayOf(" STREAMING", " RESULT"),
            endpointTimestamps = floatArrayOf(0.5f, 1.0f)
        )
        val offline = FakeOfflineRecognizer("IMPROVED OFFLINE RESULT")
        val pcmSource = FakePcmSource(chunksToEmit = 4)

        val transcriber = createTranscriber(recognizer, pcmSource, immediateExecutor, offline)
        val finals = mutableListOf<String>()
        transcriber.onFinalResultWithWords = { text, _ -> finals.add(text) }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(500)
        transcriber.stop()

        assertTrue("Should deliver at least one final", finals.isNotEmpty())
        // The final should contain the offline result, not the streaming result
        assertTrue(
            "Final should be from offline model, got: ${finals}",
            finals.any { it.contains("improved", ignoreCase = true) }
        )
        assertFalse(
            "Should not contain raw streaming result",
            finals.any { it.contains("streaming", ignoreCase = true) }
        )
        assertTrue("Offline recognizer should have been called", offline.decodeCalls > 0)
    }

    @Test
    fun `two-pass buffers PCM and sends to offline recognizer`() {
        val recognizer = FakeRecognizer(
            endpointAfterChunks = 3, endpointText = "SOME TEXT",
            endpointTokens = arrayOf(" SOME", " TEXT"),
            endpointTimestamps = floatArrayOf(0.5f, 1.0f)
        )
        val offline = FakeOfflineRecognizer("better text")
        val pcmSource = FakePcmSource(chunksToEmit = 5)

        val transcriber = createTranscriber(recognizer, pcmSource, immediateExecutor, offline)
        transcriber.onFinalResultWithWords = { _, _ -> }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(500)
        transcriber.stop()

        assertTrue("Offline should receive PCM samples", offline.lastSamplesSize > 0)
    }

    @Test
    fun `without offline model, streaming result is used as before`() {
        val recognizer = FakeRecognizer(
            endpointAfterChunks = 2, endpointText = "STREAMING ONLY",
            endpointTokens = arrayOf(" STREAMING", " ONLY"),
            endpointTimestamps = floatArrayOf(0.5f, 1.0f)
        )
        val pcmSource = FakePcmSource(chunksToEmit = 4)

        // No offline recognizer — should behave as before
        val transcriber = createTranscriber(recognizer, pcmSource, immediateExecutor, offlineRecognizer = null)
        val finals = mutableListOf<String>()
        transcriber.onFinalResultWithWords = { text, _ -> finals.add(text) }
        transcriber.onFinalResult = {}

        transcriber.start("en-US")
        Thread.sleep(500)
        transcriber.stop()

        assertTrue("Should deliver streaming result", finals.isNotEmpty())
        assertTrue(
            "Should contain streaming result",
            finals.any { it.contains("streaming", ignoreCase = true) }
        )
    }
}
