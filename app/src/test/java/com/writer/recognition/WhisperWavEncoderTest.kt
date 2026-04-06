package com.writer.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for the WAV encoding used by WhisperTranscriber to save
 * recorded audio into the document ZIP bundle.
 */
class WhisperWavEncoderTest {

    /** Encode float PCM samples to WAV bytes (same logic as WhisperTranscriber). */
    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            pcm[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        val dataSize = pcm.size
        val fileSize = 36 + dataSize
        val header = java.io.ByteArrayOutputStream(44)
        fun writeInt(v: Int) { header.write(v and 0xFF); header.write(v shr 8 and 0xFF); header.write(v shr 16 and 0xFF); header.write(v shr 24 and 0xFF) }
        fun writeShort(v: Int) { header.write(v and 0xFF); header.write(v shr 8 and 0xFF) }
        header.write("RIFF".toByteArray()); writeInt(fileSize)
        header.write("WAVE".toByteArray())
        header.write("fmt ".toByteArray()); writeInt(16); writeShort(1)
        writeShort(1); writeInt(sampleRate); writeInt(sampleRate * 2); writeShort(2); writeShort(16)
        header.write("data".toByteArray()); writeInt(dataSize)
        return header.toByteArray() + pcm
    }

    @Test
    fun `WAV header has correct RIFF structure`() {
        val wav = encodeWav(FloatArray(100), 16000)
        val riff = String(wav, 0, 4)
        val wave = String(wav, 8, 4)
        val fmt = String(wav, 12, 4)
        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)
        assertEquals("fmt ", fmt)
    }

    @Test
    fun `WAV header has correct size fields`() {
        val samples = FloatArray(160) // 10ms at 16kHz
        val wav = encodeWav(samples, 16000)
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        // File size = total - 8 (RIFF header)
        buf.position(4)
        val fileSize = buf.int
        assertEquals(wav.size - 8, fileSize)

        // Data chunk size = samples * 2 bytes per sample
        buf.position(40)
        val dataSize = buf.int
        assertEquals(samples.size * 2, dataSize)
    }

    @Test
    fun `WAV header has correct format fields`() {
        val wav = encodeWav(FloatArray(100), 16000)
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        buf.position(20); assertEquals(1, buf.short.toInt())  // PCM format
        buf.position(22); assertEquals(1, buf.short.toInt())  // mono
        buf.position(24); assertEquals(16000, buf.int)        // sample rate
        buf.position(28); assertEquals(32000, buf.int)        // byte rate (16000 * 2)
        buf.position(32); assertEquals(2, buf.short.toInt())  // block align
        buf.position(34); assertEquals(16, buf.short.toInt()) // bits per sample
    }

    @Test
    fun `WAV total size is 44 header plus PCM data`() {
        val samples = FloatArray(500)
        val wav = encodeWav(samples, 16000)
        assertEquals(44 + samples.size * 2, wav.size)
    }

    @Test
    fun `silence encodes to zero bytes`() {
        val wav = encodeWav(FloatArray(10), 16000) // all zeros
        // PCM data starts at byte 44
        for (i in 44 until wav.size) {
            assertEquals("PCM should be zero for silence", 0.toByte(), wav[i])
        }
    }

    @Test
    fun `max amplitude encodes correctly`() {
        val samples = floatArrayOf(1.0f, -1.0f)
        val wav = encodeWav(samples, 16000)
        val buf = ByteBuffer.wrap(wav, 44, 4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767, buf.short.toInt())    // +1.0 → 32767
        assertEquals(-32767, buf.short.toInt())   // -1.0 → -32767 (symmetric clamp)
    }

    @Test
    fun `round-trip preserves sample values within quantization`() {
        val original = floatArrayOf(0.0f, 0.5f, -0.5f, 0.25f, -0.25f)
        val wav = encodeWav(original, 16000)
        val buf = ByteBuffer.wrap(wav, 44, original.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in original.indices) {
            val pcmValue = buf.short.toInt()
            val recovered = pcmValue / 32767f
            assertEquals("Sample $i", original[i].toDouble(), recovered.toDouble(), 0.001)
        }
    }

    @Test
    fun `empty audio produces valid WAV`() {
        val wav = encodeWav(FloatArray(0), 16000)
        assertEquals(44, wav.size) // Header only
        assertEquals("RIFF", String(wav, 0, 4))
    }
}
