package com.writer.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal OGG/Opus muxer. Wraps encoded Opus packets from [android.media.MediaCodec]
 * into OGG pages with granule positions for sample-accurate seeking.
 *
 * Usage:
 * 1. Call [writeHeaders] with the CSD-0 bytes from MediaCodec's output format
 * 2. Call [writePacket] for each encoded Opus packet
 * 3. Call [close] to write EOS and flush
 *
 * The CSD-0 from Android's Opus encoder contains a valid OpusHead identification
 * header (RFC 7845 §5.1) with the correct pre-skip, channel count, and sample rate
 * for the configured encoder. Using it directly ensures decoder compatibility.
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) {
    private var serialNo = (System.nanoTime() and 0xFFFFFFFFL).toInt()
    private var pageSequence = 0
    private var granulePosition = 0L
    private var preSkip = DEFAULT_PRE_SKIP
    private var closed = false

    /**
     * Write the required OGG/Opus headers.
     *
     * @param csd0 CSD-0 bytes from MediaCodec's output format. If non-null, used as
     *             the OpusHead identification header. If null, a default header is generated.
     */
    fun writeHeaders(csd0: ByteArray? = null) {
        // Page 0: OpusHead
        val opusHead = if (csd0 != null && csd0.size >= 19 &&
            String(csd0, 0, 8, Charsets.US_ASCII) == "OpusHead"
        ) {
            // CSD-0 is already a valid OpusHead — use it directly
            preSkip = ByteBuffer.wrap(csd0, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            csd0
        } else {
            // Generate a default OpusHead
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray())
                put(1)                              // version
                put(channels.toByte())              // channel count
                putShort(DEFAULT_PRE_SKIP.toShort()) // pre-skip
                putInt(sampleRate)                   // input sample rate
                putShort(0)                          // output gain
                put(0)                               // channel mapping family
            }.array()
        }
        writePage(opusHead, granulePos = 0, flags = FLAG_BOS)

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
     * Write an encoded Opus packet.
     *
     * @param data encoded Opus packet bytes
     * @param presentationTimeUs presentation timestamp in microseconds
     */
    fun writePacket(data: ByteArray, presentationTimeUs: Long) {
        // Granule position = 48kHz samples + pre-skip (RFC 7845 §4)
        granulePosition = presentationTimeUs * 48 / 1000 + preSkip
        writePage(data, granulePos = granulePosition, flags = 0)
    }

    /** Write the final page and close. */
    fun close() {
        if (closed) return
        writePage(ByteArray(0), granulePos = granulePosition, flags = FLAG_EOS)
        out.flush()
        closed = true
    }

    private fun writePage(payload: ByteArray, granulePos: Long, flags: Int) {
        val segments = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining)

        val header = ByteBuffer.allocate(27 + segments.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OggS".toByteArray())
            put(0)
            put(flags.toByte())
            putLong(granulePos)
            putInt(serialNo)
            putInt(pageSequence++)
            putInt(0) // CRC placeholder
            put(segments.size.toByte())
            for (s in segments) put(s.toByte())
        }

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
        private const val FLAG_BOS = 0x02
        private const val FLAG_EOS = 0x04
        private const val DEFAULT_PRE_SKIP = 312 // 6.5ms at 48kHz

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
