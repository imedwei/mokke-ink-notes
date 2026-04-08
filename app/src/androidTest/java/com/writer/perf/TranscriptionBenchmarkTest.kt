package com.writer.perf

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.writer.recognition.WhisperLib
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Benchmark comparing Vosk, sherpa-onnx, and Whisper speech-to-text engines.
 *
 * Downloads LibriSpeech test-clean utterances on first run (streaming tar extraction,
 * only downloads first ~10-20MB). Results are cached for subsequent runs.
 *
 * Metrics: model load time, model size, RTF (median of 3 runs), WER (word-level Levenshtein).
 *
 * Run:
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.writer.perf.TranscriptionBenchmarkTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionBenchmarkTest {

    companion object {
        private const val TAG = "TransBenchmark"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 2000 // 0.125s at 16kHz
        private const val BENCHMARK_RUNS = 3
        private const val UTTS_PER_SPEAKER = 3
        private const val TARGET_SPEAKERS = 4

        private const val LIBRISPEECH_URL =
            "https://www.openslr.org/resources/12/test-clean.tar.gz"

        private const val SHERPA_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main"
        private val SHERPA_FILES = listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt"
        )

        private const val VOSK_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val VOSK_DIR = "vosk-model-small-en-us-0.15"

        private const val WHISPER_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val benchDir by lazy { File(context.filesDir, "benchmark").also { it.mkdirs() } }
    private val utterances by lazy { loadTestUtterances() }

    // ── Data classes ────────────────────────────────────────────────

    data class Utterance(
        val id: String,
        val speakerId: String,
        val pcm: FloatArray,
        val groundTruth: String
    ) {
        val durationSec: Float get() = pcm.size.toFloat() / SAMPLE_RATE
    }

    data class EngineResult(
        val engine: String,
        val modelLoadMs: Long,
        val modelSizeBytes: Long,
        val rtfRuns: List<Float>,
        val rtfMedian: Float,
        val werPerUtterance: List<Pair<String, Float>>,
        val werAggregate: Float
    )

    // ── WER (word-level Levenshtein) ────────────────────────────────

    private fun wordErrorRate(reference: String, hypothesis: String): Float {
        val r = normalize(reference).split(" ").filter { it.isNotEmpty() }
        val h = normalize(hypothesis).split(" ").filter { it.isNotEmpty() }
        if (r.isEmpty()) return if (h.isEmpty()) 0f else 1f
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in d.indices) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            d[i][j] = minOf(
                d[i - 1][j] + 1,       // deletion
                d[i][j - 1] + 1,       // insertion
                d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1  // substitution
            )
        }
        return d[r.size][h.size].toFloat() / r.size
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── Audio utilities ─────────────────────────────────────────────

    private fun floatToBytes(pcm: FloatArray): ByteArray {
        val buf = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            buf[i * 2] = (s.toInt() and 0xFF).toByte()
            buf[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        return buf
    }

    private fun decodeFlacToPcm(flacFile: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(flacFile.absolutePath)
        require(extractor.trackCount > 0) { "No tracks in ${flacFile.name}" }
        extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val samples = mutableListOf<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    codec.releaseOutputBuffer(outIdx, false)
                    break
                }
                val outBuf = codec.getOutputBuffer(outIdx)!!
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val shorts = ShortArray(outBuf.remaining())
                outBuf.get(shorts)
                for (s in shorts) samples.add(s.toFloat() / 32768f)
                codec.releaseOutputBuffer(outIdx, false)
            }
        }

        codec.stop(); codec.release(); extractor.release()
        return samples.toFloatArray()
    }

    // ── LibriSpeech loading ─────────────────────────────────────────

    private fun loadTestUtterances(): List<Utterance> {
        val cacheDir = File(benchDir, "librispeech")
        val cached = loadFromCache(cacheDir)
        if (cached != null) {
            Log.i(TAG, "Loaded ${cached.size} cached utterances")
            return cached
        }
        return downloadLibriSpeech(cacheDir)
    }

    private fun loadFromCache(dir: File): List<Utterance>? {
        val meta = File(dir, "metadata.txt")
        if (!meta.exists()) return null
        val utts = mutableListOf<Utterance>()
        for (line in meta.readLines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t", limit = 3)
            if (parts.size < 3) continue
            val pcmFile = File(dir, "${parts[0]}.pcm")
            if (!pcmFile.exists()) continue
            val bytes = pcmFile.readBytes()
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            utts.add(Utterance(parts[0], parts[1], floats, parts[2]))
        }
        return utts.ifEmpty { null }
    }

    private fun saveToCache(dir: File, utts: List<Utterance>) {
        dir.mkdirs()
        val meta = StringBuilder()
        for (u in utts) {
            meta.appendLine("${u.id}\t${u.speakerId}\t${u.groundTruth}")
            val buf = ByteBuffer.allocate(u.pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(u.pcm)
            File(dir, "${u.id}.pcm").writeBytes(buf.array())
        }
        File(dir, "metadata.txt").writeText(meta.toString())
    }

    /**
     * Stream-extract utterances from LibriSpeech test-clean.tar.gz.
     * Only downloads enough data to get [UTTS_PER_SPEAKER] utterances from
     * [TARGET_SPEAKERS] speakers (~10-20MB of the 346MB archive).
     */
    private fun downloadLibriSpeech(cacheDir: File): List<Utterance> {
        Log.i(TAG, "Downloading LibriSpeech test-clean (streaming extraction)...")
        val conn = URL(LIBRISPEECH_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true

        val flacData = mutableMapOf<String, ByteArray>()
        val transcripts = mutableMapOf<String, String>()
        val speakerCounts = mutableMapOf<String, Int>()
        val doneSpeakers = mutableSetOf<String>()

        try {
            val gzip = GZIPInputStream(BufferedInputStream(conn.inputStream, 65536))
            while (true) {
                // Stop once we have enough FLACs AND matching transcripts for all
                if (doneSpeakers.size >= TARGET_SPEAKERS &&
                    flacData.keys.all { it in transcripts }
                ) break

                val hdr = readTarHeader(gzip) ?: break
                if (!hdr.isFile) { skipTarData(gzip, hdr.size); continue }

                val parts = hdr.name.split("/")
                if (parts.size < 5) { skipTarData(gzip, hdr.size); continue }
                val speaker = parts[2]

                when {
                    hdr.name.endsWith(".flac") -> {
                        if (speaker in doneSpeakers) {
                            skipTarData(gzip, hdr.size); continue
                        }
                        val count = speakerCounts.getOrDefault(speaker, 0)
                        if (count < UTTS_PER_SPEAKER) {
                            val data = readTarData(gzip, hdr.size)
                            val uttId = File(hdr.name).nameWithoutExtension
                            flacData[uttId] = data
                            speakerCounts[speaker] = count + 1
                            Log.i(TAG, "Extracted $uttId (${data.size} bytes)")
                            if (count + 1 >= UTTS_PER_SPEAKER) doneSpeakers.add(speaker)
                        } else {
                            skipTarData(gzip, hdr.size)
                        }
                    }
                    hdr.name.endsWith(".trans.txt") -> {
                        val text = String(readTarData(gzip, hdr.size), Charsets.UTF_8)
                        for (line in text.lines()) {
                            val sp = line.indexOf(' ')
                            if (sp > 0) transcripts[line.substring(0, sp)] =
                                line.substring(sp + 1)
                        }
                    }
                    else -> skipTarData(gzip, hdr.size)
                }
            }
            gzip.close()
        } catch (e: Exception) {
            Log.w(TAG, "Tar stream ended: ${e.message}")
        } finally {
            conn.disconnect()
        }

        Log.i(TAG, "Extracted ${flacData.size} FLACs, ${transcripts.size} transcripts")

        // Decode FLAC -> PCM
        val tempDir = File(cacheDir, "tmp").also { it.mkdirs() }
        val utts = mutableListOf<Utterance>()
        for ((uttId, data) in flacData) {
            val gt = transcripts[uttId] ?: continue
            val flacFile = File(tempDir, "$uttId.flac").also { it.writeBytes(data) }
            try {
                val pcm = decodeFlacToPcm(flacFile)
                utts.add(Utterance(uttId, uttId.split("-")[0], pcm, gt))
                Log.i(TAG, "Decoded $uttId: %.1fs".format(pcm.size.toFloat() / SAMPLE_RATE))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode $uttId: ${e.message}")
            } finally {
                flacFile.delete()
            }
        }
        tempDir.delete()

        assumeTrue("Need at least 4 test utterances", utts.size >= 4)
        saveToCache(cacheDir, utts)
        return utts
    }

    // ── Minimal tar reader ──────────────────────────────────────────

    private data class TarHeader(val name: String, val size: Long, val typeFlag: Char) {
        val isFile get() = typeFlag == '0' || typeFlag == '\u0000'
    }

    private fun readTarHeader(input: InputStream): TarHeader? {
        val buf = readExact(input, 512) ?: return null
        if (buf.all { it == 0.toByte() }) return null
        val name = String(buf, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
        val sizeStr = String(buf, 124, 12, Charsets.US_ASCII).trimEnd('\u0000').trim()
        val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
        val typeFlag = buf[156].toInt().toChar()
        val prefix = String(buf, 345, 155, Charsets.US_ASCII).trimEnd('\u0000')
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
        return TarHeader(fullName, size, typeFlag)
    }

    private fun readTarData(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < size) {
            val n = input.read(data, off, (size - off).toInt())
            if (n < 0) break
            off += n
        }
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) skipBytes(input, pad.toLong())
        return data
    }

    private fun skipTarData(input: InputStream, size: Long) {
        skipBytes(input, size + ((512 - (size % 512)) % 512))
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return if (off == 0) null else buf
            off += r
        }
        return buf
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var rem = n
        val buf = ByteArray(8192)
        while (rem > 0) {
            val r = input.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (r < 0) break
            rem -= r
        }
    }

    // ── Model management ────────────────────────────────────────────

    private fun ensureVoskModel(): String {
        val modelPath = File(File(benchDir, "vosk"), VOSK_DIR)
        if (modelPath.isDirectory) return modelPath.absolutePath
        modelPath.parentFile!!.mkdirs()
        Log.i(TAG, "Downloading Vosk model...")
        ZipInputStream(BufferedInputStream(URL(VOSK_URL).openStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(modelPath.parentFile!!, entry.name)
                if (entry.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return modelPath.absolutePath
    }

    private fun ensureSherpaModel(): File {
        val dir = File(benchDir, "sherpa").also { it.mkdirs() }
        for (name in SHERPA_FILES) {
            val file = File(dir, name)
            if (file.exists() && file.length() > 0) continue
            Log.i(TAG, "Downloading sherpa $name...")
            URL("$SHERPA_BASE/$name").openStream().use { inp ->
                file.outputStream().use { inp.copyTo(it) }
            }
        }
        return dir
    }

    private fun ensureWhisperModel(): String {
        val dir = File(benchDir, "whisper").also { it.mkdirs() }
        val file = File(dir, "ggml-tiny.en-q5_1.bin")
        if (file.exists() && file.length() > 0) return file.absolutePath
        Log.i(TAG, "Downloading Whisper model...")
        URL(WHISPER_URL).openStream().use { inp ->
            file.outputStream().use { inp.copyTo(it) }
        }
        return file.absolutePath
    }

    // ── Engine benchmarks ───────────────────────────────────────────

    private fun benchmarkVosk(utts: List<Utterance>): EngineResult {
        val modelPath = ensureVoskModel()
        val t0 = System.currentTimeMillis()
        val model = Model(modelPath)
        val loadMs = System.currentTimeMillis() - t0
        val modelSize = File(modelPath).walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val rec = Recognizer(model, SAMPLE_RATE.toFloat())
                val bytes = floatToBytes(utt.pcm)
                val start = System.currentTimeMillis()
                var off = 0
                val chunkBytes = CHUNK_SAMPLES * 2
                while (off < bytes.size) {
                    val end = minOf(off + chunkBytes, bytes.size)
                    rec.acceptWaveForm(bytes.copyOfRange(off, end), end - off)
                    off = end
                }
                val result = rec.finalResult
                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val text = try {
                        JSONObject(result).optString("text", "")
                    } catch (_: Exception) { "" }
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    Log.i(TAG, "Vosk [${utt.id}] WER=%.3f".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
                rec.close()
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "Vosk run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        model.close()

        return EngineResult(
            engine = "Vosk (small-en-us-0.15)",
            modelLoadMs = loadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    private fun benchmarkSherpa(utts: List<Utterance>): EngineResult {
        val modelDir = ensureSherpaModel()

        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )

        val t0 = System.currentTimeMillis()
        val recognizer = OnlineRecognizer(config = config)
        val loadMs = System.currentTimeMillis() - t0
        val modelSize = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val stream = recognizer.createStream()
                val resultText = StringBuilder()

                val start = System.currentTimeMillis()
                var off = 0
                while (off < utt.pcm.size) {
                    val end = minOf(off + CHUNK_SAMPLES, utt.pcm.size)
                    stream.acceptWaveform(utt.pcm.copyOfRange(off, end), SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    if (recognizer.isEndpoint(stream)) {
                        val r = recognizer.getResult(stream)
                        if (r.text.isNotBlank()) resultText.append(r.text).append(" ")
                        recognizer.reset(stream)
                    }
                    off = end
                }
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val finalR = recognizer.getResult(stream)
                if (finalR.text.isNotBlank()) resultText.append(finalR.text)
                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val text = resultText.toString().trim()
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    Log.i(TAG, "Sherpa [${utt.id}] WER=%.3f".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
                stream.release()
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "Sherpa run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        recognizer.release()

        return EngineResult(
            engine = "Sherpa (zipformer-en int8)",
            modelLoadMs = loadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    private fun benchmarkWhisper(utts: List<Utterance>): EngineResult {
        val modelPath = ensureWhisperModel()
        val t0 = System.currentTimeMillis()
        val ptr = WhisperLib.initContext(modelPath)
        val loadMs = System.currentTimeMillis() - t0
        assertTrue("Whisper model should load", ptr != 0L)
        val modelSize = File(modelPath).length()

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val start = System.currentTimeMillis()
                WhisperLib.fullTranscribe(ptr, 2, utt.pcm)
                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val segments = WhisperLib.getTextSegmentCount(ptr)
                    val text = buildString {
                        for (i in 0 until segments) append(WhisperLib.getTextSegment(ptr, i))
                    }.trim()
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    Log.i(TAG, "Whisper [${utt.id}] WER=%.3f".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "Whisper run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        WhisperLib.freeContext(ptr)

        return EngineResult(
            engine = "Whisper (tiny.en q5_1)",
            modelLoadMs = loadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    // ── Reporting ───────────────────────────────────────────────────

    private fun printComparison(results: List<EngineResult>) {
        val totalSec = utterances.sumOf { it.durationSec.toDouble() }
        val speakers = utterances.map { it.speakerId }.distinct().size

        Log.i(TAG, "")
        Log.i(TAG, "=== TRANSCRIPTION BENCHMARK RESULTS ===")
        Log.i(TAG, "Test data: ${utterances.size} utterances, $speakers speakers, ${"%.1f".format(totalSec)}s")
        Log.i(TAG, "Runs per engine: $BENCHMARK_RUNS (median RTF)")
        Log.i(TAG, "")
        Log.i(TAG, "%-28s  %6s  %6s  %5s  %5s".format("Engine", "Load", "Size", "RTF", "WER"))
        Log.i(TAG, "%-28s  %6s  %6s  %5s  %5s".format("", "(ms)", "(MB)", "", ""))
        Log.i(TAG, "─".repeat(58))
        for (r in results) {
            Log.i(
                TAG, "%-28s  %6d  %5.1f  %5.2f  %4.1f%%".format(
                    r.engine.take(28), r.modelLoadMs,
                    r.modelSizeBytes / (1024f * 1024f),
                    r.rtfMedian, r.werAggregate * 100
                )
            )
        }
        Log.i(TAG, "")
        for (r in results) {
            Log.i(TAG, "── ${r.engine} per-utterance WER ──")
            for ((id, wer) in r.werPerUtterance) {
                Log.i(TAG, "  %-30s  %5.1f%%".format(id, wer * 100))
            }
        }
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    fun benchmark_all_engines() {
        val utts = utterances
        val speakers = utts.map { it.speakerId }.distinct()
        Log.i(TAG, "Loaded ${utts.size} utterances from ${speakers.size} speakers " +
            "(${"%.1f".format(utts.sumOf { it.durationSec.toDouble() })}s total)")

        val results = mutableListOf<EngineResult>()
        for ((name, fn) in listOf(
            "Vosk" to { benchmarkVosk(utts) },
            "Sherpa" to { benchmarkSherpa(utts) },
            "Whisper" to { benchmarkWhisper(utts) }
        )) {
            try {
                results.add(fn())
            } catch (e: Exception) {
                Log.e(TAG, "$name benchmark failed", e)
            }
        }

        assertTrue("At least one engine must complete", results.isNotEmpty())
        printComparison(results)
    }
}
