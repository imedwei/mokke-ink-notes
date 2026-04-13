package com.writer.storage

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AudioBlobStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: AudioBlobStore

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "blob-test-${System.nanoTime()}")
        tempDir.mkdirs()
        store = AudioBlobStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `store returns consistent hash`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val hash1 = store.store(bytes)
        val hash2 = store.store(bytes)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 hex = 64 chars
    }

    @Test
    fun `store and load round trips`() {
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        val hash = store.store(bytes)
        val loaded = store.load(hash)
        assertNotNull(loaded)
        assertArrayEquals(bytes, loaded)
    }

    @Test
    fun `store deduplicates`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        store.store(bytes)
        store.store(bytes)

        // Only one file should exist
        val files = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(1, files.size)
    }

    @Test
    fun `garbageCollect removes unreferenced`() {
        val keep = store.store(byteArrayOf(1, 2, 3))
        val remove = store.store(byteArrayOf(4, 5, 6))

        store.garbageCollect(setOf(keep))

        assertTrue(store.exists(keep))
        assertFalse(store.exists(remove))
        assertNull(store.load(remove))
    }

    @Test
    fun `isContentHash detects 64 hex chars`() {
        val hash = store.store(byteArrayOf(1, 2, 3))
        assertTrue(AudioBlobStore.isContentHash(hash))
        assertFalse(AudioBlobStore.isContentHash("rec-001.opus"))
        assertFalse(AudioBlobStore.isContentHash(""))
        assertFalse(AudioBlobStore.isContentHash("abcdef")) // too short
    }

    @Test
    fun `toFile returns file for existing blob`() {
        val bytes = byteArrayOf(1, 2, 3)
        val hash = store.store(bytes)
        val file = store.toFile(hash)
        assertNotNull(file)
        assertArrayEquals(bytes, file!!.readBytes())
    }

    @Test
    fun `toFile returns null for missing blob`() {
        assertNull(store.toFile("0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun `garbageCollect keeps referenced`() {
        val hash1 = store.store(byteArrayOf(1, 2, 3))
        val hash2 = store.store(byteArrayOf(4, 5, 6))

        store.garbageCollect(setOf(hash1, hash2))

        assertTrue(store.exists(hash1))
        assertTrue(store.exists(hash2))
    }
}
