package com.writer.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal OGG/Opus muxer. Wraps encoded Opus packets from [android.media.MediaCodec]
 * into OGG pages with granule positions for sample-accurate seeking.
 *
 * OGG page format (RFC 3533):
 * - 27-byte header with capture pattern, granule position, serial, page/segment counts
 * - Segment table (1 byte per segment, values 0-255)
 * - Payload (concatenated segments)
 *
 * Opus in OGG (RFC 7845):
 * - Page 0: OpusHead header (identification)
 * - Page 1: OpusTags header (metadata)
 * - Page 2+: Audio data pages, each with granule position = cumulative sample count
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) {
    private var serialNo = (System.nanoTime() and 0xFFFFFFFFL).toInt()
    private var pageSequence = 0
    private var granulePosition = 0L
    private var closed = false

    /** Write the required OGG/Opus headers. Call once before any audio packets. */
    fun writeHeaders() {
        // Page 0: OpusHead
        val opusHead = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())  // magic
            put(1)                          // version
            put(channels.toByte())          // channel count
            putShort(0)                     // pre-skip (samples)
            putInt(sampleRate)              // input sample rate
            putShort(0)                     // output gain
            put(0)                          // channel mapping family
        }
        writePage(opusHead.array(), granulePos = 0, flags = FLAG_BOS)

        // Page 1: OpusTags
        val vendor = "InkUp"
        val tagsSize = 8 + 4 + vendor.length + 4
        val opusTags = ByteBuffer.allocate(tagsSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray())
            putInt(vendor.length)
            put(vendor.toByteArray())
            putInt(0) // no user comments
        }
        writePage(opusTags.array(), granulePos = 0, flags = 0)
    }

    /**
     * Write an encoded Opus packet. [presentationTimeUs] is used to compute
     * the granule position (cumulative samples at 48kHz, per RFC 7845).
     *
     * @param data encoded Opus packet bytes
     * @param presentationTimeUs presentation timestamp in microseconds
     */
    fun writePacket(data: ByteArray, presentationTimeUs: Long) {
        // Granule position is in 48kHz samples regardless of input sample rate (RFC 7845 §4)
        granulePosition = presentationTimeUs * 48 / 1000 // µs → 48kHz samples
        writePage(data, granulePos = granulePosition, flags = 0)
    }

    /** Write the final page and close. */
    fun close() {
        if (closed) return
        // Write an empty EOS page
        writePage(ByteArray(0), granulePos = granulePosition, flags = FLAG_EOS)
        out.flush()
        closed = true
    }

    private fun writePage(payload: ByteArray, granulePos: Long, flags: Int) {
        // Segment table: each segment is max 255 bytes.
        // A packet is terminated by a segment < 255 bytes (or a 0-length segment).
        val segments = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining) // final segment (0-254), terminates the packet

        val header = ByteBuffer.allocate(27 + segments.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OggS".toByteArray())       // capture pattern
            put(0)                           // stream structure version
            put(flags.toByte())              // header type flags
            putLong(granulePos)              // granule position
            putInt(serialNo)                 // bitstream serial number
            putInt(pageSequence++)           // page sequence number
            putInt(0)                        // CRC checksum (filled below)
            put(segments.size.toByte())      // number of segments
            for (s in segments) put(s.toByte())
        }

        // Compute CRC-32 over header + payload
        val headerBytes = header.array()
        val crc = oggCrc32(headerBytes, payload)
        headerBytes[22] = (crc and 0xFF).toByte()
        headerBytes[23] = ((crc shr 8) and 0xFF).toByte()
        headerBytes[24] = ((crc shr 16) and 0xFF).toByte()
        headerBytes[25] = ((crc shr 24) and 0xFF).toByte()

        out.write(headerBytes)
        out.write(payload)
    }

    companion object {
        private const val FLAG_BOS = 0x02  // beginning of stream
        private const val FLAG_EOS = 0x04  // end of stream

        // OGG uses a custom CRC-32 polynomial (0x04C11DB7), not the standard zlib one.
        private val crcTable = IntArray(256).also { table ->
            for (i in 0..255) {
                var r = i shl 24
                for (j in 0..7) {
                    r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7
                    else r shl 1
                }
                table[i] = r
            }
        }

        private fun oggCrc32(header: ByteArray, payload: ByteArray): Int {
            var crc = 0
            for (b in header) {
                crc = (crc shl 8) xor crcTable[((crc ushr 24) and 0xFF) xor (b.toInt() and 0xFF)]
            }
            for (b in payload) {
                crc = (crc shl 8) xor crcTable[((crc ushr 24) and 0xFF) xor (b.toInt() and 0xFF)]
            }
            return crc
        }
    }
}
