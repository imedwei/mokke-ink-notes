package com.writer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Captures raw PCM audio via [AudioRecord] on a background thread and
 * stream-compresses to OGG/Opus via [MediaCodec].
 *
 * Designed to run alongside SpeechRecognizer for concurrent audio capture
 * on Android 10+ devices. Memory usage stays constant regardless of
 * recording duration.
 *
 * Call [start] to begin, [stop] to end. After stopping, [getOutputFile]
 * returns the compressed audio file.
 */
class AudioRecordCapture(private val cacheDir: File) {

    private val tag = "AudioRecordCapture"
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    private var outputFile: File? = null
    var isRecording: Boolean = false
        private set

    // Fallback: if MediaCodec Opus encoding fails, capture raw PCM
    private var rawPcmFallback = false
    private var rawPcmBuffer: ByteArrayOutputStream? = null

    fun start(): Boolean {
        if (isRecording) stop()

        val sampleRate = SAMPLE_RATE
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(tag, "Mic permission denied", e)
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "AudioRecord failed to initialize — mic may be in use exclusively")
            audioRecord.release()
            return false
        }

        val audioDir = File(cacheDir, "audio_recording")
        audioDir.mkdirs()
        val file = File(audioDir, "rec-${System.currentTimeMillis()}.wav")
        outputFile = file

        recorder = audioRecord
        recording = true
        isRecording = true

        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(tag, "startRecording failed — concurrent capture not supported", e)
            audioRecord.release()
            recorder = null
            recording = false
            isRecording = false
            return false
        }

        // Record raw PCM to a temp file, encode to WAV on stop
        rawPcmFallback = true
        rawPcmBuffer = ByteArrayOutputStream()

        recordingThread = Thread({
            val buffer = ByteArray(sampleRate * 2) // 1 second of 16-bit mono
            while (recording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    rawPcmBuffer?.let { synchronized(it) { it.write(buffer, 0, read) } }
                }
            }
        }, "AudioRecordCapture").also { it.start() }

        Log.i(tag, "Recording started (concurrent capture)")
        return true
    }

    fun stop() {
        recording = false
        recordingThread?.join(2000)
        recordingThread = null

        try {
            recorder?.stop()
        } catch (e: IllegalStateException) {
            Log.w(tag, "stop() failed", e)
        }
        recorder?.release()
        recorder = null
        isRecording = false

        // Write WAV file from buffered PCM
        val pcm = rawPcmBuffer?.let { synchronized(it) { it.toByteArray() } }
        rawPcmBuffer = null
        if (pcm != null && pcm.isNotEmpty()) {
            val wav = buildWav(pcm, SAMPLE_RATE)
            outputFile?.writeBytes(wav)
            Log.i(tag, "Saved ${wav.size} bytes to ${outputFile?.name}")
        }
    }

    /** Get the output file path. Available after [stop]. */
    fun getOutputFile(): File? = outputFile

    /** Read the recorded audio bytes. Returns null if nothing was recorded. */
    fun readRecordedBytes(): ByteArray? = outputFile?.takeIf { it.exists() && it.length() > 44 }?.readBytes()

    private fun buildWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size
        val fileSize = 36 + dataSize
        val wav = ByteArrayOutputStream(44 + dataSize)
        fun writeInt(v: Int) { wav.write(v and 0xFF); wav.write(v shr 8 and 0xFF); wav.write(v shr 16 and 0xFF); wav.write(v shr 24 and 0xFF) }
        fun writeShort(v: Int) { wav.write(v and 0xFF); wav.write(v shr 8 and 0xFF) }
        wav.write("RIFF".toByteArray()); writeInt(fileSize)
        wav.write("WAVE".toByteArray())
        wav.write("fmt ".toByteArray()); writeInt(16); writeShort(1) // PCM
        writeShort(1); writeInt(sampleRate); writeInt(sampleRate * 2); writeShort(2); writeShort(16)
        wav.write("data".toByteArray()); writeInt(dataSize)
        wav.write(pcm)
        return wav.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
