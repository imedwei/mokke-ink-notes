package com.writer.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Manages audio recording to .opus files via [MediaRecorder].
 *
 * Usage:
 * ```
 * val manager = AudioCaptureManager(context)
 * val file = manager.start("rec-001")
 * // ... recording ...
 * manager.stop()
 * // file now contains the recorded audio
 * ```
 */
class AudioCaptureManager(private val context: Context) {

    private val tag = "AudioCaptureManager"

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording: Boolean = false
        private set

    /** Directory for temporary audio files during recording. */
    private fun audioDir(): File {
        val dir = File(context.cacheDir, "audio_recording")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Start recording audio to a temp file.
     * @param baseName base filename without extension (e.g. "rec-001")
     * @return the output File that will contain the recording
     */
    fun start(baseName: String): File {
        if (isRecording) stop()

        val file = File(audioDir(), "$baseName.opus")
        outputFile = file

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioEncodingBitRate(16000)
            setAudioSamplingRate(16000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        this.recorder = recorder
        isRecording = true
        Log.i(tag, "Recording started: ${file.name}")
        return file
    }

    /** Stop recording. The file returned from [start] now contains the audio. */
    fun stop() {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            Log.w(tag, "Error stopping recorder", e)
        }
        recorder?.release()
        recorder = null
        isRecording = false
        Log.i(tag, "Recording stopped: ${outputFile?.name}")
    }

    /** Release resources without saving. */
    fun release() {
        stop()
        outputFile = null
    }

    /** Get the current output file, or null if not recording. */
    fun getOutputFile(): File? = outputFile

    /** Read the recorded audio bytes. Returns null if no recording exists. */
    fun readRecordedBytes(): ByteArray? = outputFile?.takeIf { it.exists() }?.readBytes()

    /** Generate a unique recording filename based on timestamp. */
    companion object {
        fun generateRecordingName(): String = "rec-${System.currentTimeMillis()}"
    }
}
