package com.writer.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class OggOpusWriterTest {

    @Test
    fun `headers produce valid OGG pages`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate = 16000, channels = 1)
        writer.writeHeaders()
        writer.close()

        val bytes = out.toByteArray()
        assertTrue("Should produce output", bytes.isNotEmpty())

        // First page starts with OggS capture pattern
        assertEquals('O'.code.toByte(), bytes[0])
        assertEquals('g'.code.toByte(), bytes[1])
        assertEquals('g'.code.toByte(), bytes[2])
        assertEquals('S'.code.toByte(), bytes[3])

        // First page has BOS flag (0x02) at offset 5
        assertEquals(0x02, bytes[5].toInt() and 0xFF)
    }

    @Test
    fun `OpusHead contains correct fields`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate = 16000, channels = 1)
        writer.writeHeaders()
        writer.close()

        val bytes = out.toByteArray()
        // Find OpusHead in the first page payload (after the OGG header)
        val segCount = bytes[26].toInt() and 0xFF
        val payloadStart = 27 + segCount

        val magic = String(bytes, payloadStart, 8)
        assertEquals("OpusHead", magic)

        // Version = 1
        assertEquals(1, bytes[payloadStart + 8].toInt())
        // Channels = 1
        assertEquals(1, bytes[payloadStart + 9].toInt())
    }

    @Test
    fun `writePacket produces page with granule position`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate = 16000, channels = 1)
        writer.writeHeaders()

        // Write a fake Opus packet at 1 second (1,000,000 µs)
        val fakePacket = ByteArray(50) { it.toByte() }
        writer.writePacket(fakePacket, presentationTimeUs = 1_000_000L)
        writer.close()

        val bytes = out.toByteArray()
        // Find the third OggS page (page 0 = OpusHead, page 1 = OpusTags, page 2 = audio)
        var pageStart = 0
        var pageCount = 0
        while (pageStart < bytes.size - 4) {
            if (bytes[pageStart] == 'O'.code.toByte() &&
                bytes[pageStart + 1] == 'g'.code.toByte() &&
                bytes[pageStart + 2] == 'g'.code.toByte() &&
                bytes[pageStart + 3] == 'S'.code.toByte()
            ) {
                pageCount++
                if (pageCount == 3) break // third page = first audio
                // Skip to next page
                val segs = bytes[pageStart + 26].toInt() and 0xFF
                var payloadSize = 0
                for (i in 0 until segs) payloadSize += bytes[pageStart + 27 + i].toInt() and 0xFF
                pageStart += 27 + segs + payloadSize
            } else {
                pageStart++
            }
        }
        assertEquals("Should have 3 pages (head, tags, audio)", 3, pageCount)

        // Granule position at offset 6 (8 bytes, little-endian)
        // 1,000,000 µs × 48 / 1000 = 48,000 samples
        val granule = java.nio.ByteBuffer.wrap(bytes, pageStart + 6, 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).long
        assertEquals("Granule should be 48000 for 1s at 48kHz", 48_000L, granule)
    }

    @Test
    fun `EOS page has EOS flag`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate = 16000, channels = 1)
        writer.writeHeaders()
        writer.writePacket(ByteArray(10), 500_000L)
        writer.close()

        val bytes = out.toByteArray()
        // Find last OggS page
        var lastPageStart = -1
        for (i in bytes.indices) {
            if (i + 3 < bytes.size &&
                bytes[i] == 'O'.code.toByte() && bytes[i + 1] == 'g'.code.toByte() &&
                bytes[i + 2] == 'g'.code.toByte() && bytes[i + 3] == 'S'.code.toByte()
            ) {
                lastPageStart = i
            }
        }
        assertTrue("Should find at least one page", lastPageStart >= 0)
        // EOS flag (0x04) at offset 5
        assertEquals("Last page should have EOS flag", 0x04, bytes[lastPageStart + 5].toInt() and 0x04)
    }

    @Test
    fun `multiple packets have increasing granule positions`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate = 16000, channels = 1)
        writer.writeHeaders()
        writer.writePacket(ByteArray(20), 500_000L)   // 0.5s
        writer.writePacket(ByteArray(20), 1_000_000L) // 1.0s
        writer.writePacket(ByteArray(20), 1_500_000L) // 1.5s
        writer.close()

        val bytes = out.toByteArray()
        val granules = mutableListOf<Long>()
        var i = 0
        while (i < bytes.size - 4) {
            if (bytes[i] == 'O'.code.toByte() && bytes[i + 1] == 'g'.code.toByte() &&
                bytes[i + 2] == 'g'.code.toByte() && bytes[i + 3] == 'S'.code.toByte()
            ) {
                val granule = java.nio.ByteBuffer.wrap(bytes, i + 6, 8)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                granules.add(granule)
                val segs = bytes[i + 26].toInt() and 0xFF
                var payloadSize = 0
                for (s in 0 until segs) payloadSize += bytes[i + 27 + s].toInt() and 0xFF
                i += 27 + segs + payloadSize
            } else {
                i++
            }
        }
        // Pages: OpusHead(0), OpusTags(0), audio(24000), audio(48000), audio(72000), EOS(72000)
        assertTrue("Should have at least 5 pages", granules.size >= 5)
        // Audio granules should be monotonically increasing
        val audioGranules = granules.drop(2).dropLast(1) // skip headers and EOS
        for (j in 1 until audioGranules.size) {
            assertTrue("Granules should increase: ${audioGranules[j-1]} < ${audioGranules[j]}",
                audioGranules[j] > audioGranules[j - 1])
        }
    }
}
