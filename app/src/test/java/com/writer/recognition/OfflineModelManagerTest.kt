package com.writer.recognition

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineModelManagerTest {

    private lateinit var manager: OfflineModelManager

    private fun fakeRecognizer(): OfflineRecognizer = object : OfflineRecognizer {
        override fun decode(samples: FloatArray, sampleRate: Int) = OfflineResult(text = "fake result")
        override fun release() {}
    }

    @Before
    fun setUp() {
        manager = OfflineModelManager()
    }

    @Test
    fun `initial state is UNLOADED`() {
        assertEquals(OfflineModelManager.State.UNLOADED, manager.state)
    }

    @Test
    fun `getRecognizer returns null when unloaded`() {
        assertNull(manager.getRecognizer())
    }

    @Test
    fun `release from UNLOADED is safe`() {
        manager.release()
        assertEquals(OfflineModelManager.State.UNLOADED, manager.state)
    }

    @Test
    fun `loadWithFactory transitions to READY`() {
        manager.loadWithFactory { fakeRecognizer() }
        assertEquals(OfflineModelManager.State.READY, manager.state)
    }

    @Test
    fun `getRecognizer returns non-null when READY`() {
        manager.loadWithFactory { fakeRecognizer() }
        assertNotNull(manager.getRecognizer())
    }

    @Test
    fun `release transitions back to UNLOADED`() {
        manager.loadWithFactory { fakeRecognizer() }
        manager.release()
        assertEquals(OfflineModelManager.State.UNLOADED, manager.state)
        assertNull(manager.getRecognizer())
    }

    @Test
    fun `double load is no-op when READY`() {
        val first = fakeRecognizer()
        manager.loadWithFactory { first }
        manager.loadWithFactory { fakeRecognizer() }
        assertSame(first, manager.getRecognizer())
    }

    @Test
    fun `factory error transitions to ERROR`() {
        manager.loadWithFactory { throw RuntimeException("model broken") }
        assertEquals(OfflineModelManager.State.ERROR, manager.state)
        assertNull(manager.getRecognizer())
    }

    @Test
    fun `can reload after ERROR`() {
        manager.loadWithFactory { throw RuntimeException("fail") }
        val good = fakeRecognizer()
        manager.loadWithFactory { good }
        assertEquals(OfflineModelManager.State.READY, manager.state)
        assertSame(good, manager.getRecognizer())
    }

    @Test
    fun `release after ERROR is safe`() {
        manager.loadWithFactory { throw RuntimeException("fail") }
        manager.release()
        assertEquals(OfflineModelManager.State.UNLOADED, manager.state)
    }

    @Test
    fun `onReady callback fires when transitioning to READY`() {
        var called = false
        manager.onReady = { called = true }
        manager.loadWithFactory { fakeRecognizer() }
        assertTrue(called)
    }

    @Test
    fun `onReady callback does not fire on error`() {
        var called = false
        manager.onReady = { called = true }
        manager.loadWithFactory { throw RuntimeException("fail") }
        assertFalse(called)
    }
}
