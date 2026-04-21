package com.writer.storage

import com.writer.model.AnchorMode
import com.writer.model.AnchorTarget
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.TextBlock
import com.writer.model.WordInfo
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3e: verify [AutomergeSync] produces minimal deltas for the transcript
 * column. The Phase 3a code already serializes transcript strokes, text blocks,
 * and anchor fields; these tests lock the incremental-sync contract so a
 * single-block edit doesn't rewrite the whole document.
 */
class AutomergeSyncTranscriptTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(density = 1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun docWithTranscript(blocks: List<TextBlock>): DocumentData = DocumentData(
        main = ColumnData(),
        transcript = ColumnData(textBlocks = blocks),
    )

    @Test
    fun `transcript column syncs via Automerge`() {
        val sync = AutomergeSync()
        sync.sync(docWithTranscript(listOf(
            TextBlock(
                id = "tb-1", startLineIndex = 2, heightInLines = 1,
                text = "hello world", audioFile = "rec-a.opus",
                anchorTarget = AnchorTarget.MAIN, anchorLineIndex = 2, anchorMode = AnchorMode.AUTO,
            )
        )))

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.transcript.textBlocks.size)
        with(result.transcript.textBlocks[0]) {
            assertEquals("tb-1", id)
            assertEquals("hello world", text)
            assertEquals(AnchorTarget.MAIN, anchorTarget)
            assertEquals(AnchorMode.AUTO, anchorMode)
        }
    }

    @Test
    fun `adding a transcript block produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(docWithTranscript(listOf(
            TextBlock(id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "first")
        )))
        val headsBefore = sync.document.heads.clone()

        sync.sync(docWithTranscript(listOf(
            TextBlock(id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "first"),
            TextBlock(id = "tb-2", startLineIndex = 2, heightInLines = 1, text = "second"),
        )))

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue(
            "add-one-block delta should be small (was ${delta.size} bytes)",
            delta.size < 500
        )
        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(2, result.transcript.textBlocks.size)
    }

    @Test
    fun `removing a transcript block produces small delta`() {
        val sync = AutomergeSync()
        sync.sync(docWithTranscript(listOf(
            TextBlock(id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "keep"),
            TextBlock(id = "tb-2", startLineIndex = 2, heightInLines = 1, text = "drop"),
        )))
        val headsBefore = sync.document.heads.clone()

        sync.sync(docWithTranscript(listOf(
            TextBlock(id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "keep"),
        )))

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("remove-one-block delta should be small (was ${delta.size} bytes)", delta.size < 300)
        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.transcript.textBlocks.size)
        assertEquals("tb-1", result.transcript.textBlocks[0].id)
    }

    @Test
    fun `transcript block text edit propagates`() {
        // E.g. user picks a different alternative for a low-confidence word, or pen-
        // overwrites a word and HWR replaces it. The id stays the same, the text mutates.
        val sync = AutomergeSync()
        sync.sync(docWithTranscript(listOf(
            TextBlock(
                id = "tb-1", startLineIndex = 2, heightInLines = 1,
                text = "halo world",
                words = listOf(WordInfo("halo", 0.5f, 0, 400), WordInfo("world", 0.9f, 500, 900)),
            )
        )))
        val headsBefore = sync.document.heads.clone()

        sync.sync(docWithTranscript(listOf(
            TextBlock(
                id = "tb-1", startLineIndex = 2, heightInLines = 1,
                text = "hello world",
                words = listOf(WordInfo("hello", 0.95f, 0, 400), WordInfo("world", 0.9f, 500, 900)),
            )
        )))

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("text-edit delta should be small (was ${delta.size} bytes)", delta.size < 800)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals(1, result.transcript.textBlocks.size)
        assertEquals("hello world", result.transcript.textBlocks[0].text)
        assertEquals("hello", result.transcript.textBlocks[0].words[0].text)
        assertEquals(0.95f, result.transcript.textBlocks[0].words[0].confidence, 0.001f)
    }

    @Test
    fun `anchor change propagates with small delta`() {
        // User drags the anchor handle from MAIN to CUE. The only thing that changes
        // is the anchor fields — text, audio, words, everything else is untouched.
        val sync = AutomergeSync()
        sync.sync(docWithTranscript(listOf(
            TextBlock(
                id = "tb-1", startLineIndex = 3, heightInLines = 1,
                text = "spoken line",
                audioFile = "rec-a.opus", audioStartMs = 1000, audioEndMs = 2000,
                anchorTarget = AnchorTarget.MAIN, anchorLineIndex = 3, anchorMode = AnchorMode.AUTO,
            )
        )))
        val headsBefore = sync.document.heads.clone()

        sync.sync(docWithTranscript(listOf(
            TextBlock(
                id = "tb-1", startLineIndex = 3, heightInLines = 1,
                text = "spoken line",
                audioFile = "rec-a.opus", audioStartMs = 1000, audioEndMs = 2000,
                anchorTarget = AnchorTarget.CUE, anchorLineIndex = 7, anchorMode = AnchorMode.MANUAL,
            )
        )))

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue("anchor-change delta should be small (was ${delta.size} bytes)", delta.size < 400)

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        with(result.transcript.textBlocks[0]) {
            assertEquals(AnchorTarget.CUE, anchorTarget)
            assertEquals(7, anchorLineIndex)
            assertEquals(AnchorMode.MANUAL, anchorMode)
            assertEquals("spoken line", text) // unchanged
            assertEquals("rec-a.opus", audioFile) // unchanged
        }
    }

    @Test
    fun `no transcript change produces no delta`() {
        val sync = AutomergeSync()
        val data = docWithTranscript(listOf(
            TextBlock(
                id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "noop",
                anchorTarget = AnchorTarget.CUE, anchorLineIndex = 5, anchorMode = AnchorMode.MANUAL,
            )
        ))
        sync.sync(data)
        val headsBefore = sync.document.heads.clone()

        sync.sync(data)

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertEquals("identical sync writes no bytes", 0, delta.size)
    }

    @Test
    fun `anchor mode flip only touches one block`() {
        // When two blocks exist and only one changes anchor mode, the sync payload
        // must not rewrite the untouched block.
        val sync = AutomergeSync()
        sync.sync(docWithTranscript(listOf(
            TextBlock(id = "tb-a", startLineIndex = 0, heightInLines = 1, text = "a", anchorMode = AnchorMode.AUTO),
            TextBlock(id = "tb-b", startLineIndex = 2, heightInLines = 1, text = "b", anchorMode = AnchorMode.AUTO),
        )))
        val headsBefore = sync.document.heads.clone()

        sync.sync(docWithTranscript(listOf(
            TextBlock(id = "tb-a", startLineIndex = 0, heightInLines = 1, text = "a", anchorMode = AnchorMode.AUTO),
            TextBlock(id = "tb-b", startLineIndex = 2, heightInLines = 1, text = "b", anchorMode = AnchorMode.MANUAL),
        )))

        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue(
            "single-field flip on one of two blocks stays small (was ${delta.size} bytes)",
            delta.size < 400
        )
        val result = AutomergeAdapter.fromAutomerge(sync.document)
        val blockA = result.transcript.textBlocks.first { it.id == "tb-a" }
        val blockB = result.transcript.textBlocks.first { it.id == "tb-b" }
        assertEquals(AnchorMode.AUTO, blockA.anchorMode)
        assertEquals(AnchorMode.MANUAL, blockB.anchorMode)
    }

    @Test
    fun `transcript sync is independent of main and cue`() {
        // Guards against a regression where syncColumn crosses column boundaries
        // and a transcript edit rewrites main or cue.
        val sync = AutomergeSync()
        val withMain = DocumentData(
            main = ColumnData(
                strokes = listOf(com.writer.model.InkStroke(
                    "main-s-1",
                    listOf(com.writer.model.StrokePoint(10f, 20f, 0.5f, 1000L))
                ))
            ),
            transcript = ColumnData(textBlocks = listOf(
                TextBlock(id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "v1"),
            )),
        )
        sync.sync(withMain)
        val headsBefore = sync.document.heads.clone()

        // Only mutate the transcript block.
        sync.sync(withMain.copy(
            transcript = ColumnData(textBlocks = listOf(
                TextBlock(id = "tb-1", startLineIndex = 0, heightInLines = 1, text = "v2"),
            )),
        ))
        val delta = sync.document.encodeChangesSince(headsBefore)
        assertTrue(
            "transcript-only edit must not invalidate main stroke data (delta=${delta.size})",
            delta.size < 400
        )

        val result = AutomergeAdapter.fromAutomerge(sync.document)
        assertEquals("main stroke survived", 1, result.main.strokes.size)
        assertEquals("v2", result.transcript.textBlocks[0].text)
        assertFalse(result.main.strokes.isEmpty())
    }
}
