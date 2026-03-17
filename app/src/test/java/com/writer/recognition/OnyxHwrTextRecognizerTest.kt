package com.writer.recognition

import android.app.Application
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Tests for the pipe-based IPC used by [OnyxHwrTextRecognizer]
 * to pass protobuf data to the Boox HWR service via ParcelFileDescriptor.
 *
 * Runs under Robolectric with a plain Application to avoid WriterApplication's
 * HiddenApiBypass dependency on sun.misc.Unsafe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnyxHwrTextRecognizerTest {

    // ── Pipe-based IPC ────────────────────────────────────────────────────────

    @Test
    fun pipe_roundTrips_data() {
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

    // ── End-to-end: real protobuf through pipe ──────────────────────────────

    @Test
    fun realProtobuf_throughPipe_roundTrips() {
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

        val pipe = ParcelFileDescriptor.createPipe()
        val readPfd = pipe[0]
        val writePfd = pipe[1]

        FileOutputStream(writePfd.fileDescriptor).use { it.write(protoBytes) }
        writePfd.close()

        val readBack = FileInputStream(readPfd.fileDescriptor).use { it.readBytes() }
        readPfd.close()

        assertEquals("Protobuf bytes should survive pipe round-trip",
            protoBytes.size, readBack.size)
        for (i in protoBytes.indices) {
            assertEquals("Byte $i", protoBytes[i], readBack[i])
        }
    }
}
