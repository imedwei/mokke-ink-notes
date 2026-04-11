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

        // Legacy — kept for existing tests that reference it
        private const val LIBRISPEECH_URL =
            "https://www.openslr.org/resources/12/test-clean.tar.gz"

        // ── Evaluation datasets (unseen by all models) ──

        // Earnings-22: financial earnings calls from Rev.ai (diverse accents, teleconference audio)
        private const val EARNINGS22_MEDIA_BASE =
            "https://media.githubusercontent.com/media/revdotcom/speech-datasets/main/earnings22/media"
        private const val EARNINGS22_TRANSCRIPT_BASE =
            "https://raw.githubusercontent.com/revdotcom/speech-datasets/main/earnings22/transcripts/force_aligned_nlp_references"
        // Smallest US English file (~41 min total, we take first ~2 min)
        private const val EARNINGS22_FILE_ID = "4462231"
        private const val EARNINGS22_MAX_SEGMENTS = 6 // ~30s of speech segments

        // TED-LIUM 3: TED talks via HuggingFace datasets-server API
        private const val TEDLIUM_API_BASE =
            "https://datasets-server.huggingface.co/rows?dataset=distil-whisper%2Ftedlium-long-form&config=default&split=validation"
        // Row 3 = Brian_Cox (~2-3 min), Row 5 = David_Merrill (~3-4 min)
        private const val TEDLIUM_ROW = 3
        private const val TEDLIUM_MAX_DURATION_SEC = 30 // trim to ~30s

        private const val SHERPA_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main"
        private val SHERPA_FILES = listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt"
        )

        private const val SHERPA_ORT_BASE =
            "https://huggingface.co/w11wo/sherpa-onnx-ort-streaming-zipformer-en-2023-06-26/resolve/main"
        private val SHERPA_ORT_FILES = listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.ort",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.ort",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.ort",
            "tokens.txt"
        )

        private const val VOSK_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val VOSK_DIR = "vosk-model-small-en-us-0.15"

        private const val WHISPER_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"

        // Offline Sherpa models
        private const val SHERPA_OFFLINE_TRANSDUCER_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-en-2023-04-01/resolve/main"
        private val SHERPA_OFFLINE_TRANSDUCER_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )

        private const val SHERPA_PARAFORMER_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-en-2024-03-09/resolve/main"
        private val SHERPA_PARAFORMER_FILES = listOf(
            "model.int8.onnx",
            "tokens.txt"
        )

        // Offline model variants for comparison benchmark
        // 1. Newer zipformer (same architecture, newer checkpoint) — LibriSpeech trained
        private const val OFFLINE_ZIPFORMER_0626_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-en-2023-06-26/resolve/main"
        private val OFFLINE_ZIPFORMER_0626_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )

        // 2. Multidataset (LibriSpeech + GigaSpeech + CommonVoice) — best real-world generalization
        private const val OFFLINE_MULTIDATASET_BASE =
            "https://huggingface.co/yfyeung/icefall-asr-multidataset-pruned_transducer_stateless7-2023-05-04/resolve/main"
        // Files are in subdirectories — handled specially in ensure function
        private val OFFLINE_MULTIDATASET_FILES = mapOf(
            "encoder-epoch-30-avg-4.int8.onnx" to "exp/encoder-epoch-30-avg-4.int8.onnx",
            "decoder-epoch-30-avg-4.int8.onnx" to "exp/decoder-epoch-30-avg-4.int8.onnx",
            "joiner-epoch-30-avg-4.int8.onnx" to "exp/joiner-epoch-30-avg-4.int8.onnx",
            "tokens.txt" to "data/lang_bpe_500/tokens.txt"
        )

        // 3. GigaSpeech (10Kh YouTube/podcasts) — real-world audio, compact
        private const val OFFLINE_GIGASPEECH_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/main"
        private val OFFLINE_GIGASPEECH_FILES = listOf(
            "encoder-epoch-30-avg-1.int8.onnx",
            "decoder-epoch-30-avg-1.int8.onnx",
            "joiner-epoch-30-avg-1.int8.onnx",
            "tokens.txt"
        )

        // 4. NeMo Parakeet TDT 0.6B v2 (120Kh diverse, SOTA accuracy, built-in punctuation)
        private const val OFFLINE_PARAKEET_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
        private val OFFLINE_PARAKEET_FILES = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        )
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val benchDir by lazy { File(context.filesDir, "benchmark").also { it.mkdirs() } }
    private val utterances by lazy { loadTestUtterances() }
    /** Evaluation utterances from datasets unseen by all models (Earnings-22 + TED-LIUM).
     *  Each dataset produces one long utterance — WER is computed on the full transcript. */
    private val evalUtterances by lazy { loadEarnings22() + loadTedLium() }

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

    /** Get the sample rate of an audio file via MediaExtractor. */
    private fun getAudioSampleRate(audioFile: File): Int {
        val extractor = MediaExtractor()
        extractor.setDataSource(audioFile.absolutePath)
        val format = extractor.getTrackFormat(0)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        extractor.release()
        return rate
    }

    /** Decode any audio file (FLAC, MP3, WAV, OGG) to native-rate mono float32 PCM via MediaCodec. */
    private fun decodeAudioToPcm(audioFile: File, maxSamples: Int = Int.MAX_VALUE): FloatArray {
        return decodeAudioToPcmInternal(audioFile, maxSamples)
    }

    /** Simple nearest-neighbor resample from srcRate to dstRate. */
    private fun resample(pcm: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return pcm
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (pcm.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcIdx = (i * ratio).toInt().coerceAtMost(pcm.size - 1)
            out[i] = pcm[srcIdx]
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun decodeFlacToPcm(flacFile: File): FloatArray = decodeAudioToPcmInternal(flacFile)

    private fun decodeAudioToPcmInternal(flacFile: File, maxSamples: Int = Int.MAX_VALUE): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(flacFile.absolutePath)
        require(extractor.trackCount > 0) { "No tracks in ${flacFile.name}" }
        extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // Use primitive FloatArray to avoid boxing overhead (MutableList<Float> uses 16 bytes/sample)
        var samples = FloatArray(minOf(maxSamples, SAMPLE_RATE * 60)) // start at 1 min or maxSamples
        var sampleCount = 0
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
                for (s in shorts) {
                    if (sampleCount >= maxSamples) break
                    if (sampleCount >= samples.size) {
                        samples = samples.copyOf(minOf(samples.size * 2, maxSamples))
                    }
                    samples[sampleCount++] = s.toFloat() / 32768f
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (sampleCount >= maxSamples) break
            }
        }

        codec.stop(); codec.release(); extractor.release()
        return if (sampleCount == samples.size) samples else samples.copyOf(sampleCount)
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

    // ── Earnings-22 loading ──────────────────────────────────────────

    /**
     * Download one Earnings-22 earnings call MP3 + force-aligned transcript,
     * extract the first [EARNINGS22_MAX_SEGMENTS] speech segments as utterances.
     *
     * The force-aligned NLP file has one word per line:
     *   token|speaker|ts|endTs|punctuation|prepunctuation|case|tags|...
     *
     * We group words into sentences (splitting on sentence-ending punctuation)
     * and create one Utterance per sentence, extracting the corresponding audio.
     */
    private fun loadEarnings22(): List<Utterance> {
        val cacheDir = File(benchDir, "earnings22")
        val cached = loadFromCache(cacheDir)
        if (cached != null && cached.isNotEmpty()) {
            Log.i(TAG, "Loaded ${cached.size} cached Earnings-22 utterances")
            return cached
        }

        Log.i(TAG, "Downloading Earnings-22 call $EARNINGS22_FILE_ID...")

        // Download MP3
        val mp3File = File(cacheDir.also { it.mkdirs() }, "$EARNINGS22_FILE_ID.mp3")
        if (!mp3File.exists() || mp3File.length() == 0L) {
            URL("$EARNINGS22_MEDIA_BASE/$EARNINGS22_FILE_ID.mp3").openStream().use { inp ->
                mp3File.outputStream().use { inp.copyTo(it) }
            }
            Log.i(TAG, "Downloaded MP3: ${mp3File.length() / 1024}KB")
        }

        // Download force-aligned transcript
        val nlpFile = File(cacheDir, "$EARNINGS22_FILE_ID.aligned.nlp")
        if (!nlpFile.exists() || nlpFile.length() == 0L) {
            URL("$EARNINGS22_TRANSCRIPT_BASE/$EARNINGS22_FILE_ID.aligned.nlp").openStream().use { inp ->
                nlpFile.outputStream().use { inp.copyTo(it) }
            }
            Log.i(TAG, "Downloaded NLP: ${nlpFile.length() / 1024}KB")
        }

        // Parse NLP into sentences
        val sentences = parseEarnings22Nlp(nlpFile)
        Log.i(TAG, "Parsed ${sentences.size} sentences from NLP transcript")

        // Only decode as much audio as we need (first N segments + 2s margin)
        val lastSegment = sentences.take(EARNINGS22_MAX_SEGMENTS).lastOrNull()
        val maxDecodeSec = (lastSegment?.endSec ?: 120f) + 2f

        // Detect actual sample rate from MediaExtractor (MP3 may be 8/22/44kHz)
        val actualSampleRate = getAudioSampleRate(mp3File)
        Log.i(TAG, "MP3 native sample rate: $actualSampleRate Hz")
        val maxDecodeSamples = (maxDecodeSec * actualSampleRate).toInt()
        val fullPcm = decodeAudioToPcm(mp3File, maxDecodeSamples)
        Log.i(TAG, "Decoded MP3: %.1fs of audio at ${actualSampleRate}Hz (limited to %.0fs)".format(
            fullPcm.size.toFloat() / actualSampleRate, maxDecodeSec))

        // Build one utterance covering all segments: concatenate ground truth,
        // extract audio from first segment start to last segment end
        val segments = sentences.take(EARNINGS22_MAX_SEGMENTS)
        val groundTruth = segments.joinToString(" ") { it.text }
        val audioStart = segments.first().startSec
        val audioEnd = segments.last().endSec
        val startSample = (audioStart * actualSampleRate).toInt().coerceIn(0, fullPcm.size)
        val endSample = (audioEnd * actualSampleRate).toInt().coerceIn(startSample, fullPcm.size)
        val pcm = resample(fullPcm.copyOfRange(startSample, endSample), actualSampleRate, SAMPLE_RATE)

        Log.i(TAG, "Earnings-22: %.1f-%.1fs (%d segments), %d words".format(
            audioStart, audioEnd, segments.size, groundTruth.split(" ").size))

        val utts = listOf(Utterance(
            id = "earn22-$EARNINGS22_FILE_ID",
            speakerId = "earn22",
            pcm = pcm,
            groundTruth = groundTruth
        ))
        saveToCache(cacheDir, utts)
        return utts
    }

    private data class Sentence(val text: String, val startSec: Float, val endSec: Float, val speaker: String)

    private fun parseEarnings22Nlp(nlpFile: File): List<Sentence> {
        val lines = nlpFile.readLines()
        if (lines.isEmpty()) return emptyList()

        // Skip header line
        val dataLines = if (lines[0].startsWith("token|")) lines.drop(1) else lines

        val sentences = mutableListOf<Sentence>()
        val currentWords = mutableListOf<String>()
        var sentenceStartSec = -1f
        var sentenceEndSec = 0f
        var currentSpeaker = ""

        for (line in dataLines) {
            val parts = line.split("|")
            if (parts.size < 6) continue
            val token = parts[0]
            val speaker = parts[1]
            val ts = parts[2].toFloatOrNull() ?: continue
            val endTs = parts[3].toFloatOrNull() ?: continue
            val punctuation = parts[4]

            if (sentenceStartSec < 0) sentenceStartSec = ts
            currentSpeaker = speaker
            sentenceEndSec = endTs

            // Apply case
            val caseType = if (parts.size > 6) parts[6] else ""
            val word = when (caseType) {
                "UC" -> token.replaceFirstChar { it.uppercase() }
                "CA" -> token.uppercase()
                else -> token.lowercase()
            }
            currentWords.add(word + punctuation)

            // Sentence boundary: period, question mark, exclamation
            if (punctuation.contains('.') || punctuation.contains('?') || punctuation.contains('!')) {
                if (currentWords.isNotEmpty()) {
                    sentences.add(Sentence(
                        text = currentWords.joinToString(" "),
                        startSec = sentenceStartSec,
                        endSec = sentenceEndSec,
                        speaker = currentSpeaker
                    ))
                    currentWords.clear()
                    sentenceStartSec = -1f
                }
            }
        }
        // Emit remaining words as final sentence
        if (currentWords.isNotEmpty()) {
            sentences.add(Sentence(
                text = currentWords.joinToString(" "),
                startSec = sentenceStartSec,
                endSec = sentenceEndSec,
                speaker = currentSpeaker
            ))
        }
        return sentences
    }

    // ── TED-LIUM 3 loading ──────────────────────────────────────────

    /**
     * Load a TED talk from the HuggingFace datasets-server API.
     * Downloads the audio (signed URL) and transcript for one speaker entry,
     * trims to [TEDLIUM_MAX_DURATION_SEC] seconds.
     *
     * The transcript is a single string for the whole talk, so we split into
     * ~sentence-sized chunks for per-utterance WER measurement.
     */
    private fun loadTedLium(): List<Utterance> {
        val cacheDir = File(benchDir, "tedlium")
        val cached = loadFromCache(cacheDir)
        if (cached != null && cached.isNotEmpty()) {
            Log.i(TAG, "Loaded ${cached.size} cached TED-LIUM utterances")
            return cached
        }

        Log.i(TAG, "Fetching TED-LIUM row $TEDLIUM_ROW from datasets-server API...")

        // Fetch row metadata (contains signed audio URL + transcript)
        val apiUrl = "$TEDLIUM_API_BASE&offset=$TEDLIUM_ROW&length=1"
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val root = JSONObject(json)
        val rows = root.getJSONArray("rows")
        if (rows.length() == 0) {
            Log.w(TAG, "No rows returned from TED-LIUM API")
            return emptyList()
        }
        val row = rows.getJSONObject(0).getJSONObject("row")
        val transcript = row.getString("text")

        // Get audio URL from the nested audio array
        val audioArray = row.getJSONArray("audio")
        val audioUrl = audioArray.getJSONObject(0).getString("src")
        Log.i(TAG, "TED-LIUM transcript: ${transcript.length} chars, audio URL obtained")

        // Download audio WAV
        val wavFile = File(cacheDir.also { it.mkdirs() }, "tedlium_row$TEDLIUM_ROW.wav")
        URL(audioUrl).openStream().use { inp ->
            wavFile.outputStream().use { inp.copyTo(it) }
        }
        Log.i(TAG, "Downloaded WAV: ${wavFile.length() / 1024}KB")

        // Decode to PCM, trimmed to max duration
        val tedSampleRate = getAudioSampleRate(wavFile)
        val maxSamples = TEDLIUM_MAX_DURATION_SEC * tedSampleRate
        val rawPcm = decodeAudioToPcm(wavFile, maxSamples)
        val fullPcm = resample(rawPcm, tedSampleRate, SAMPLE_RATE)
        Log.i(TAG, "Decoded WAV: %.1fs at ${tedSampleRate}Hz, resampled to ${SAMPLE_RATE}Hz".format(
            fullPcm.size.toFloat() / SAMPLE_RATE))

        // The transcript covers the full talk but we only have the first 30s of audio.
        // Without word-level timestamps we can't know exactly which words are in our clip.
        // Solution: do a quick offline decode to count actual words, then use that count
        // (plus margin) to trim the reference. This avoids length-mismatch WER artifacts.
        val actualDurationSec = fullPcm.size.toFloat() / SAMPLE_RATE
        val allWords = transcript.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val wordsForDuration = calibrateRefWordCount(fullPcm, allWords)
        val groundTruth = allWords.take(wordsForDuration).joinToString(" ")

        Log.i(TAG, "TED-LIUM: %.1fs audio, %d ref words (of %d total, %.1f w/s est)".format(
            actualDurationSec, wordsForDuration, allWords.size, wordsForDuration / actualDurationSec))

        val utts = listOf(Utterance(
            id = "tedlium-row$TEDLIUM_ROW",
            speakerId = "tedlium",
            pcm = fullPcm,
            groundTruth = groundTruth
        ))
        saveToCache(cacheDir, utts)
        return utts
    }

    /**
     * Decode the audio with the offline transducer to count how many words the
     * model actually produces, then return that count + 10% as the ref word count.
     * This eliminates length-mismatch WER artifacts when the transcript has no
     * word-level timestamps.
     */
    private fun calibrateRefWordCount(pcm: FloatArray, allRefWords: List<String>): Int {
        val modelDir = ensureOfflineTransducerModel()
        val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2
            )
        )
        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(config = config)
        val stream = recognizer.createStream()
        stream.acceptWaveform(pcm, SAMPLE_RATE)
        recognizer.decode(stream)
        val hypText = recognizer.getResult(stream).text.trim()
        stream.release()
        recognizer.release()

        val hypWords = hypText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        // Use hyp count + 10% margin, capped at total ref words
        val refCount = ((hypWords * 1.1f).toInt()).coerceAtMost(allRefWords.size)
        Log.i(TAG, "TED-LIUM calibration: model produced $hypWords words, using $refCount ref words")
        return refCount
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

    private fun ensureSherpaModel(): File =
        ensureModelFiles(File(benchDir, "sherpa"), SHERPA_BASE, SHERPA_FILES)

    private fun ensureSherpaOrtModel(): File =
        ensureModelFiles(File(benchDir, "sherpa-ort"), SHERPA_ORT_BASE, SHERPA_ORT_FILES)

    private fun ensureModelFiles(dir: File, baseUrl: String, files: List<String>): File {
        dir.mkdirs()
        for (name in files) {
            val file = File(dir, name)
            if (file.exists() && file.length() > 0) continue
            Log.i(TAG, "Downloading $name...")
            URL("$baseUrl/$name").openStream().use { inp ->
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

    private fun benchmarkSherpa(utts: List<Utterance>): EngineResult =
        benchmarkSherpaWith(utts, ensureSherpaModel(), SHERPA_FILES, "Sherpa (zipformer-en int8)")

    private fun benchmarkSherpaOrt(utts: List<Utterance>): EngineResult =
        benchmarkSherpaWith(utts, ensureSherpaOrtModel(), SHERPA_ORT_FILES, "Sherpa (zipformer-en int8 ORT)")

    private fun benchmarkSherpaWith(
        utts: List<Utterance>, modelDir: File, files: List<String>, label: String
    ): EngineResult {
        val encoder = files.first { it.startsWith("encoder") }
        val decoder = files.first { it.startsWith("decoder") }
        val joiner = files.first { it.startsWith("joiner") }

        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, encoder).absolutePath,
                    decoder = File(modelDir, decoder).absolutePath,
                    joiner = File(modelDir, joiner).absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )

        // Force native library load (static init) before timing model init
        Log.i(TAG, "$label: triggering native library load...")
        val libStart = System.currentTimeMillis()
        try { Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer") } catch (_: Exception) {}
        val libMs = System.currentTimeMillis() - libStart
        Log.i(TAG, "$label: native library loaded in ${libMs}ms")

        Log.i(TAG, "$label: loading models...")
        val t0 = System.currentTimeMillis()
        val recognizer = OnlineRecognizer(config = config)
        val loadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "$label: models loaded in ${loadMs}ms (lib=${libMs}ms, total=${libMs + loadMs}ms)")
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
                    Log.i(TAG, "$label [${utt.id}] WER=%.3f".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
                stream.release()
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "$label run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        recognizer.release()

        return EngineResult(
            engine = label,
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

    /** Compare ONNX vs ORT model loading for Sherpa. */
    @Test
    fun benchmark_sherpa_onnx_vs_ort() {
        val utts = utterances
        Log.i(TAG, "Sherpa ONNX vs ORT comparison (${utts.size} utterances)")

        val results = mutableListOf<EngineResult>()
        results.add(benchmarkSherpa(utts))
        results.add(benchmarkSherpaOrt(utts))
        printComparison(results)
    }

    /**
     * Measure memory cost of keeping the Sherpa ORT model pre-loaded.
     * Reports Java heap, native heap, and PSS before/after/during-use.
     */
    @Test
    fun measure_sherpa_ort_memory() {
        val modelDir = ensureSherpaOrtModel()
        val files = SHERPA_ORT_FILES
        val encoder = files.first { it.startsWith("encoder") }
        val decoder = files.first { it.startsWith("decoder") }
        val joiner = files.first { it.startsWith("joiner") }

        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, encoder).absolutePath,
                    decoder = File(modelDir, decoder).absolutePath,
                    joiner = File(modelDir, joiner).absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )

        // Force native library load before measurement
        try { Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer") } catch (_: Exception) {}

        // ── Baseline ──
        forceGc()
        val baseline = snapshot("baseline")

        // ── After model load ──
        val recognizer = OnlineRecognizer(config = config)
        forceGc()
        val loaded = snapshot("model loaded")

        // ── During active transcription (stream + buffers) ──
        val utts = utterances
        val stream = recognizer.createStream()
        for (utt in utts.take(3)) {
            var off = 0
            while (off < utt.pcm.size) {
                val end = minOf(off + CHUNK_SAMPLES, utt.pcm.size)
                stream.acceptWaveform(utt.pcm.copyOfRange(off, end), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                if (recognizer.isEndpoint(stream)) {
                    recognizer.getResult(stream)
                    recognizer.reset(stream)
                }
                off = end
            }
        }
        forceGc()
        val active = snapshot("during transcription")

        // ── After stream release ──
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        recognizer.getResult(stream)
        stream.release()
        forceGc()
        val idle = snapshot("stream released")

        // ── After recognizer release ──
        recognizer.release()
        forceGc()
        val released = snapshot("recognizer released")

        printMemoryReport("SHERPA ORT", baseline, loaded, active, idle, released)
    }

    /**
     * Measure memory cost of keeping the Vosk model pre-loaded.
     */
    @Test
    fun measure_vosk_memory() {
        val modelPath = ensureVoskModel()

        // ── Baseline ──
        forceGc()
        val baseline = snapshot("baseline")

        // ── After model load ──
        val model = Model(modelPath)
        forceGc()
        val loaded = snapshot("model loaded")

        // ── During active transcription ──
        val utts = utterances
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        for (utt in utts.take(3)) {
            val bytes = floatToBytes(utt.pcm)
            var off = 0
            val chunkBytes = CHUNK_SAMPLES * 2
            while (off < bytes.size) {
                val end = minOf(off + chunkBytes, bytes.size)
                rec.acceptWaveForm(bytes.copyOfRange(off, end), end - off)
                off = end
            }
        }
        rec.finalResult
        forceGc()
        val active = snapshot("during transcription")

        // ── After recognizer close ──
        rec.close()
        forceGc()
        val idle = snapshot("recognizer closed")

        // ── After model close ──
        model.close()
        forceGc()
        val released = snapshot("model closed")

        printMemoryReport("VOSK", baseline, loaded, active, idle, released)
    }

    private fun printMemoryReport(
        title: String,
        baseline: MemSnapshot, loaded: MemSnapshot, active: MemSnapshot,
        idle: MemSnapshot, released: MemSnapshot
    ) {
        Log.i(TAG, "")
        Log.i(TAG, "=== $title MEMORY PROFILE ===")
        Log.i(TAG, "")
        Log.i(TAG, "%-24s  %8s  %8s  %8s".format("Phase", "Java", "Native", "PSS"))
        Log.i(TAG, "%-24s  %8s  %8s  %8s".format("", "(MB)", "(MB)", "(MB)"))
        Log.i(TAG, "─".repeat(54))
        for (s in listOf(baseline, loaded, active, idle, released)) {
            Log.i(TAG, "%-24s  %7.1f  %7.1f  %7.1f".format(
                s.label, s.javaHeapMB, s.nativeHeapMB, s.pssMB
            ))
        }
        Log.i(TAG, "─".repeat(54))

        val modelCost = loaded - baseline
        val streamCost = active - loaded
        val totalCost = loaded - released
        Log.i(TAG, "%-24s  %+7.1f  %+7.1f  %+7.1f".format(
            "Model load delta", modelCost.javaHeapMB, modelCost.nativeHeapMB, modelCost.pssMB
        ))
        Log.i(TAG, "%-24s  %+7.1f  %+7.1f  %+7.1f".format(
            "Active stream delta", streamCost.javaHeapMB, streamCost.nativeHeapMB, streamCost.pssMB
        ))
        Log.i(TAG, "%-24s  %+7.1f  %+7.1f  %+7.1f".format(
            "Pre-load cost (retain)", totalCost.javaHeapMB, totalCost.nativeHeapMB, totalCost.pssMB
        ))
        Log.i(TAG, "")
    }

    private data class MemSnapshot(
        val label: String,
        val javaHeapMB: Float,
        val nativeHeapMB: Float,
        val pssMB: Float
    ) {
        operator fun minus(other: MemSnapshot) = MemSnapshot(
            "$label - ${other.label}",
            javaHeapMB - other.javaHeapMB,
            nativeHeapMB - other.nativeHeapMB,
            pssMB - other.pssMB
        )
    }

    private fun snapshot(label: String): MemSnapshot {
        val javaHeap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024f * 1024f)
        val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)
        val memInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memInfo)
        val pss = memInfo.totalPss / 1024f // totalPss is in KB
        Log.i(TAG, "  MEM [$label] java=%.1fMB native=%.1fMB pss=%.1fMB".format(javaHeap, nativeHeap, pss))
        return MemSnapshot(label, javaHeap, nativeHeap, pss)
    }

    private fun forceGc() {
        System.gc()
        Thread.sleep(200)
        System.gc()
        Thread.sleep(200)
    }

    // ── Partial / Final latency ─────────────────────────────────────

    /**
     * Measures partial and final transcript latency for Vosk and Sherpa ORT.
     *
     * Feeds audio in real-time-paced 0.125s chunks and records:
     * - Time-to-first-partial: audio position when first non-empty text appears
     * - Partial update count: how many times the partial hypothesis changes
     * - Final latency: audio time between last spoken word and final emission
     * - Partial→final delta: how much the text changes from last partial to final
     */
    @Test
    fun measure_partial_final_latency() {
        val utts = utterances.take(4) // one per speaker
        Log.i(TAG, "Partial/final latency test (${utts.size} utterances)")

        Log.i(TAG, "")
        Log.i(TAG, "=== VOSK PARTIAL/FINAL BEHAVIOR ===")
        val voskModel = Model(ensureVoskModel())
        val voskResults = utts.map { measureVoskLatency(voskModel, it) }
        voskModel.close()

        Log.i(TAG, "")
        Log.i(TAG, "=== SHERPA ORT PARTIAL/FINAL BEHAVIOR ===")
        val sherpaDir = ensureSherpaOrtModel()
        val files = SHERPA_ORT_FILES
        val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(sherpaDir, files.first { it.startsWith("encoder") }).absolutePath,
                    decoder = File(sherpaDir, files.first { it.startsWith("decoder") }).absolutePath,
                    joiner = File(sherpaDir, files.first { it.startsWith("joiner") }).absolutePath
                ),
                tokens = File(sherpaDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        ))
        val sherpaResults = utts.map { measureSherpaLatency(recognizer, it) }
        recognizer.release()

        // Summary
        Log.i(TAG, "")
        Log.i(TAG, "=== PARTIAL/FINAL LATENCY SUMMARY ===")
        Log.i(TAG, "")
        Log.i(TAG, "%-14s  %8s  %8s  %8s  %8s".format(
            "Engine", "1st Par", "Updates", "Final Δ", "Par→Fin"
        ))
        Log.i(TAG, "%-14s  %8s  %8s  %8s  %8s".format(
            "", "(sec)", "(count)", "(sec)", "(words)"
        ))
        Log.i(TAG, "─".repeat(54))
        printLatencySummary("Vosk", voskResults)
        printLatencySummary("Sherpa ORT", sherpaResults)
    }

    private data class LatencyResult(
        val uttId: String,
        val firstPartialSec: Float,   // audio position when first text appeared
        val partialUpdates: Int,       // number of distinct partial hypotheses
        val finalEmitSec: Float,       // audio position when final was emitted
        val audioDurationSec: Float,
        val finalLatencySec: Float,    // audioDuration - finalEmitSec (how long after end of audio)
        val lastPartialText: String,
        val finalText: String,
        val partialToFinalWordDelta: Int // word-level edit distance between last partial and final
    )

    private fun measureVoskLatency(model: Model, utt: Utterance): LatencyResult {
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        val bytes = floatToBytes(utt.pcm)
        val chunkBytes = CHUNK_SAMPLES * 2
        val chunkDurationSec = CHUNK_SAMPLES.toFloat() / SAMPLE_RATE

        var firstPartialSec = -1f
        var partialUpdates = 0
        var lastPartialText = ""
        var finalText = ""
        var finalEmitSec = -1f
        var audioPosSec = 0f

        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + chunkBytes, bytes.size)
            val isFinal = rec.acceptWaveForm(bytes.copyOfRange(off, end), end - off)
            audioPosSec += (end - off).toFloat() / 2 / SAMPLE_RATE

            if (isFinal) {
                val text = try {
                    JSONObject(rec.finalResult).optString("text", "")
                } catch (_: Exception) { "" }
                if (text.isNotBlank()) {
                    finalText = text
                    finalEmitSec = audioPosSec
                    Log.i(TAG, "  VOSK FINAL @%.2fs: \"%s\"".format(audioPosSec, text.take(60)))
                }
            } else {
                val text = try {
                    JSONObject(rec.partialResult).optString("partial", "")
                } catch (_: Exception) { "" }
                if (text.isNotBlank() && text != lastPartialText) {
                    if (firstPartialSec < 0) firstPartialSec = audioPosSec
                    partialUpdates++
                    lastPartialText = text
                    if (partialUpdates <= 5 || partialUpdates % 10 == 0) {
                        Log.i(TAG, "  VOSK partial #$partialUpdates @%.2fs: \"%s\"".format(
                            audioPosSec, text.take(60)
                        ))
                    }
                }
            }
            off = end
        }

        // Flush remaining
        val flushText = try {
            JSONObject(rec.finalResult).optString("text", "")
        } catch (_: Exception) { "" }
        if (flushText.isNotBlank()) {
            finalText = flushText
            finalEmitSec = audioPosSec
            Log.i(TAG, "  VOSK FINAL (flush) @%.2fs: \"%s\"".format(audioPosSec, flushText.take(60)))
        }
        rec.close()

        val wordDelta = wordEditDistance(
            normalize(lastPartialText).split(" ").filter { it.isNotEmpty() },
            normalize(finalText).split(" ").filter { it.isNotEmpty() }
        )

        val result = LatencyResult(
            uttId = utt.id,
            firstPartialSec = firstPartialSec,
            partialUpdates = partialUpdates,
            finalEmitSec = finalEmitSec,
            audioDurationSec = utt.durationSec,
            finalLatencySec = if (finalEmitSec > 0) finalEmitSec - utt.durationSec else 0f,
            lastPartialText = lastPartialText,
            finalText = finalText,
            partialToFinalWordDelta = wordDelta
        )
        Log.i(TAG, "  [${utt.id}] 1st-partial=%.2fs updates=%d final-at=%.2fs (audio=%.1fs) par→fin-Δ=%d words".format(
            result.firstPartialSec, result.partialUpdates,
            result.finalEmitSec, result.audioDurationSec, result.partialToFinalWordDelta
        ))
        return result
    }

    private fun measureSherpaLatency(recognizer: OnlineRecognizer, utt: Utterance): LatencyResult {
        val stream = recognizer.createStream()
        val chunkDurationSec = CHUNK_SAMPLES.toFloat() / SAMPLE_RATE

        var firstPartialSec = -1f
        var partialUpdates = 0
        var lastPartialText = ""
        var finalText = ""
        var finalEmitSec = -1f
        var audioPosSec = 0f

        var off = 0
        while (off < utt.pcm.size) {
            val end = minOf(off + CHUNK_SAMPLES, utt.pcm.size)
            stream.acceptWaveform(utt.pcm.copyOfRange(off, end), SAMPLE_RATE)
            audioPosSec += (end - off).toFloat() / SAMPLE_RATE

            while (recognizer.isReady(stream)) recognizer.decode(stream)

            if (recognizer.isEndpoint(stream)) {
                val r = recognizer.getResult(stream)
                if (r.text.isNotBlank()) {
                    finalText = r.text
                    finalEmitSec = audioPosSec
                    Log.i(TAG, "  SHERPA FINAL @%.2fs: \"%s\"".format(audioPosSec, r.text.take(60)))
                }
                recognizer.reset(stream)
                lastPartialText = ""
            } else {
                val r = recognizer.getResult(stream)
                if (r.text.isNotBlank() && r.text != lastPartialText) {
                    if (firstPartialSec < 0) firstPartialSec = audioPosSec
                    partialUpdates++
                    lastPartialText = r.text
                    if (partialUpdates <= 5 || partialUpdates % 10 == 0) {
                        Log.i(TAG, "  SHERPA partial #$partialUpdates @%.2fs: \"%s\"".format(
                            audioPosSec, r.text.take(60)
                        ))
                    }
                }
            }
            off = end
        }

        // Flush
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val flushR = recognizer.getResult(stream)
        if (flushR.text.isNotBlank()) {
            if (finalText.isBlank() || flushR.text != finalText) {
                finalText = flushR.text
                finalEmitSec = audioPosSec
                Log.i(TAG, "  SHERPA FINAL (flush) @%.2fs: \"%s\"".format(audioPosSec, flushR.text.take(60)))
            }
        }
        stream.release()

        val wordDelta = wordEditDistance(
            normalize(lastPartialText).split(" ").filter { it.isNotEmpty() },
            normalize(finalText).split(" ").filter { it.isNotEmpty() }
        )

        val result = LatencyResult(
            uttId = utt.id,
            firstPartialSec = firstPartialSec,
            partialUpdates = partialUpdates,
            finalEmitSec = finalEmitSec,
            audioDurationSec = utt.durationSec,
            finalLatencySec = if (finalEmitSec > 0) finalEmitSec - utt.durationSec else 0f,
            lastPartialText = lastPartialText,
            finalText = finalText,
            partialToFinalWordDelta = wordDelta
        )
        Log.i(TAG, "  [${utt.id}] 1st-partial=%.2fs updates=%d final-at=%.2fs (audio=%.1fs) par→fin-Δ=%d words".format(
            result.firstPartialSec, result.partialUpdates,
            result.finalEmitSec, result.audioDurationSec, result.partialToFinalWordDelta
        ))
        return result
    }

    /** Word-level edit distance (not normalized). */
    private fun wordEditDistance(a: List<String>, b: List<String>): Int {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in d.indices) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + if (a[i-1] == b[j-1]) 0 else 1)
        }
        return d[a.size][b.size]
    }

    // ── Offline Sherpa benchmarks ─────────────────────────────────────

    private fun ensureOfflineTransducerModel(): File =
        ensureModelFiles(File(benchDir, "sherpa-offline-transducer"), SHERPA_OFFLINE_TRANSDUCER_BASE, SHERPA_OFFLINE_TRANSDUCER_FILES)

    private fun ensureParaformerModel(): File =
        ensureModelFiles(File(benchDir, "sherpa-paraformer"), SHERPA_PARAFORMER_BASE, SHERPA_PARAFORMER_FILES)

    private fun benchmarkOfflineTransducer(utts: List<Utterance>): EngineResult {
        val modelDir = ensureOfflineTransducerModel()

        val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2
            )
        )

        Log.i(TAG, "Offline transducer: loading model...")
        val t0 = System.currentTimeMillis()
        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(config = config)
        val loadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "Offline transducer: loaded in ${loadMs}ms")
        val modelSize = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val stream = recognizer.createStream()
                stream.acceptWaveform(utt.pcm, SAMPLE_RATE)

                val start = System.currentTimeMillis()
                recognizer.decode(stream)
                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val result = recognizer.getResult(stream)
                    val text = result.text.trim()
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    Log.i(TAG, "OfflineTransducer [${utt.id}] WER=%.3f".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "Offline transducer run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        recognizer.release()

        return EngineResult(
            engine = "Sherpa Offline (zipformer int8)",
            modelLoadMs = loadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    private fun benchmarkParaformer(utts: List<Utterance>): EngineResult {
        val modelDir = ensureParaformerModel()

        val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                paraformer = com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2
            )
        )

        Log.i(TAG, "Paraformer: loading model...")
        val t0 = System.currentTimeMillis()
        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(config = config)
        val loadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "Paraformer: loaded in ${loadMs}ms")
        val modelSize = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val stream = recognizer.createStream()
                stream.acceptWaveform(utt.pcm, SAMPLE_RATE)

                val start = System.currentTimeMillis()
                recognizer.decode(stream)
                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val result = recognizer.getResult(stream)
                    val text = result.text.trim()
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    Log.i(TAG, "Paraformer [${utt.id}] WER=%.3f".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "Paraformer run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        recognizer.release()

        return EngineResult(
            engine = "Sherpa Paraformer (int8)",
            modelLoadMs = loadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    /** Compare online streaming vs offline transducer vs paraformer. */
    @Test
    fun benchmark_sherpa_online_vs_offline() {
        val utts = utterances
        Log.i(TAG, "Online vs Offline comparison (${utts.size} utterances)")

        val results = mutableListOf<EngineResult>()

        try { results.add(benchmarkSherpa(utts)) } catch (e: Exception) {
            Log.e(TAG, "Online benchmark failed", e)
        }
        try { results.add(benchmarkOfflineTransducer(utts)) } catch (e: Exception) {
            Log.e(TAG, "Offline transducer benchmark failed", e)
        }
        try { results.add(benchmarkParaformer(utts)) } catch (e: Exception) {
            Log.e(TAG, "Paraformer benchmark failed", e)
        }

        assertTrue("At least one engine must complete", results.isNotEmpty())
        printComparison(results)
    }

    // ── Two-pass benchmark (streaming endpoints → offline re-transcription) ──

    /**
     * Simulate the per-endpoint two-pass approach from SherpaOnnx2Pass:
     * 1. Feed audio in chunks to the online (streaming) recognizer
     * 2. When an endpoint is detected, send the buffered segment to the offline recognizer
     * 3. Use the offline result as the final text for that segment
     * 4. Keep a 500ms overlap buffer for the next segment
     *
     * This measures the WER of the two-pass result vs pure streaming vs pure offline.
     */
    private fun benchmarkTwoPass(utts: List<Utterance>): EngineResult {
        val onlineModelDir = ensureSherpaOrtModel()
        val offlineModelDir = ensureOfflineTransducerModel()

        // Create online (streaming) recognizer
        val onlineEncoder = SHERPA_ORT_FILES.first { it.startsWith("encoder") }
        val onlineDecoder = SHERPA_ORT_FILES.first { it.startsWith("decoder") }
        val onlineJoiner = SHERPA_ORT_FILES.first { it.startsWith("joiner") }

        val onlineConfig = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(onlineModelDir, onlineEncoder).absolutePath,
                    decoder = File(onlineModelDir, onlineDecoder).absolutePath,
                    joiner = File(onlineModelDir, onlineJoiner).absolutePath
                ),
                tokens = File(onlineModelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer2"
            ),
            enableEndpoint = true
        )

        // Create offline recognizer
        val offlineConfig = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = File(offlineModelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(offlineModelDir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    joiner = File(offlineModelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
                ),
                tokens = File(offlineModelDir, "tokens.txt").absolutePath,
                numThreads = 2
            )
        )

        Log.i(TAG, "Two-pass: loading online model...")
        val t0 = System.currentTimeMillis()
        val onlineRecognizer = OnlineRecognizer(config = onlineConfig)
        val onlineLoadMs = System.currentTimeMillis() - t0

        Log.i(TAG, "Two-pass: loading offline model...")
        val t1 = System.currentTimeMillis()
        val offlineRecognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(config = offlineConfig)
        val offlineLoadMs = System.currentTimeMillis() - t1
        val totalLoadMs = onlineLoadMs + offlineLoadMs
        Log.i(TAG, "Two-pass: models loaded (online=${onlineLoadMs}ms, offline=${offlineLoadMs}ms)")

        val modelSize = onlineModelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            offlineModelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()
        val overlapSamples = 8000 // 500ms at 16kHz, same as SherpaOnnx2Pass

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val stream = onlineRecognizer.createStream()
                val resultText = StringBuilder()
                val samplesBuffer = mutableListOf<FloatArray>()
                var secondPassCount = 0

                val start = System.currentTimeMillis()
                var off = 0
                while (off < utt.pcm.size) {
                    val end = minOf(off + CHUNK_SAMPLES, utt.pcm.size)
                    val chunk = utt.pcm.copyOfRange(off, end)
                    samplesBuffer.add(chunk)
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    while (onlineRecognizer.isReady(stream)) onlineRecognizer.decode(stream)

                    if (onlineRecognizer.isEndpoint(stream)) {
                        val onlineResult = onlineRecognizer.getResult(stream)
                        onlineRecognizer.reset(stream)

                        if (onlineResult.text.isNotBlank()) {
                            // Run second pass on buffered segment
                            val segmentText = runOfflineSecondPass(
                                offlineRecognizer, samplesBuffer, overlapSamples
                            )
                            if (segmentText.isNotBlank()) {
                                resultText.append(segmentText).append(" ")
                            }
                            secondPassCount++
                        } else {
                            samplesBuffer.clear()
                        }
                    }
                    off = end
                }

                // Process any remaining audio after the last endpoint
                stream.inputFinished()
                while (onlineRecognizer.isReady(stream)) onlineRecognizer.decode(stream)
                val finalOnline = onlineRecognizer.getResult(stream)
                if (finalOnline.text.isNotBlank()) {
                    // Run second pass on remaining audio
                    val segmentText = runOfflineSecondPass(
                        offlineRecognizer, samplesBuffer, 0 // no overlap needed for final segment
                    )
                    if (segmentText.isNotBlank()) {
                        resultText.append(segmentText)
                    }
                    secondPassCount++
                }

                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val text = resultText.toString().trim()
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    Log.i(TAG, "TwoPass [${utt.id}] WER=%.3f (${secondPassCount} segments)".format(wer))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(80)}")
                    Log.i(TAG, "  hyp: ${text.take(80)}")
                }
                stream.release()
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "Two-pass run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }
        onlineRecognizer.release()
        offlineRecognizer.release()

        return EngineResult(
            engine = "Two-pass (ORT + offline)",
            modelLoadMs = totalLoadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    /**
     * Flatten buffered samples, send to offline recognizer, keep overlap for next segment.
     * Mirrors SherpaOnnx2Pass runSecondPass() logic.
     */
    private fun runOfflineSecondPass(
        offlineRecognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer,
        samplesBuffer: MutableList<FloatArray>,
        overlapSamples: Int
    ): String {
        // Flatten all buffered chunks into a single array
        val totalSamples = samplesBuffer.sumOf { it.size }
        val samples = FloatArray(totalSamples)
        var i = 0
        for (chunk in samplesBuffer) {
            chunk.copyInto(samples, i)
            i += chunk.size
        }

        // Keep overlap for next segment (500ms at 16kHz = 8000 samples)
        val n = maxOf(0, samples.size - overlapSamples)
        samplesBuffer.clear()
        if (overlapSamples > 0 && n < samples.size) {
            samplesBuffer.add(samples.copyOfRange(n, samples.size))
        }

        // Decode with offline model
        val stream = offlineRecognizer.createStream()
        stream.acceptWaveform(if (overlapSamples > 0) samples.copyOfRange(0, maxOf(1, n)) else samples, SAMPLE_RATE)
        offlineRecognizer.decode(stream)
        val result = offlineRecognizer.getResult(stream)
        stream.release()

        return result.text.trim()
    }

    /**
     * Compare streaming-only, two-pass, and offline-only approaches.
     * This validates whether per-endpoint two-pass achieves similar WER to full offline.
     */
    @Test
    fun benchmark_two_pass() {
        val utts = utterances
        Log.i(TAG, "Two-pass benchmark (${utts.size} utterances)")

        val results = mutableListOf<EngineResult>()

        try { results.add(benchmarkSherpaOrt(utts)) } catch (e: Exception) {
            Log.e(TAG, "Streaming-only benchmark failed", e)
        }
        try { results.add(benchmarkTwoPass(utts)) } catch (e: Exception) {
            Log.e(TAG, "Two-pass benchmark failed", e)
        }
        try { results.add(benchmarkOfflineTransducer(utts)) } catch (e: Exception) {
            Log.e(TAG, "Offline-only benchmark failed", e)
        }

        assertTrue("At least one engine must complete", results.isNotEmpty())
        printComparison(results)

        // Log per-utterance comparison if all three completed
        if (results.size == 3) {
            Log.i(TAG, "")
            Log.i(TAG, "=== PER-UTTERANCE WER COMPARISON ===")
            Log.i(TAG, "%-30s  %8s  %8s  %8s".format("Utterance", "Stream", "TwoPass", "Offline"))
            Log.i(TAG, "─".repeat(60))
            val streamWers = results[0].werPerUtterance.toMap()
            val twoPassWers = results[1].werPerUtterance.toMap()
            val offlineWers = results[2].werPerUtterance.toMap()
            for ((id, _) in results[0].werPerUtterance) {
                Log.i(TAG, "%-30s  %7.1f%%  %7.1f%%  %7.1f%%".format(
                    id.take(30),
                    (streamWers[id] ?: 0f) * 100,
                    (twoPassWers[id] ?: 0f) * 100,
                    (offlineWers[id] ?: 0f) * 100
                ))
            }
        }
    }

    // ── Offline model comparison benchmark ─────────────────────────────

    private fun ensureOfflineZipformer0626(): File =
        ensureModelFiles(File(benchDir, "sherpa-offline-zipformer-0626"), OFFLINE_ZIPFORMER_0626_BASE, OFFLINE_ZIPFORMER_0626_FILES)

    private fun ensureOfflineGigaSpeech(): File =
        ensureModelFiles(File(benchDir, "sherpa-offline-gigaspeech"), OFFLINE_GIGASPEECH_BASE, OFFLINE_GIGASPEECH_FILES)

    private fun ensureOfflineParakeet(): File =
        ensureModelFiles(File(benchDir, "sherpa-offline-parakeet"), OFFLINE_PARAKEET_BASE, OFFLINE_PARAKEET_FILES)

    /** Download files from subdirectory paths (for multidataset model). */
    private fun ensureOfflineMultidataset(): File {
        val dir = File(benchDir, "sherpa-offline-multidataset").also { it.mkdirs() }
        for ((localName, remotePath) in OFFLINE_MULTIDATASET_FILES) {
            val file = File(dir, localName)
            if (file.exists() && file.length() > 0) continue
            Log.i(TAG, "Downloading $remotePath -> $localName...")
            URL("$OFFLINE_MULTIDATASET_BASE/$remotePath").openStream().use { inp ->
                file.outputStream().use { inp.copyTo(it) }
            }
        }
        return dir
    }

    /**
     * Generic offline transducer benchmark. Loads models, measures load time,
     * runs WER/RTF, and profiles memory.
     */
    private fun benchmarkOfflineGeneric(
        utts: List<Utterance>,
        modelDir: File,
        encoderFile: String,
        decoderFile: String,
        joinerFile: String,
        tokensFile: String = "tokens.txt",
        label: String,
        profileMemory: Boolean = true
    ): EngineResult {
        val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = File(modelDir, encoderFile).absolutePath,
                    decoder = File(modelDir, decoderFile).absolutePath,
                    joiner = File(modelDir, joinerFile).absolutePath
                ),
                tokens = File(modelDir, tokensFile).absolutePath,
                numThreads = 2
            )
        )

        // Memory baseline
        if (profileMemory) forceGc()
        val memBaseline = if (profileMemory) snapshot("baseline") else null

        Log.i(TAG, "$label: loading model...")
        val t0 = System.currentTimeMillis()
        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(config = config)
        val loadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "$label: loaded in ${loadMs}ms")
        val modelSize = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        // Memory after load
        if (profileMemory) forceGc()
        val memLoaded = if (profileMemory) snapshot("model loaded") else null

        val rtfs = mutableListOf<Float>()
        var wers = emptyList<Pair<String, Float>>()

        for (run in 1..BENCHMARK_RUNS) {
            var audioSec = 0f; var procMs = 0L
            val runWers = mutableListOf<Pair<String, Float>>()

            for (utt in utts) {
                val stream = recognizer.createStream()
                stream.acceptWaveform(utt.pcm, SAMPLE_RATE)

                val start = System.currentTimeMillis()
                recognizer.decode(stream)
                procMs += System.currentTimeMillis() - start
                audioSec += utt.durationSec

                if (run == 1) {
                    val result = recognizer.getResult(stream)
                    val text = result.text.trim()
                    val wer = wordErrorRate(utt.groundTruth, text)
                    runWers.add(utt.id to wer)
                    val refWords = normalize(utt.groundTruth).split(" ").filter { it.isNotEmpty() }.size
                    val hypWords = normalize(text).split(" ").filter { it.isNotEmpty() }.size
                    Log.i(TAG, "$label [${utt.id}] WER=%.3f (ref=%d hyp=%d words)".format(wer, refWords, hypWords))
                    Log.i(TAG, "  ref: ${utt.groundTruth.take(120)}")
                    Log.i(TAG, "  hyp: ${text.take(120)}")
                }
            }

            rtfs.add(procMs / 1000f / audioSec)
            Log.i(TAG, "$label run $run: RTF=%.3f".format(rtfs.last()))
            if (run == 1) wers = runWers
        }

        // Memory during use
        if (profileMemory) forceGc()
        val memActive = if (profileMemory) snapshot("after transcription") else null

        recognizer.release()

        // Memory after release
        if (profileMemory) forceGc()
        val memReleased = if (profileMemory) snapshot("released") else null

        if (profileMemory && memBaseline != null && memLoaded != null && memActive != null && memReleased != null) {
            Log.i(TAG, "")
            Log.i(TAG, "── $label MEMORY ──")
            Log.i(TAG, "%-24s  %8s  %8s  %8s".format("Phase", "Java", "Native", "PSS"))
            Log.i(TAG, "%-24s  %8s  %8s  %8s".format("", "(MB)", "(MB)", "(MB)"))
            Log.i(TAG, "─".repeat(54))
            for (s in listOf(memBaseline, memLoaded, memActive, memReleased)) {
                Log.i(TAG, "%-24s  %7.1f  %7.1f  %7.1f".format(s.label, s.javaHeapMB, s.nativeHeapMB, s.pssMB))
            }
            val modelCost = memLoaded - memBaseline
            Log.i(TAG, "%-24s  %+7.1f  %+7.1f  %+7.1f".format(
                "Model load delta", modelCost.javaHeapMB, modelCost.nativeHeapMB, modelCost.pssMB
            ))
        }

        return EngineResult(
            engine = label,
            modelLoadMs = loadMs, modelSizeBytes = modelSize,
            rtfRuns = rtfs, rtfMedian = rtfs.sorted()[rtfs.size / 2],
            werPerUtterance = wers,
            werAggregate = wers.map { it.second }.average().toFloat()
        )
    }

    /**
     * Compare all offline models on unseen evaluation data (Earnings-22 + TED-LIUM 3).
     *
     * Neither dataset was used to train any of the models under test, giving a fair
     * cross-domain WER comparison. Reports WER, RTF, load time, size, and memory.
     */
    @Test
    fun benchmark_offline_models() {
        val utts = evalUtterances
        Log.i(TAG, "Offline model comparison (${utts.size} utterances from Earnings-22 + TED-LIUM)")
        Log.i(TAG, "Evaluation data is unseen by all models — no training set advantage.")

        val results = mutableListOf<EngineResult>()

        // 1. Current model (2023-04-01, LibriSpeech trained)
        try {
            val dir = ensureOfflineTransducerModel()
            results.add(benchmarkOfflineGeneric(utts, dir,
                "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                label = "Zipformer 2023-04-01 (LibriSpeech)"))
        } catch (e: Exception) { Log.e(TAG, "Zipformer 2023-04-01 failed", e) }

        // 2. Newer zipformer (2023-06-26, LibriSpeech trained)
        try {
            val dir = ensureOfflineZipformer0626()
            results.add(benchmarkOfflineGeneric(utts, dir,
                "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                label = "Zipformer 2023-06-26 (LibriSpeech)"))
        } catch (e: Exception) { Log.e(TAG, "Zipformer 2023-06-26 failed", e) }

        // 3. Multidataset (LibriSpeech + GigaSpeech + CommonVoice)
        try {
            val dir = ensureOfflineMultidataset()
            results.add(benchmarkOfflineGeneric(utts, dir,
                "encoder-epoch-30-avg-4.int8.onnx", "decoder-epoch-30-avg-4.int8.onnx",
                "joiner-epoch-30-avg-4.int8.onnx",
                label = "Multidataset (LibriS+Giga+CV)"))
        } catch (e: Exception) { Log.e(TAG, "Multidataset failed", e) }

        // 4. GigaSpeech (YouTube/podcasts)
        try {
            val dir = ensureOfflineGigaSpeech()
            results.add(benchmarkOfflineGeneric(utts, dir,
                "encoder-epoch-30-avg-1.int8.onnx", "decoder-epoch-30-avg-1.int8.onnx",
                "joiner-epoch-30-avg-1.int8.onnx",
                label = "GigaSpeech (YouTube/podcasts)"))
        } catch (e: Exception) { Log.e(TAG, "GigaSpeech failed", e) }

        // 5. NeMo Parakeet TDT 0.6B v2 — SKIPPED: 1.6 GB native memory, OOM on 6 GB device
        Log.i(TAG, "Skipping Parakeet TDT 0.6B: 1.6 GB native memory causes OOM on Palma 2 Pro")

        assertTrue("At least one model must complete", results.isNotEmpty())
        printComparison(results)
    }

    private fun printLatencySummary(engine: String, results: List<LatencyResult>) {
        val avgFirstPartial = results.filter { it.firstPartialSec >= 0 }
            .map { it.firstPartialSec }.average().toFloat()
        val avgUpdates = results.map { it.partialUpdates }.average().toFloat()
        val avgFinalLat = results.filter { it.finalEmitSec >= 0 }
            .map { it.finalLatencySec }.average().toFloat()
        val avgWordDelta = results.map { it.partialToFinalWordDelta }.average().toFloat()

        Log.i(TAG, "%-14s  %7.2f  %8.0f  %+7.2f  %8.1f".format(
            engine, avgFirstPartial, avgUpdates, avgFinalLat, avgWordDelta
        ))
        for (r in results) {
            Log.i(TAG, "  %-20s  1st=%.2fs  #%d updates  final@%.2fs  Δ%d words".format(
                r.uttId, r.firstPartialSec, r.partialUpdates,
                r.finalEmitSec, r.partialToFinalWordDelta
            ))
        }
    }
}
