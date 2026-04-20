package com.writer.ui.writing

import android.app.Application
import com.writer.model.ColumnData
import com.writer.model.DocumentModel
import com.writer.model.InkLine
import com.writer.model.WordInfo
import com.writer.recognition.TextRecognizer
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 3c behavioral contract at the coordinator level:
 *
 *   All audio-derived TextBlocks land in the transcript column, regardless of
 *   which column was active when the user tapped stop.
 *
 * This is a Robolectric test — it instantiates the real [WritingCoordinator]
 * against a real [HandwritingCanvasView] under a Robolectric application
 * context, so the routing is exercised through the same wiring the activity
 * uses at runtime. The canvas is never attached to a window, so its
 * Onyx-SDK-dependent surface lifecycle stays dormant.
 *
 * Full activity-level routing (pen tap changes activeColumn, recording stops
 * whichever coordinator was active, etc.) remains covered by the on-device
 * AppLaunch / future RecordingTriggersTranscriptAppearance tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WritingCoordinatorTranscriptRoutingTest {

    private lateinit var scope: CoroutineScope
    private lateinit var recognizer: TextRecognizer
    private lateinit var documentModel: DocumentModel

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        scope = TestScope(UnconfinedTestDispatcher())
        recognizer = NoopTextRecognizer()
        documentModel = DocumentModel()
    }

    private fun newCoordinator(columnModel: com.writer.model.ColumnModel): WritingCoordinator {
        val canvas = HandwritingCanvasView(RuntimeEnvironment.getApplication())
        return WritingCoordinator(
            documentModel = documentModel,
            columnModel = columnModel,
            recognizer = recognizer,
            inkCanvas = canvas,
            scope = scope,
            onStatusUpdate = {},
        )
    }

    @Test
    fun `insertTextBlock on transcript coordinator writes to transcript column only`() {
        val coordinator = newCoordinator(documentModel.transcript)
        coordinator.start()

        coordinator.insertTextBlock(
            text = "spoken text",
            audioFile = "rec-1.opus",
            startMs = 1000L,
            endMs = 5000L,
            words = listOf(WordInfo("spoken", 0.9f, 1000, 2000), WordInfo("text", 0.8f, 2500, 5000)),
        )

        assertEquals(1, documentModel.transcript.textBlocks.size)
        with(documentModel.transcript.textBlocks[0]) {
            assertEquals("spoken text", text)
            assertEquals("rec-1.opus", audioFile)
            assertEquals(1000L, audioStartMs)
            assertEquals(5000L, audioEndMs)
            assertEquals(2, words.size)
        }
        assertTrue("main column stays empty", documentModel.main.textBlocks.isEmpty())
        assertTrue("cue column stays empty", documentModel.cue.textBlocks.isEmpty())
    }

    @Test
    fun `multiple transcript inserts stack in order`() {
        val coordinator = newCoordinator(documentModel.transcript)
        coordinator.start()

        coordinator.insertTextBlock("first", audioFile = "rec-1.opus")
        coordinator.insertTextBlock("second", audioFile = "rec-1.opus", startMs = 5000L, endMs = 9000L)
        coordinator.insertTextBlock("third", audioFile = "rec-2.opus")

        assertEquals(3, documentModel.transcript.textBlocks.size)
        assertEquals(listOf("first", "second", "third"),
            documentModel.transcript.textBlocks.map { it.text })
        // Each block lands on a strictly-later line than the previous.
        val lines = documentModel.transcript.textBlocks.map { it.startLineIndex }
        assertEquals("line indices strictly increasing", lines.sorted(), lines)
        assertEquals("no duplicate lines", lines.distinct().size, lines.size)
    }

    @Test
    fun `transcript coordinator does not touch main or cue columns`() {
        // Even if main has content already, the transcript coordinator writes only to
        // its own ColumnModel.
        documentModel.main.textBlocks.add(
            com.writer.model.TextBlock(
                id = "pre-existing",
                startLineIndex = 0,
                heightInLines = 1,
                text = "pre-existing main block",
            )
        )
        val coordinator = newCoordinator(documentModel.transcript)
        coordinator.start()

        coordinator.insertTextBlock("new transcript block", audioFile = "rec.opus")

        assertEquals("pre-existing main block preserved", 1, documentModel.main.textBlocks.size)
        assertEquals("pre-existing", documentModel.main.textBlocks[0].id)
        assertEquals("new transcript block landed", 1, documentModel.transcript.textBlocks.size)
        assertEquals("new transcript block", documentModel.transcript.textBlocks[0].text)
    }

    @Test
    fun `reverse contract — main coordinator writes to main column only`() {
        // Sanity check that the routing is genuinely driven by the ColumnModel argument,
        // not by some hardcoded "always-transcript" rule.
        val coordinator = newCoordinator(documentModel.main)
        coordinator.start()

        coordinator.insertTextBlock("main content")

        assertEquals(1, documentModel.main.textBlocks.size)
        assertEquals("main content", documentModel.main.textBlocks[0].text)
        assertTrue(documentModel.transcript.textBlocks.isEmpty())
        assertTrue(documentModel.cue.textBlocks.isEmpty())
    }

    // ── Fake recognizer ─────────────────────────────────────────────────────

    private class NoopTextRecognizer : TextRecognizer {
        override suspend fun initialize(languageTag: String) {}
        override suspend fun recognizeLine(line: InkLine, preContext: String): String = ""
        override fun close() {}
    }
}
