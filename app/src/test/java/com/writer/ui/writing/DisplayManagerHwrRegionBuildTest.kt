package com.writer.ui.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for building a per-column [InlineTextRegion] list from a column's lineTextCache.
 *
 * Exposed as a pure function so the main/cue columns each build their own list from their
 * own cache — DisplayManager calls this inside its display loop in Phase 2.
 */
class DisplayManagerHwrRegionBuildTest {

    @Test
    fun emptyLineTextCache_yieldsNoRegions() {
        val regions = buildInlineTextRegions(lineTextCache = emptyMap(), currentLineIndex = 0)
        assertTrue("empty cache → empty region list", regions.isEmpty())
    }

    @Test
    fun oneEntryPerCacheLine() {
        val cache = mapOf(0 to "hello", 2 to "world", 5 to "foo bar")
        val regions = buildInlineTextRegions(lineTextCache = cache, currentLineIndex = -1)

        assertEquals("one region per cache entry", cache.size, regions.size)
        assertEquals(
            "region line indices match cache keys",
            cache.keys.toSortedSet().toList(),
            regions.map { it.lineIndex }
        )
        assertEquals(
            "region text matches cache value per line index",
            cache.toSortedMap().values.toList(),
            regions.map { it.text }
        )
    }

    @Test
    fun consolidationFlagDerivedFromCurrentLineIndex() {
        val cache = mapOf(0 to "a", 1 to "b", 2 to "c", 3 to "d")

        // User is writing on line 2 — lines 0 and 1 are past (consolidated).
        val regions = buildInlineTextRegions(lineTextCache = cache, currentLineIndex = 2)

        val byLine = regions.associateBy { it.lineIndex }
        assertTrue("line 0 is past → consolidated", byLine.getValue(0).consolidated)
        assertTrue("line 1 is past → consolidated", byLine.getValue(1).consolidated)
        assertEquals("line 2 is current → not consolidated", false, byLine.getValue(2).consolidated)
        assertEquals("line 3 is ahead → not consolidated", false, byLine.getValue(3).consolidated)
    }

    @Test
    fun mainAndCueProduceIndependentRegionLists() {
        val mainCache = mapOf(0 to "main-0", 1 to "main-1")
        val cueCache = mapOf(0 to "cue-0", 3 to "cue-3")

        val mainRegions = buildInlineTextRegions(lineTextCache = mainCache, currentLineIndex = 1)
        val cueRegions = buildInlineTextRegions(lineTextCache = cueCache, currentLineIndex = 0)

        assertEquals(listOf("main-0", "main-1"), mainRegions.map { it.text })
        assertEquals(listOf("cue-0", "cue-3"), cueRegions.map { it.text })
        assertEquals(listOf(0, 1), mainRegions.map { it.lineIndex })
        assertEquals(listOf(0, 3), cueRegions.map { it.lineIndex })
    }
}
