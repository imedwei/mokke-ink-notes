package com.writer.recognition

import android.app.Application
import android.os.MemoryFile
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Tests for the shared-memory path used by [OnyxHwrTextRecognizer]
 * to pass protobuf data to the Boox HWR service via ParcelFileDescriptor.
 *
 * Runs under Robolectric with a plain Application to avoid WriterApplication's
 * HiddenApiBypass dependency on sun.misc.Unsafe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnyxHwrTextRecognizerTest {

    // ── MemoryFile path (the working approach) ────────────────────────────────

    @Test
    fun memoryFile_getFileDescriptor_reflection_succeeds() {
        val data = "hello".toByteArray()
        val memFile = MemoryFile("test", data.size)
        memFile.writeBytes(data, 0, 0, data.size)

        val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        method.isAccessible = true
        val fd = method.invoke(memFile) as FileDescriptor

        assertNotNull("getFileDescriptor() should return a valid fd", fd)
        memFile.close()
    }

    @Test
    fun memoryFile_pfd_roundTrips_data() {
        val data = byteArrayOf(0x0A, 0x02, 0x48, 0x49)
        val memFile = MemoryFile("test", data.size)
        memFile.writeBytes(data, 0, 0, data.size)

        val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        method.isAccessible = true
        val fd = method.invoke(memFile) as FileDescriptor
        val pfd = ParcelFileDescriptor.dup(fd)

        val readBack = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        assertEquals(data.size, readBack.size)
        for (i in data.indices) {
            assertEquals("Byte $i", data[i], readBack[i])
        }

        pfd.close()
        memFile.close()
    }

    @Test
    fun memoryFile_largePayload_roundTrips() {
        val data = ByteArray(17_000) { (it % 256).toByte() }
        val memFile = MemoryFile("hwr_input", data.size)
        memFile.writeBytes(data, 0, 0, data.size)

        val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        method.isAccessible = true
        val fd = method.invoke(memFile) as FileDescriptor
        val pfd = ParcelFileDescriptor.dup(fd)

        val readBack = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        assertEquals(data.size, readBack.size)

        pfd.close()
        memFile.close()
    }

    // ── SharedMemory path (demonstrates why this approach fails) ──────────────

    @Test
    fun sharedMemory_writeToParcel_isNotCompatibleWithParcelFileDescriptor() {
        // SharedMemory.writeToParcel() writes its fd in a format that only
        // SharedMemory.CREATOR can deserialize — NOT ParcelFileDescriptor.CREATOR.
        // On a real device this throws NullPointerException("FileDescriptor must not be null").
        // Under Robolectric the shadow may not throw, but the data won't round-trip correctly.
        val data = "test data".toByteArray()
        val shm = SharedMemory.create("hwr_input", data.size)
        val buf = shm.mapReadWrite()
        buf.put(data)
        SharedMemory.unmap(buf)

        val parcel = Parcel.obtain()
        try {
            shm.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            try {
                val pfd = ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
                // If Robolectric doesn't throw, verify the data doesn't actually round-trip
                val readBack = try {
                    FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                } catch (_: Exception) {
                    byteArrayOf() // can't even read — confirms incompatibility
                }
                // The data won't match because the fd formats are incompatible
                val matches = readBack.size == data.size &&
                        readBack.zip(data.toList()).all { (a, b) -> a == b }
                if (matches) {
                    fail("SharedMemory data should NOT round-trip through ParcelFileDescriptor.CREATOR")
                }
                pfd.close()
            } catch (_: NullPointerException) {
                // Expected on real devices: "FileDescriptor must not be null"
            } catch (_: Exception) {
                // Other exceptions also confirm incompatibility
            }
        } finally {
            parcel.recycle()
            shm.close()
        }
    }

    @Test
    fun sharedMemory_canBeReadBackViaSharedMemoryCreator() {
        // Contrast: SharedMemory.CREATOR CAN read what SharedMemory.writeToParcel writes
        val data = "round trip".toByteArray()
        val shm = SharedMemory.create("hwr_input", data.size)
        val buf = shm.mapReadWrite()
        buf.put(data)
        SharedMemory.unmap(buf)

        val parcel = Parcel.obtain()
        try {
            shm.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = SharedMemory.CREATOR.createFromParcel(parcel)
            assertNotNull("SharedMemory.CREATOR should deserialize its own format", restored)

            val readBuf = restored.mapReadOnly()
            val readBack = ByteArray(data.size)
            readBuf.get(readBack)
            SharedMemory.unmap(readBuf)

            assertEquals(String(data), String(readBack))

            restored.close()
        } finally {
            parcel.recycle()
            shm.close()
        }
    }

    // ── Alternative approaches that work ────────────────────────────────────────

    @Test
    fun pipe_roundTrips_data() {
        // Pipe approach: no reflection, no shared memory — just a pipe pair
        val data = ByteArray(17_000) { (it % 256).toByte() }
        val pipe = ParcelFileDescriptor.createPipe()
        val readPfd = pipe[0]
        val writePfd = pipe[1]

        FileOutputStream(writePfd.fileDescriptor).use { it.write(data) }
        writePfd.close()

        val readBack = FileInputStream(readPfd.fileDescriptor).use { it.readBytes() }
        readPfd.close()

        assertEquals(data.size, readBack.size)
        for (i in data.indices) {
            assertEquals("Byte $i", data[i], readBack[i])
        }
    }

    @Test
    fun sharedMemory_fdReflection_roundTrips_data() {
        // SharedMemory + reflection for mFileDescriptor → ParcelFileDescriptor.dup()
        val data = ByteArray(17_000) { (it % 256).toByte() }
        val shm = SharedMemory.create("hwr_input", data.size)
        val buf = shm.mapReadWrite()
        buf.put(data)
        SharedMemory.unmap(buf)

        val fdField = SharedMemory::class.java.getDeclaredField("mFileDescriptor")
        fdField.isAccessible = true
        val fd = fdField.get(shm) as FileDescriptor
        val pfd = ParcelFileDescriptor.dup(fd)

        val readBack = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        assertEquals(data.size, readBack.size)
        for (i in data.indices) {
            assertEquals("Byte $i", data[i], readBack[i])
        }

        pfd.close()
        shm.close()
    }

    // ── End-to-end: real protobuf through MemoryFile ──────────────────────────

    @Test
    fun realProtobuf_throughMemoryFile_roundTrips() {
        val stroke = com.writer.model.InkStroke(
            strokeId = "s1",
            points = listOf(
                com.writer.model.StrokePoint(100f, 200f, 0.5f, 1000L),
                com.writer.model.StrokePoint(110f, 210f, 0.6f, 1010L),
                com.writer.model.StrokePoint(120f, 220f, 0.7f, 1020L)
            )
        )
        val line = com.writer.model.InkLine(
            listOf(stroke),
            android.graphics.RectF(100f, 200f, 120f, 220f)
        )
        val protoBytes = HwrProtobuf.buildProtobuf(line, 1000f, 500f, "en_US")

        // MemoryFile path (same as createMemoryFilePfd)
        val memFile = MemoryFile("hwr_input", protoBytes.size)
        memFile.writeBytes(protoBytes, 0, 0, protoBytes.size)
        val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        method.isAccessible = true
        val fd = method.invoke(memFile) as FileDescriptor
        val pfd = ParcelFileDescriptor.dup(fd)

        val readBack = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        assertEquals("Protobuf bytes should survive MemoryFile round-trip",
            protoBytes.size, readBack.size)
        for (i in protoBytes.indices) {
            assertEquals("Byte $i", protoBytes[i], readBack[i])
        }

        pfd.close()
        memFile.close()
    }
}
