package com.writer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Captures audio via [AudioRecord] and stream-compresses to Opus in an
 * OGG container via [MediaCodec] + [OggOpusWriter].
 *
 * Opus at 24kbps mono ≈ 180 KB/min (~11 MB/hr). Best voice codec at low bitrates.
 *
 * OGG provides sample-accurate seeking via granule positions — no estimation needed.
 * Each page is self-contained, so a truncated file is playable up to the last page.
 */
class AudioRecordCapture(private val cacheDir: File) {

    private val tag = "AudioRecordCapture"
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var oggWriter: OggOpusWriter? = null
    private var outputStream: BufferedOutputStream? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    private var outputFile: File? = null
    private var startTimeUs = 0L
    private var headersWritten = false
    var isRecording: Boolean = false
        private set

    fun start(): Boolean {
        if (isRecording) stop()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(tag, "Mic permission denied", e); return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "AudioRecord failed to initialize"); audioRecord.release(); return false
        }

        if (!initEncoder()) { audioRecord.release(); return false }

        recorder = audioRecord
        recording = true
        isRecording = true

        try { audioRecord.startRecording() } catch (e: IllegalStateException) {
            Log.w(tag, "startRecording failed", e); cleanup(); return false
        }

        recordingThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = ShortArray(SAMPLE_RATE)
            val codec = encoder ?: return@Thread

            while (recording) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        inputBuffer.clear()
                        for (i in 0 until read) inputBuffer.putShort(pcmBuffer[i])
                        codec.queueInputBuffer(inputIndex, 0, read * 2, System.nanoTime() / 1000 - startTimeUs, 0)
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
                drainEncoder(bufferInfo, false)
            }

            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(bufferInfo, true)
        }, "AudioRecordCapture").also { it.start() }

        Log.i(tag, "Recording started (Opus/OGG)")
        return true
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val codec = encoder ?: return
        val ogg = oggWriter ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Extract CSD-0 (OpusHead) from the encoder's output format
                    val outputFormat = codec.outputFormat
                    val csd0 = outputFormat.getByteBuffer("csd-0")?.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it); buf.rewind() }
                    }
                    if (!headersWritten) {
                        ogg.writeHeaders(csd0)
                        headersWritten = true
                        Log.i(tag, "OGG headers written (csd0=${csd0?.size ?: 0} bytes)")
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        headersWritten
                    ) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(data)
                        ogg.writePacket(data, bufferInfo.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> break
            }
        }
    }

    fun stop() {
        recording = false
        recordingThread?.join(5000)
        recordingThread = null
        cleanup()
        Log.i(tag, "Recording stopped: ${outputFile?.name} (${outputFile?.length() ?: 0} bytes)")
    }

    private fun cleanup() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        try { encoder?.stop() } catch (_: Exception) {}
        encoder?.release(); encoder = null
        try { oggWriter?.close() } catch (_: Exception) {}
        oggWriter = null
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        isRecording = false
    }

    fun startEncoderOnly(): Boolean {
        if (isRecording) stop()
        return initEncoder()
    }

    private fun initEncoder(): Boolean {
        val audioDir = File(cacheDir, "audio_recording")
        audioDir.mkdirs()
        val file = File(audioDir, "rec-${System.currentTimeMillis()}.ogg")
        outputFile = file

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE * 2)

        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (e: Exception) { Log.w(tag, "Failed to create Opus encoder", e); return false }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) { Log.w(tag, "Failed to configure encoder", e); codec.release(); return false }

        val fos = try {
            BufferedOutputStream(FileOutputStream(file))
        } catch (e: Exception) { Log.w(tag, "Failed to open output file", e); codec.release(); return false }

        encoder = codec
        oggWriter = OggOpusWriter(fos, SAMPLE_RATE, 1)
        outputStream = fos
        headersWritten = false
        startTimeUs = System.nanoTime() / 1000
        isRecording = true
        codec.start()
        Log.i(tag, "Encoder started (Opus/OGG)")
        return true
    }

    fun feedPcm(buffer: ByteArray, length: Int) {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(buffer, 0, length)
            codec.queueInputBuffer(inputIndex, 0, length, System.nanoTime() / 1000 - startTimeUs, 0)
        }
        drainEncoder(bufferInfo, false)
    }

    fun stopEncoder() {
        val codec = encoder ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        val bufferInfo = MediaCodec.BufferInfo()
        drainEncoder(bufferInfo, true)
        cleanup()
        Log.i(tag, "Encoder stopped: ${outputFile?.name} (${outputFile?.length() ?: 0} bytes)")
    }

    fun getOutputFile(): File? = outputFile
    fun readRecordedBytes(): ByteArray? = outputFile?.takeIf { it.exists() && it.length() > 0 }?.readBytes()

    companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 24000
    }
}
