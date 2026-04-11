package com.writer.recognition

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL

/**
 * Manages the Sherpa-ONNX offline [OfflineRecognizer] lifecycle.
 *
 * The offline model is larger (~180 MB) with a longer load time (~9s ONNX, ~3s ORT).
 * It provides much better accuracy (0.5% WER vs 11.2% for streaming) and is used
 * as the second pass in two-pass transcription.
 *
 * Same state machine pattern as [SherpaModelManager].
 */
class OfflineModelManager {

    enum class State { UNLOADED, LOADING, READY, ERROR }

    @Volatile var state: State = State.UNLOADED
        private set

    /** Called on the loading thread when the model becomes READY. */
    var onReady: (() -> Unit)? = null

    private var recognizer: OfflineRecognizer? = null

    fun getRecognizer(): OfflineRecognizer? =
        if (state == State.READY) recognizer else null

    /**
     * Load using an injected factory (for testing without real models).
     * Blocks on the calling thread.
     */
    fun loadWithFactory(factory: () -> OfflineRecognizer) {
        if (state == State.READY) return
        state = State.LOADING
        try {
            recognizer = factory()
            state = State.READY
            onReady?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Offline model load failed", e)
            state = State.ERROR
        }
    }

    /** Pre-load models asynchronously. Downloads if needed, then creates the recognizer. */
    fun preload(context: Context) {
        if (state == State.READY || state == State.LOADING) return
        state = State.LOADING
        Thread({
            try {
                val modelDir = ensureModelFiles(context)
                val config = buildConfig(modelDir)
                recognizer = SherpaOfflineRecognizerWrapper(
                    com.k2fsa.sherpa.onnx.OfflineRecognizer(config = config)
                )
                state = State.READY
                Log.i(TAG, "Offline model ready")
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Offline model load failed", e)
                state = State.ERROR
            }
        }, "OfflineModelLoad").start()
    }

    /** Release the recognizer and free native memory. */
    fun release() {
        val rec = recognizer
        recognizer = null
        state = State.UNLOADED
        if (rec != null) {
            try { rec.release() } catch (e: Exception) {
                Log.w(TAG, "Error releasing offline recognizer", e)
            }
        }
    }

    private fun buildConfig(modelDir: File): com.k2fsa.sherpa.onnx.OfflineRecognizerConfig =
        com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = File(modelDir, ENCODER).absolutePath,
                    decoder = File(modelDir, DECODER).absolutePath,
                    joiner = File(modelDir, JOINER).absolutePath
                ),
                tokens = File(modelDir, TOKENS).absolutePath,
                numThreads = 2
            )
        )

    private fun ensureModelFiles(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
        for (name in MODEL_FILES) {
            val file = File(dir, name)
            if (file.exists() && SherpaModelManager.isValidModelFile(file, name)) continue
            if (file.exists()) {
                Log.w(TAG, "Deleting invalid model file $name (${file.length()} bytes)")
                file.delete()
            }
            Log.i(TAG, "Downloading offline model $name...")
            val tmpFile = File(dir, "$name.tmp")
            URL("$MODEL_BASE_URL/$name").openStream().use { input ->
                tmpFile.outputStream().use { input.copyTo(it) }
            }
            if (!SherpaModelManager.isValidModelFile(tmpFile, name)) {
                tmpFile.delete()
                throw IllegalStateException("Downloaded $name is invalid (${tmpFile.length()} bytes)")
            }
            tmpFile.renameTo(file)
        }
        return dir
    }

    companion object {
        private const val TAG = "OfflineModelManager"
        private const val MODEL_DIR = "sherpa_offline_models"
        private const val MODEL_BASE_URL =
            "https://huggingface.co/imedemi/sherpa-onnx-ort-zipformer-gigaspeech-2023-12-12/resolve/main"

        private const val ENCODER = "encoder-epoch-30-avg-1.int8.ort"
        private const val DECODER = "decoder-epoch-30-avg-1.int8.ort"
        private const val JOINER = "joiner-epoch-30-avg-1.int8.ort"
        private const val TOKENS = "tokens.txt"

        private val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)
    }
}
