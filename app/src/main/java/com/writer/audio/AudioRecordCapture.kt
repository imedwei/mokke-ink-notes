package com.writer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Captures audio via [AudioRecord] and stream-compresses to AAC in an MP4
 * container via [MediaCodec] + [MediaMuxer].
 *
 * Opus encoding isn't supported by [MediaMuxer] (no OGG container), so we
 * use AAC-LC at 32kbps mono — roughly 240 KB/min, ~14 MB/hr. Good enough
 * for voice playback and re-transcription.
 *
 * Designed to run concurrently alongside SpeechRecognizer on Android 10+.
 */
class AudioRecordCapture(private val cacheDir: File) {

    private val tag = "AudioRecordCapture"
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    private var outputFile: File? = null
    private var trackIndex = -1
    private var muxerStarted = false
    var isRecording: Boolean = false
        private set

    fun start(): Boolean {
        if (isRecording) stop()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(tag, "Mic permission denied", e)
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "AudioRecord failed to initialize")
            audioRecord.release()
            return false
        }

        // Set up output file
        val audioDir = File(cacheDir, "audio_recording")
        audioDir.mkdirs()
        val file = File(audioDir, "rec-${System.currentTimeMillis()}.m4a")
        outputFile = file

        // Set up AAC encoder
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE * 2) // 1 second

        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.w(tag, "Failed to create AAC encoder", e)
            audioRecord.release()
            return false
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(tag, "Failed to configure AAC encoder", e)
            codec.release()
            audioRecord.release()
            return false
        }

        val mux = try {
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.w(tag, "Failed to create MediaMuxer", e)
            codec.release()
            audioRecord.release()
            return false
        }

        recorder = audioRecord
        encoder = codec
        muxer = mux
        trackIndex = -1
        muxerStarted = false
        recording = true
        isRecording = true

        codec.start()

        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(tag, "startRecording failed — concurrent capture not supported", e)
            cleanup()
            return false
        }

        recordingThread = Thread({
            encodeLoop(audioRecord, codec, mux)
        }, "AudioRecordCapture").also { it.start() }

        Log.i(tag, "Recording started (AAC compressed)")
        return true
    }

    private fun encodeLoop(audioRecord: AudioRecord, codec: MediaCodec, mux: MediaMuxer) {
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuffer = ShortArray(SAMPLE_RATE) // 1 second chunks

        while (recording) {
            // Feed PCM to encoder
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (read > 0) {
                    inputBuffer.clear()
                    for (i in 0 until read) {
                        inputBuffer.putShort(pcmBuffer[i])
                    }
                    codec.queueInputBuffer(inputIndex, 0, read * 2, System.nanoTime() / 1000, 0)
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }

            // Drain encoder output
            drainEncoder(codec, mux, bufferInfo, false)
        }

        // Signal end of stream
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainEncoder(codec, mux, bufferInfo, true)
    }

    private fun drainEncoder(codec: MediaCodec, mux: MediaMuxer, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = mux.addTrack(codec.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, outputBuffer, bufferInfo)
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
        recorder?.release()
        recorder = null
        try { encoder?.stop() } catch (_: Exception) {}
        encoder?.release()
        encoder = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        muxer?.release()
        muxer = null
        isRecording = false
    }

    /** Get the output file. Available after [stop]. */
    fun getOutputFile(): File? = outputFile

    /** Read the compressed audio bytes. Returns null if nothing was recorded. */
    fun readRecordedBytes(): ByteArray? = outputFile?.takeIf { it.exists() && it.length() > 0 }?.readBytes()

    companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 32000 // 32 kbps — ~240 KB/min for voice
    }
}
