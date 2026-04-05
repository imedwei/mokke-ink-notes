package com.writer.recognition

import android.os.Build
import android.util.Log

private const val TAG = "WhisperLib"

/**
 * JNI bindings for whisper.cpp.
 * Loads the native library and provides low-level transcription methods.
 */
internal class WhisperLib {
    companion object {
        init {
            Log.d(TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            if (Build.SUPPORTED_ABIS[0] == "arm64-v8a") {
                val cpuInfo = try {
                    java.io.File("/proc/cpuinfo").readText()
                } catch (_: Exception) { "" }
                if (cpuInfo.contains("fphp")) {
                    Log.d(TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                } else {
                    Log.d(TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            } else {
                Log.d(TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
    }
}
