package com.writer.ui.writing

import android.app.Application
import android.net.Uri
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoSaverTest {

    private lateinit var sink: FakeSink
    private val testScope = TestScope()

    private fun makeSnapshot(name: String = "doc", withSync: Boolean = false) =
        AutoSaver.Snapshot(
            name = name,
            state = DocumentData(
                main = ColumnData(strokes = emptyList(), diagramAreas = emptyList()),
                cue = ColumnData(strokes = emptyList(), diagramAreas = emptyList()),
            ),
            markdown = if (withSync) "# Hello" else null,
            syncUri = if (withSync) Uri.parse("content://sync/folder") else null,
        )

    @Before
    fun setUp() {
        sink = FakeSink()
    }

    @Test
    fun `saveBlocking saves to sink synchronously`() {
        val saver = AutoSaver(testScope, sink)
        saver.saveBlocking(makeSnapshot("my-doc"))

        assertEquals(1, sink.saves.size)
        assertEquals("my-doc", sink.saves[0])
    }

    @Test
    fun `saveBlocking exports when sync is configured`() {
        val saver = AutoSaver(testScope, sink)
        saver.saveBlocking(makeSnapshot("doc", withSync = true))

        assertEquals(1, sink.saves.size)
        assertEquals(1, sink.exports.size)
        assertEquals("doc", sink.exports[0])
    }

    @Test
    fun `saveBlocking does not export when save fails`() {
        sink.failSaves = true
        val saver = AutoSaver(testScope, sink)
        saver.saveBlocking(makeSnapshot("doc", withSync = true))

        assertEquals(1, sink.saves.size)
        assertEquals(0, sink.exports.size)
    }

    @Test
    fun `saveBlocking cancels pending debounce`() = runTest {
        val saver = AutoSaver(testScope, sink)
        saver.schedule { makeSnapshot("debounced") }
        saver.saveBlocking(makeSnapshot("immediate"))

        // Only the blocking save should have run
        assertEquals(1, sink.saves.size)
        assertEquals("immediate", sink.saves[0])
    }

    @Test
    fun `saveAsync saves on background thread`() = testScope.runTest {
        val saver = AutoSaver(testScope, sink)
        saver.saveAsync(makeSnapshot("async-doc"))
        advanceUntilIdle()

        assertEquals(1, sink.saves.size)
        assertEquals("async-doc", sink.saves[0])
    }

    @Test
    fun `cancel prevents pending save`() = testScope.runTest {
        val saver = AutoSaver(testScope, sink)
        saver.schedule { makeSnapshot("cancelled") }
        saver.cancel()
        advanceUntilIdle()

        assertTrue(sink.saves.isEmpty())
    }

    private class FakeSink : AutoSaver.Sink {
        val saves = mutableListOf<String>()
        val exports = mutableListOf<String>()
        var failSaves = false

        override fun save(name: String, state: DocumentData): Boolean {
            saves.add(name)
            return !failSaves
        }

        override fun export(name: String, state: DocumentData, markdown: String, syncUri: Uri) {
            exports.add(name)
        }
    }
}
