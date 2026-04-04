package com.writer

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom test runner that automatically cleans up documents created during tests.
 *
 * Configured in build.gradle.kts as `testInstrumentationRunner`. All instrumented
 * tests inherit document cleanup without needing a per-test `@Rule`.
 */
class WriterTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        // Register a global listener that snapshots documents before each test
        // and deletes any new ones after.
        val listenerArg = arguments?.getString("listener").orEmpty()
        val listeners = if (listenerArg.isBlank()) {
            DocumentCleanupListener::class.java.name
        } else {
            "$listenerArg,${DocumentCleanupListener::class.java.name}"
        }
        arguments?.putString("listener", listeners)
        super.onCreate(arguments)
    }
}
