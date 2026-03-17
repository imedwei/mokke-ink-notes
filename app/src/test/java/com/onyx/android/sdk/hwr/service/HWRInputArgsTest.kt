package com.onyx.android.sdk.hwr.service

import android.app.Application
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HWRInputArgsTest {

    @Test
    fun parcelRoundTrip_preservesAllFields() {
        val original = HWRInputArgs().apply {
            lang = "fr_FR"
            contentType = "Math"
            recognizerType = "MS_ON_SCREEN"
            viewWidth = 1920f
            viewHeight = 1080f
            offsetX = 10f
            offsetY = 20f
            isGestureEnable = true
            isTextEnable = false
            isShapeEnable = true
            isIncremental = true
            content = "some content"
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = HWRInputArgs(parcel)

            assertEquals(original.lang, restored.lang)
            assertEquals(original.contentType, restored.contentType)
            assertEquals(original.recognizerType, restored.recognizerType)
            assertEquals(original.viewWidth, restored.viewWidth, 0f)
            assertEquals(original.viewHeight, restored.viewHeight, 0f)
            assertEquals(original.offsetX, restored.offsetX, 0f)
            assertEquals(original.offsetY, restored.offsetY, 0f)
            assertEquals(original.isGestureEnable, restored.isGestureEnable)
            assertEquals(original.isTextEnable, restored.isTextEnable)
            assertEquals(original.isShapeEnable, restored.isShapeEnable)
            assertEquals(original.isIncremental, restored.isIncremental)
            assertEquals(original.content, restored.content)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_defaultValues() {
        val original = HWRInputArgs()

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = HWRInputArgs(parcel)

            assertEquals("en_US", restored.lang)
            assertEquals("Text", restored.contentType)
            assertEquals("Text", restored.recognizerType)
            assertEquals(0f, restored.viewWidth, 0f)
            assertEquals(0f, restored.viewHeight, 0f)
            assertNull(restored.pfd)
            assertNull(restored.content)
        } finally {
            parcel.recycle()
        }
    }
}
