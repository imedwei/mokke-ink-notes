package com.writer.recognition

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Manages the Sherpa-ONNX [OnlineRecognizer] lifecycle.
 *
 * The recognizer is expensive to create (~2s for ORT models, 221 MB native memory)
 * but cheap to reuse across recording sessions. This manager pre-loads the recognizer
 * on demand, keeps it resident between recordings, and releases on background.
 */
class SherpaModelManager {

    enum class State { UNLOADED, LOADING, READY, ERROR }

    @Volatile var state: State = State.UNLOADED
        private set

    /** Called on the loading thread when the model becomes READY. */
    var onReady: (() -> Unit)? = null

    /** Status updates during download/load. Called from background thread. */
    var onStatusUpdate: ((String) -> Unit)? = null

    private var recognizer: StreamingRecognizer? = null

    fun getRecognizer(): StreamingRecognizer? =
        if (state == State.READY) recognizer else null

    /**
     * Load using an injected factory (for testing without real models).
     * Blocks on the calling thread.
     */
    fun loadWithFactory(factory: () -> StreamingRecognizer) {
        if (state == State.READY) return
        state = State.LOADING
        try {
            recognizer = factory()
            state = State.READY
            onReady?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
            state = State.ERROR
        }
    }

    /** Pre-load models asynchronously. Downloads if needed, then creates the recognizer. */
    fun preload(context: Context) {
        if (state == State.READY || state == State.LOADING) return
        Log.i(TAG, "Preloading from state=$state")
        state = State.LOADING
        Thread({
            try {
                val modelDir = ModelDownloader.ensureModelFiles(
                    File(context.filesDir, MODEL_DIR), MODEL_BASE_URL, MODEL_FILES, onStatusUpdate
                )
                onStatusUpdate?.invoke("Loading models…")
                val config = buildConfig(modelDir)
                recognizer = SherpaRecognizerWrapper(OnlineRecognizer(config = config))
                state = State.READY
                Log.i(TAG, "Sherpa model ready")
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa model load failed", e)
                state = State.ERROR
                val reason = when (e) {
                    is java.net.UnknownHostException -> "No internet connection"
                    is java.net.SocketTimeoutException -> "Download timed out"
                    is java.io.IOException -> "Download failed: ${e.message}"
                    else -> "Model load failed: ${e.message}"
                }
                onStatusUpdate?.invoke(reason)
            }
        }, "SherpaModelLoad").start()
    }

    /** Release the recognizer and free ~221 MB native memory. */
    fun release() {
        val rec = recognizer
        recognizer = null
        state = State.UNLOADED
        if (rec is StreamingRecognizer) {
            try { rec.release() } catch (e: Exception) {
                Log.w(TAG, "Error releasing recognizer", e)
            }
        }
    }

    private fun buildConfig(modelDir: File): OnlineRecognizerConfig =
        OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, ENCODER).absolutePath,
                    decoder = File(modelDir, DECODER).absolutePath,
                    joiner = File(modelDir, JOINER).absolutePath
                ),
                tokens = File(modelDir, TOKENS).absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )

    companion object {
        private const val TAG = "SherpaModelManager"
        private const val MODEL_DIR = "sherpa_models"
        private const val MODEL_BASE_URL =
            "https://huggingface.co/w11wo/sherpa-onnx-ort-streaming-zipformer-en-2023-06-26/resolve/main"

        private const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.ort"
        private const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.int8.ort"
        private const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.ort"
        private const val TOKENS = "tokens.txt"

        private val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)
    }
}
