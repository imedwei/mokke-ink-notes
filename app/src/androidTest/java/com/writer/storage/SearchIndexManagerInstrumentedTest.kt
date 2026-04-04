package com.writer.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [SearchIndexManager] running on a real device.
 *
 * AppSearch LocalStorage requires the native IcingSearchEngine which only
 * runs on a real Android device (not Robolectric). All SearchIndexManager
 * tests are therefore instrumented tests.
 *
 * Run via: ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.storage.SearchIndexManagerInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexManagerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun samplePoints() = listOf(
        StrokePoint(0f, 0f, 1f, 0L),
        StrokePoint(100f, 100f, 1f, 100L)
    )

    private fun docWithText(
        mainLines: Map<Int, String> = emptyMap(),
        cueLines: Map<Int, String> = emptyMap()
    ) = DocumentData(
        main = ColumnData(
            strokes = listOf(InkStroke(points = samplePoints())),
            lineTextCache = mainLines
        ),
        cue = ColumnData(lineTextCache = cueLines)
    )

    @Before
    fun setUp() = runTest {
        // Clean up documents dir to avoid cross-test contamination
        val docsDir = context.filesDir.resolve("documents")
        docsDir.listFiles()?.forEach { it.delete() }
        // Reset search index
        SearchIndexManager.close()
    }

    @After
    fun tearDown() {
        SearchIndexManager.close()
    }

    // ── Index + basic search ──────────────────────────────────────────────

    @Test
    fun indexDocument_thenSearch_findsIt() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "hello world"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "hello")
        assertTrue("Search should find the document", results.containsKey("TestDoc"))
    }

    @Test
    fun search_noResults_returnsEmpty() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "hello world"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "xyznonexistent")
        assertFalse("Search should not find unrelated query", results.containsKey("TestDoc"))
    }

    @Test
    fun search_emptyQuery_returnsEmpty() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "hello world"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "")
        assertTrue("Empty query should return empty results", results.isEmpty())
    }

    @Test
    fun search_blankQuery_returnsEmpty() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "hello world"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "   ")
        assertTrue("Blank query should return empty results", results.isEmpty())
    }

    // ── Case insensitivity ────────────────────────────────────────────────

    @Test
    fun search_isCaseInsensitive() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "Meeting Notes"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "meeting")
        assertTrue("Search should be case-insensitive", results.containsKey("TestDoc"))
    }

    @Test
    fun search_uppercaseQuery_findsLowercaseContent() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "meeting notes"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "MEETING")
        assertTrue("Uppercase query should find lowercase content", results.containsKey("TestDoc"))
    }

    // ── Line-level matching ───────────────────────────────────────────────

    @Test
    fun search_returnsCorrectLineIndices() = runTest {
        val data = docWithText(mainLines = mapOf(
            0 to "introduction",
            1 to "important meeting notes",
            2 to "conclusion"
        ))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "meeting")
        val matches = results["TestDoc"]
        assertNotNull("Should have matches for TestDoc", matches)
        assertTrue("Should match line 1", matches!!.any { it.lineIndex == 1 })
        assertTrue("Match text should contain 'meeting'",
            matches.any { it.text.contains("meeting", ignoreCase = true) })
    }

    @Test
    fun search_matchesMultipleLines() = runTest {
        val data = docWithText(mainLines = mapOf(
            0 to "project review",
            1 to "other stuff",
            2 to "review notes"
        ))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "review")
        val matches = results["TestDoc"]
        assertNotNull("Should have matches", matches)
        assertTrue("Should match at least 2 lines", matches!!.size >= 2)
    }

    // ── Main vs cue column ────────────────────────────────────────────────

    @Test
    fun search_findsCueText() = runTest {
        val data = docWithText(
            mainLines = mapOf(0 to "main content"),
            cueLines = mapOf(0 to "cue keyword")
        )
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "keyword")
        assertTrue("Should find cue text", results.containsKey("TestDoc"))
    }

    @Test
    fun search_findsMainText() = runTest {
        val data = docWithText(
            mainLines = mapOf(0 to "main keyword"),
            cueLines = mapOf(0 to "cue content")
        )
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "keyword")
        assertTrue("Should find main text", results.containsKey("TestDoc"))
    }

    // ── Title/name search ─────────────────────────────────────────────────

    @Test
    fun search_matchesDocumentTitle() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "some content"))
        SearchIndexManager.indexDocument(context, "Physics Lecture", data)

        val results = SearchIndexManager.search(context, "Physics")
        assertTrue("Should match document title", results.containsKey("Physics Lecture"))
    }

    // ── Core CRUD cycle ───────────────────────────────────────────────────

    @Test
    fun fullCrudCycle_indexSearchRemove() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "instrumented test"))
        SearchIndexManager.indexDocument(context, "CrudDoc", data)

        val found = SearchIndexManager.search(context, "instrumented")
        assertTrue("Should find indexed doc", found.containsKey("CrudDoc"))

        SearchIndexManager.removeDocument(context, "CrudDoc")

        val gone = SearchIndexManager.search(context, "instrumented")
        assertFalse("Should not find removed doc", gone.containsKey("CrudDoc"))
    }

    @Test
    fun fullCrudCycle_indexSearchRename() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "rename test"))
        SearchIndexManager.indexDocument(context, "OldName", data)

        SearchIndexManager.renameDocument(context, "OldName", "NewName")

        val oldResults = SearchIndexManager.search(context, "rename")
        assertFalse("Old name gone", oldResults.containsKey("OldName"))

        val newResults = SearchIndexManager.search(context, "rename")
        assertTrue("New name found", newResults.containsKey("NewName"))
    }

    // ── Remove edge cases ─────────────────────────────────────────────────

    @Test
    fun removeDocument_doesNotAffectOtherDocs() = runTest {
        SearchIndexManager.indexDocument(context, "Doc1",
            docWithText(mainLines = mapOf(0 to "alpha")))
        SearchIndexManager.indexDocument(context, "Doc2",
            docWithText(mainLines = mapOf(0 to "alpha")))

        SearchIndexManager.removeDocument(context, "Doc1")

        val results = SearchIndexManager.search(context, "alpha")
        assertFalse("Doc1 should be gone", results.containsKey("Doc1"))
        assertTrue("Doc2 should remain", results.containsKey("Doc2"))
    }

    @Test
    fun removeDocument_nonexistent_doesNotThrow() = runTest {
        SearchIndexManager.removeDocument(context, "DoesNotExist")
    }

    // ── Rename ────────────────────────────────────────────────────────────

    @Test
    fun renameDocument_preservesLineData() = runTest {
        val data = docWithText(mainLines = mapOf(
            0 to "first line",
            1 to "second line"
        ))
        SearchIndexManager.indexDocument(context, "OldName", data)
        SearchIndexManager.renameDocument(context, "OldName", "NewName")

        val results = SearchIndexManager.search(context, "second")
        val matches = results["NewName"]
        assertNotNull("Should have matches under new name", matches)
        assertTrue("Should still match line 1", matches!!.any { it.lineIndex == 1 })
    }

    // ── Update (re-index) ─────────────────────────────────────────────────

    @Test
    fun indexDocument_twice_updatesContent() = runTest {
        SearchIndexManager.indexDocument(context, "TestDoc",
            docWithText(mainLines = mapOf(0 to "original content")))

        SearchIndexManager.indexDocument(context, "TestDoc",
            docWithText(mainLines = mapOf(0 to "updated content")))

        val oldResults = SearchIndexManager.search(context, "original")
        assertFalse("Old content should not match", oldResults.containsKey("TestDoc"))

        val newResults = SearchIndexManager.search(context, "updated")
        assertTrue("New content should match", newResults.containsKey("TestDoc"))
    }

    // ── Multiple documents ────────────────────────────────────────────────

    @Test
    fun searchAcrossManyDocuments() = runTest {
        for (i in 1..10) {
            SearchIndexManager.indexDocument(context, "Doc$i",
                docWithText(mainLines = mapOf(0 to "common term doc$i specific$i")))
        }

        val allResults = SearchIndexManager.search(context, "common")
        assertEquals("Should find all 10 docs", 10, allResults.size)

        val specificResults = SearchIndexManager.search(context, "specific7")
        assertEquals("Should find only Doc7", 1, specificResults.size)
        assertTrue("Should be Doc7", specificResults.containsKey("Doc7"))
    }

    @Test
    fun search_uniqueTerm_onlyMatchesOneDoc() = runTest {
        SearchIndexManager.indexDocument(context, "Doc1",
            docWithText(mainLines = mapOf(0 to "alpha beta")))
        SearchIndexManager.indexDocument(context, "Doc2",
            docWithText(mainLines = mapOf(0 to "gamma delta")))

        val results = SearchIndexManager.search(context, "gamma")
        assertFalse("Doc1 should not match", results.containsKey("Doc1"))
        assertTrue("Doc2 should match", results.containsKey("Doc2"))
    }

    // ── Rebuild from disk ─────────────────────────────────────────────────

    @Test
    fun rebuildIndex_fromInkupFiles() = runTest {
        DocumentStorage.save(context, "Rebuild1",
            docWithText(mainLines = mapOf(0 to "first document")))
        DocumentStorage.save(context, "Rebuild2",
            docWithText(mainLines = mapOf(0 to "second document")))
        DocumentStorage.save(context, "Rebuild3",
            docWithText(mainLines = mapOf(
                0 to "third document",
                1 to "with multiple lines"
            )))

        SearchIndexManager.close()
        SearchIndexManager.rebuildIndex(context)

        val r1 = SearchIndexManager.search(context, "first")
        assertTrue("Rebuild1 should be found", r1.containsKey("Rebuild1"))
        val r2 = SearchIndexManager.search(context, "second")
        assertTrue("Rebuild2 should be found", r2.containsKey("Rebuild2"))
        val r3 = SearchIndexManager.search(context, "multiple")
        assertTrue("Rebuild3 should be found", r3.containsKey("Rebuild3"))
    }

    @Test
    fun rebuildIndex_emptyDocsDir_doesNotThrow() = runTest {
        val docsDir = context.filesDir.resolve("documents")
        docsDir.listFiles()?.forEach { it.delete() }

        SearchIndexManager.rebuildIndex(context)
        val results = SearchIndexManager.search(context, "anything")
        assertTrue("Should return empty", results.isEmpty())
    }

    // ── Empty document ────────────────────────────────────────────────────

    @Test
    fun indexDocument_emptyLineTextCache_doesNotThrow() = runTest {
        val data = docWithText()
        SearchIndexManager.indexDocument(context, "EmptyDoc", data)
        val results = SearchIndexManager.search(context, "anything")
        assertFalse("Empty doc should not match random query",
            results.containsKey("EmptyDoc"))
    }

    // ── Prefix matching ───────────────────────────────────────────────────

    @Test
    fun search_prefixMatch_works() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "category overview"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "categ")
        assertTrue("Prefix 'categ' should match 'category'",
            results.containsKey("TestDoc"))
    }

    // ── Special characters ────────────────────────────────────────────────

    @Test
    fun search_withNumbers_works() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "meeting on 2026-03-29"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "2026")
        assertTrue("Should find numeric content", results.containsKey("TestDoc"))
    }

    @Test
    fun searchWithSpecialCharacters_doesNotCrash() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "test content"))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        SearchIndexManager.search(context, "test(")
        SearchIndexManager.search(context, "test[")
        SearchIndexManager.search(context, "\"test\"")
    }

    // ── Persistence ───────────────────────────────────────────────────────

    @Test
    fun indexPersistsAcrossSessionClose() = runTest {
        SearchIndexManager.indexDocument(context, "PersistDoc",
            docWithText(mainLines = mapOf(0 to "persistent data")))

        SearchIndexManager.close()

        val results = SearchIndexManager.search(context, "persistent")
        assertTrue("Data should persist", results.containsKey("PersistDoc"))
    }

    // ── Line match sorting ────────────────────────────────────────────────

    @Test
    fun search_lineMatches_sortedByLineIndex() = runTest {
        val data = docWithText(mainLines = mapOf(
            5 to "keyword here",
            2 to "keyword there",
            8 to "keyword everywhere"
        ))
        SearchIndexManager.indexDocument(context, "TestDoc", data)

        val results = SearchIndexManager.search(context, "keyword")
        val matches = results["TestDoc"]!!
        val indices = matches.map { it.lineIndex }
        assertEquals("Matches should be sorted by line index",
            indices.sorted(), indices)
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    fun documentWithOnlyCueText_isSearchable() = runTest {
        val data = docWithText(cueLines = mapOf(0 to "only cue content"))
        SearchIndexManager.indexDocument(context, "CueOnly", data)

        val results = SearchIndexManager.search(context, "cue")
        assertTrue("Cue-only doc should be searchable", results.containsKey("CueOnly"))
    }

    @Test
    fun documentWithUnicodeText_isSearchable() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "日本語テスト"))
        SearchIndexManager.indexDocument(context, "UnicodeDoc", data)

        val results = SearchIndexManager.search(context, "日本語")
        assertTrue("Unicode text should be searchable", results.containsKey("UnicodeDoc"))
    }

    @Test
    fun documentWithLongText_isSearchable() = runTest {
        val longText = "word ".repeat(500).trim()
        val data = docWithText(mainLines = mapOf(0 to longText))
        SearchIndexManager.indexDocument(context, "LongDoc", data)

        val results = SearchIndexManager.search(context, "word")
        assertTrue("Long text doc should be searchable", results.containsKey("LongDoc"))
    }

    // ── DocumentStorage integration ───────────────────────────────────────

    @Test
    fun documentStorage_save_thenSearch_onDevice() = runTest {
        val data = docWithText(mainLines = mapOf(
            0 to "handwritten notes",
            1 to "meeting agenda"
        ))
        // DocumentStorage.save fires indexing async via GlobalScope — call directly to await
        DocumentStorage.save(context, "DeviceDoc", data)
        SearchIndexManager.indexDocument(context, "DeviceDoc", data)

        val results = SearchIndexManager.search(context, "handwritten")
        assertTrue("Saved doc should be searchable", results.containsKey("DeviceDoc"))
    }

    @Test
    fun documentStorage_saveDeleteSave_cycle() = runTest {
        val data = docWithText(mainLines = mapOf(0 to "cycle test"))
        DocumentStorage.save(context, "CycleDoc", data)
        SearchIndexManager.indexDocument(context, "CycleDoc", data)

        DocumentStorage.delete(context, "CycleDoc")
        SearchIndexManager.removeDocument(context, "CycleDoc")

        val afterDelete = SearchIndexManager.search(context, "cycle")
        assertFalse("Should be gone after delete", afterDelete.containsKey("CycleDoc"))

        val data2 = docWithText(mainLines = mapOf(0 to "new cycle content"))
        DocumentStorage.save(context, "CycleDoc", data2)
        SearchIndexManager.indexDocument(context, "CycleDoc", data2)

        val afterResave = SearchIndexManager.search(context, "new")
        assertTrue("Should be back after re-save", afterResave.containsKey("CycleDoc"))
    }
}
