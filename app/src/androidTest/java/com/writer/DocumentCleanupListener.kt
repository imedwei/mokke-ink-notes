package com.writer

import androidx.test.platform.app.InstrumentationRegistry
import com.writer.storage.DocumentStorage
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * JUnit [RunListener] that automatically deletes documents created during each test.
 *
 * Snapshots the document list before each test. After the test finishes
 * (pass or fail), deletes any documents that weren't in the original snapshot.
 *
 * Registered globally by [WriterTestRunner] — no per-test setup needed.
 */
class DocumentCleanupListener : RunListener() {

    private var preTestDocNames = emptySet<String>()

    override fun testStarted(description: Description) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        preTestDocNames = DocumentStorage.listDocuments(context).map { it.name }.toSet()
    }

    override fun testFinished(description: Description) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val postTestDocNames = DocumentStorage.listDocuments(context).map { it.name }.toSet()
        for (name in postTestDocNames - preTestDocNames) {
            DocumentStorage.delete(context, name)
        }
    }
}
