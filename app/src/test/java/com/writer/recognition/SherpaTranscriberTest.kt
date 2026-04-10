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
        executor: (Runnable) -> Unit = capturingExecutor
    ): SherpaTranscriber {
        modelManager.loadWithFactory { recognizer }
        return SherpaTranscriber(
            context = null, // not used when pcmSourceFactory is provided
            modelManager = modelManager,
            pcmSourceFactory = { pcmSource },
            mainThreadExecutor = executor
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
}
