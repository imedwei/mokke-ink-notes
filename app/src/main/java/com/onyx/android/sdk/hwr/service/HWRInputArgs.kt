package com.onyx.android.sdk.hwr.service

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * Parcelable matching the Boox ksync service's HWRInputArgs.
 * Field order must match the service's classloader expectations exactly.
 */
class HWRInputArgs() : Parcelable {
    var lang: String = "en_US"
    var contentType: String = "Text"
    var recognizerType: String = "Text"
    var viewWidth: Float = 0f
    var viewHeight: Float = 0f
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var isGestureEnable: Boolean = false
    var isTextEnable: Boolean = true
    var isShapeEnable: Boolean = false
    var isIncremental: Boolean = false

    var pfd: ParcelFileDescriptor? = null
    var content: String? = null

    constructor(parcel: Parcel) : this() {
        val className = parcel.readString()
        if (className != null) {
            lang = parcel.readString() ?: "en_US"
            contentType = parcel.readString() ?: "Text"
            recognizerType = parcel.readString() ?: "Text"
            viewWidth = parcel.readFloat()
            viewHeight = parcel.readFloat()
            offsetX = parcel.readFloat()
            offsetY = parcel.readFloat()
            isGestureEnable = parcel.readByte() != 0.toByte()
            isTextEnable = parcel.readByte() != 0.toByte()
            isShapeEnable = parcel.readByte() != 0.toByte()
            isIncremental = parcel.readByte() != 0.toByte()
        }
        pfd = parcel.readParcelable(ParcelFileDescriptor::class.java.classLoader, ParcelFileDescriptor::class.java)
        content = parcel.readString()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString("com.onyx.android.sdk.hwr.bean.HWRInputData")
        parcel.writeString(lang)
        parcel.writeString(contentType)
        parcel.writeString(recognizerType)
        parcel.writeFloat(viewWidth)
        parcel.writeFloat(viewHeight)
        parcel.writeFloat(offsetX)
        parcel.writeFloat(offsetY)
        parcel.writeByte(if (isGestureEnable) 1 else 0)
        parcel.writeByte(if (isTextEnable) 1 else 0)
        parcel.writeByte(if (isShapeEnable) 1 else 0)
        parcel.writeByte(if (isIncremental) 1 else 0)
        val localPfd = pfd
        if (localPfd != null) {
            parcel.writeString("android.os.ParcelFileDescriptor")
            localPfd.writeToParcel(parcel, flags)
        } else {
            parcel.writeString(null)
        }
        parcel.writeString(content)
    }

    override fun describeContents(): Int =
        if (pfd != null) Parcelable.CONTENTS_FILE_DESCRIPTOR else 0

    companion object CREATOR : Parcelable.Creator<HWRInputArgs> {
        override fun createFromParcel(parcel: Parcel) = HWRInputArgs(parcel)
        override fun newArray(size: Int) = arrayOfNulls<HWRInputArgs>(size)
    }
}
