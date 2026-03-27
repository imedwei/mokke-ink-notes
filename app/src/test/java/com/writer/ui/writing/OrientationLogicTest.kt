package com.writer.ui.writing

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrientationLogicTest {

    private lateinit var host: FakeHost
    private lateinit var logic: OrientationLogic

    @Before
    fun setUp() {
        host = FakeHost()
        logic = OrientationLogic(host)
    }

    // --- toggleOrientation ---

    @Test
    fun `toggle from portrait requests sensor landscape`() {
        host.landscape = false
        logic.toggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.lastOrientation)
    }

    @Test
    fun `toggle from landscape requests sensor portrait`() {
        host.landscape = true
        logic.toggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, host.lastOrientation)
    }

    @Test
    fun `toggle sets orientation locked`() {
        assertFalse(logic.isOrientationLocked)
        logic.toggleOrientation()
        assertTrue(logic.isOrientationLocked)
    }

    // --- updateButtonVisibility ---

    @Test
    fun `button visible when auto-rotate off`() {
        host.autoRotate = false
        logic.updateButtonVisibility()
        assertTrue(host.isButtonVisible)
    }

    @Test
    fun `button hidden when auto-rotate on`() {
        host.autoRotate = true
        logic.updateButtonVisibility()
        assertFalse(host.isButtonVisible)
    }

    // --- onAutoRotateSettingChanged ---

    @Test
    fun `auto-rotate turned on releases orientation lock`() {
        host.landscape = false
        logic.toggleOrientation()
        assertTrue(logic.isOrientationLocked)

        host.autoRotate = true
        logic.onAutoRotateSettingChanged()

        assertFalse(logic.isOrientationLocked)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, host.lastOrientation)
        assertFalse(host.isButtonVisible)
    }

    @Test
    fun `auto-rotate turned on without lock does not set orientation`() {
        host.autoRotate = true
        host.lastOrientation = -999
        logic.onAutoRotateSettingChanged()

        // Should not have called setOrientation (lastOrientation unchanged)
        assertEquals(-999, host.lastOrientation)
        assertFalse(host.isButtonVisible)
    }

    @Test
    fun `auto-rotate turned off shows button`() {
        host.autoRotate = false
        logic.onAutoRotateSettingChanged()
        assertTrue(host.isButtonVisible)
    }

    // --- releaseOrientationLock ---

    @Test
    fun `release sets unspecified and clears lock`() {
        logic.toggleOrientation()
        assertTrue(logic.isOrientationLocked)

        logic.releaseOrientationLock()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, host.lastOrientation)
        assertFalse(logic.isOrientationLocked)
    }

    // --- Fake host ---

    private class FakeHost : OrientationLogic.Host {
        var autoRotate = false
        var landscape = false
        var lastOrientation = -999
        @JvmField var isButtonVisible = false

        override val isAutoRotateOn get() = autoRotate
        override val isLandscape get() = landscape
        override fun setOrientation(orientation: Int) { lastOrientation = orientation }
        override fun setButtonVisible(visible: Boolean) { isButtonVisible = visible }
    }
}
