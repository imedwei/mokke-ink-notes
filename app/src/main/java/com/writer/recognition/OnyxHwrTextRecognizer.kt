package com.writer.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.onyx.android.sdk.hwr.service.HWRInputArgs
import com.onyx.android.sdk.hwr.service.HWROutputArgs
import com.onyx.android.sdk.hwr.service.HWROutputCallback
import com.onyx.android.sdk.hwr.service.IHWRService
import com.writer.model.InkLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Handwriting recognition using the Boox firmware's built-in MyScript engine.
 * Communicates via AIDL IPC with `com.onyx.android.ksync.service.KHwrService`.
 *
 * Each activity should create its own instance via [TextRecognizerFactory.create]
 * and close it in `onDestroy`.
 *
 * Based on the approach used by [Notable](https://github.com/jshph/notable).
 */
class OnyxHwrTextRecognizer(private val context: Context) : TextRecognizer {

    companion object {
        private const val TAG = "OnyxHwrTextRecognizer"
        private const val SERVICE_PACKAGE = "com.onyx.android.ksync"
        private const val SERVICE_CLASS = "com.onyx.android.ksync.service.KHwrService"
        private const val BIND_TIMEOUT_MS = 3000L
        private const val RECOGNIZE_TIMEOUT_MS = 10_000L
    }

    @Volatile private var service: IHWRService? = null
    @Volatile private var bound = false
    @Volatile private var initialized = false
    private var connectLatch = CountDownLatch(1)
    private val initMutex = Mutex()
    private var currentLang = "en_US"

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHWRService.Stub.asInterface(binder)
            bound = true
            Log.i(TAG, "HWR service connected")
            connectLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            initialized = false
            Log.w(TAG, "HWR service disconnected")
        }
    }

    override suspend fun initialize(languageTag: String) {
        initMutex.withLock {
            if (bound && service != null) return@withLock

            val latch = CountDownLatch(1)
            connectLatch = latch
            currentLang = languageTag.replace("-", "_")
            val intent = Intent().apply {
                component = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)
            }

            val bindStarted = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind HWR service: ${e.message}")
                throw IllegalStateException("Onyx HWR service not available", e)
            }

            if (!bindStarted) {
                throw IllegalStateException("Onyx HWR service not found — is this a Boox device?")
            }

            val connected = try {
                withContext(Dispatchers.IO) {
                    latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                context.unbindService(connection)
                throw e
            }
            if (!connected || service == null) {
                context.unbindService(connection)
                throw IllegalStateException("Onyx HWR service bind timed out")
            }

            val svc = service!!
            val inputArgs = HWRInputArgs().apply {
                lang = currentLang
                contentType = "Text"
                recognizerType = "MS_ON_SCREEN"
                viewWidth = 1000f   // arbitrary default; per-recognition dimensions are in the protobuf
                viewHeight = 200f
                isTextEnable = true
            }

            suspendCancellableCoroutine { cont ->
                svc.init(inputArgs, true, object : HWROutputCallback.Stub() {
                    override fun read(args: HWROutputArgs?) {
                        initialized = args?.recognizerActivated == true
                        Log.i(TAG, "HWR init: activated=$initialized")
                        cont.resume(Unit)
                    }
                })
            }

            if (!initialized) {
                close()
                throw IllegalStateException("MyScript recognizer failed to activate")
            }
            Log.i(TAG, "Recognizer initialized for $languageTag")
        }
    }

    // Note: preContext is accepted by the interface but not used here —
    // MyScript context is set via HWRInputArgs on init, not per-recognition.
    override suspend fun recognizeLine(line: InkLine, preContext: String): String {
        val svc = service ?: return ""
        if (!initialized) return ""
        if (line.strokes.isEmpty()) return ""

        val bb = line.boundingBox
        val viewWidth = if (bb.width() > 0) bb.width() else 1000f
        val viewHeight = if (bb.height() > 0) bb.height() else 200f

        val protoBytes = HwrProtobuf.buildProtobuf(line, viewWidth, viewHeight, currentLang)

        val pfd = createPipePfd(protoBytes) ?: return ""

        return try {
            val result = withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    svc.batchRecognize(pfd, object : HWROutputCallback.Stub() {
                        override fun read(args: HWROutputArgs?) {
                            try {
                                // Boox API: hwrResult is populated only on error; on success it's null and pfd carries the result
                                val errorJson = args?.hwrResult
                                if (!errorJson.isNullOrBlank()) {
                                    Log.e(TAG, "HWR error: ${errorJson.take(300)}")
                                    cont.resume("")
                                    return
                                }
                                val resultPfd = args?.pfd
                                if (resultPfd == null) {
                                    cont.resume("")
                                    return
                                }
                                val json = readPfdAsString(resultPfd)
                                resultPfd.close()
                                val text = HwrProtobuf.parseHwrResult(json)
                                Log.d(TAG, "Recognized: \"$text\"")
                                cont.resume(text)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing HWR result: ${e.message}")
                                cont.resume("")
                            }
                        }
                    })
                }
            }
            result ?: ""
        } finally {
            pfd.close()
        }
    }

    override fun close() {
        if (!bound) return
        try {
            // closeRecognizer() is oneway (fire-and-forget); unbindService follows immediately
            // so the remote recognizer may not process the close before the transport tears down
            service?.closeRecognizer()
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HWR service: ${e.message}")
        }
        bound = false
        service = null
        initialized = false
    }

    private fun readPfdAsString(pfd: ParcelFileDescriptor): String {
        return FileInputStream(pfd.fileDescriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    internal fun createPipePfd(data: ByteArray): ParcelFileDescriptor? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            FileOutputStream(pipe[1].fileDescriptor).use { it.write(data) }
            pipe[1].close()
            pipe[0]  // read end for the service
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create pipe PFD: ${e.message}")
            null
        }
    }
}
