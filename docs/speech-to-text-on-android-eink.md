# Speech-to-Text on Android E-Ink: Lessons from the Boox Palma 2 Pro

Android's built-in speech recognizer cannot record audio and transcribe it at the same time.

That's not a bug. It's a hard platform constraint. The `SpeechRecognizer` API owns the microphone exclusively, and Android's audio sharing policy prevents a second `AudioRecord` from capturing simultaneously. If you want to save the audio while transcribing, you have to bring your own speech recognition engine.

I discovered this while building a feature for [Mokke Ink](https://github.com/imedwei/mokke-ink-notes), a handwriting notes app I'm developing for Boox e-ink devices. The feature I wanted: record a lecture or meeting, transcribe it in real time onto the notes screen, and let the user annotate the transcript with lightweight margin notes. After the session, the user can play back the audio synced to the transcript, correct mis-transcribed words by tapping them, and generate a summary that weights the parts they marked as important.

This requires three things from the speech-to-text engine:

1. Real-time transcription while simultaneously saving the audio
2. Per-word timestamps and confidence scores (for tap-to-seek and error highlighting)
3. Accuracy good enough for note-taking on a mid-range mobile SoC

Cloud transcription services (Google Cloud Speech, Deepgram, AssemblyAI) would be more accurate, but I ruled them out. This is an open-source app for personal note-taking. Streaming meeting audio to a third-party server is a privacy problem, and ongoing API costs don't make sense for a passion project. Everything needed to run on-device.

I tried six approaches. Here is where I landed:

```
Engine                                Real-time   Audio   Per-word   Size    Earn-22   TED   Avg WER
─────────────────────────────────────────────────────────────────────────────────────────────────────
SpeechRecognizer                      ✓           ✗       ✗          0 MB      —       —       —
whisper.cpp (tiny.en q5_1)            ✗ (5.98x)  ✓       ✓          31 MB   16.0%   25.4%   20.7%
Vosk (small-en-us-0.15)               ✓ (0.22x)  ✓       ✓          68 MB   37.3%   23.8%   30.6%
Sherpa zipformer streaming (ORT)      ✓ (0.14x)  ✓       ✓          71 MB   20.0%   32.0%   26.0%
Sherpa two-pass (stream+GigaSpeech)   ✓ (0.19x)  ✓       ✓         139 MB    6.7%   18.0%   12.3%
```

WER measured on unseen evaluation data: [Earnings-22](https://github.com/revdotcom/speech-datasets) financial conference calls (Earn-22) and [TED-LIUM 3](https://huggingface.co/datasets/distil-whisper/tedlium-long-form) talks (TED). None of the models were trained on this data. The two-pass engine uses the streaming zipformer ORT model (71 MB) for real-time partials and the GigaSpeech offline zipformer ORT model (68 MB) for second-pass re-transcription at each endpoint. Vosk performs best on TED talks (clear academic speech), while Sherpa and Whisper handle noisy conference audio better.

**Device:** Boox Palma 2 Pro, Qualcomm SM6350 (Snapdragon 690), Android 15 (API 35), Kaleido 3 color e-ink display.

---

## Why SpeechRecognizer Can't Record Audio

Android's `android.speech.SpeechRecognizer` opens and owns the microphone internally. To save audio simultaneously, you would need a second `AudioRecord` instance. Android 10+ prevents this:

> "If two ordinary apps are capturing audio simultaneously... the app that started capturing most recently receives audio and the other app gets silence."
>
> [Sharing audio input | Android Developers](https://developer.android.com/guide/topics/media/sharing-audio-input)

The sharing policy is process-level, not source-level. The `AudioSource` type (`MIC`, `VOICE_RECOGNITION`, `UNPROCESSED`) does not affect this.

I tested every combination I could think of on the Palma 2 Pro. Running `AudioRecord(MIC)` alongside SpeechRecognizer produced `ERROR_NO_MATCH` (7) and `ERROR_SERVER` (11). Starting `AudioRecord(VOICE_RECOGNITION)` after SpeechRecognizer's `onReadyForSpeech` was worse: SpeechRecognizer produced zero results because AudioRecord consumed the audio data.

This left me with one path: build my own audio pipeline and bring my own speech recognition engine.

> **Why iOS apps don't have this problem.** [Just Press Record](https://www.openplanetsoftware.com/just-press-record/) is a $6.99 iOS app that records and transcribes simultaneously. It can do this because Apple's `AVAudioEngine` provides an audio tap, a hook on the shared audio processing graph that multiple consumers can read from. The app installs a tap, writes audio to a file, and passes the same samples to `SFSpeechRecognizer`. One audio stream, multiple readers, no contention. Android's architecture isolates audio consumers at the process level. There is no equivalent of an audio tap for ordinary apps.

### ML Kit GenAI Speech Recognition: a dead end

Google's newer [ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) (`com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`) offers `AudioSource.fromPfd(ParcelFileDescriptor)`, which would let you pipe your own audio stream to the recognizer and solve the dual-consumer problem. However, as of April 2026 it is restricted to Google-signed apps:

```
PERMISSION_DENIED: Rejected by (1st-party only Allowlist) 
security policy. Not google-signed.
```

I tested on both the Boox Palma 2 Pro and a Pixel 7 Pro. Same error on both. The Boox device also cannot install [Android AICore](https://play.google.com/store/apps/details?id=com.google.android.aicore) from the Play Store ("not compatible with your device"). This may change in a future release. Check the [current documentation](https://developers.google.com/ml-kit/genai/speech-recognition/android) for status.

---

## Why Whisper Is Too Slow (on This Hardware)

With the system recognizer ruled out, I integrated [whisper.cpp v1.8.4](https://github.com/ggerganov/whisper.cpp) as a git submodule with a CMake native build, producing `libwhisper.so` for `arm64-v8a`. Models downloaded from [HuggingFace](https://huggingface.co/ggerganov/whisper.cpp).

### Benchmark results

Measured via an instrumented Android test (`WhisperBenchmarkTest`) on the Palma 2 Pro. The test audio is a 10-second synthetic sine wave at 440Hz, not speech, so these numbers reflect pure inference cost without VAD benefits. Each configuration was run once per test invocation, so treat these as directional rather than statistically rigorous. Thermal throttling on sustained runs could shift results. That said, the magnitude of the gap (4.5x realtime at best) is large enough that variance in individual runs does not change the conclusion.

```
Model                        Threads   Wall Time   Realtime Factor
──────────────────────────────────────────────────────────────────
ggml-tiny.en (f16, 75 MB)   2         55.7s       5.6x
ggml-tiny.en (f16, 75 MB)   4         45.5s       4.5x
ggml-tiny.en-q5_1 (32 MB)   2         80.8s       8.1x
ggml-tiny.en-q5_1 (32 MB)   4         56.4s       5.6x
```

Source: `app/src/androidTest/java/com/writer/perf/WhisperBenchmarkTest.kt`

**The quantized model was slower.** The q5_1 model was consistently slower than f16 on this SoC across my test runs. My working theory: the dequantization overhead on the Snapdragon 690's Kryo 560 cores exceeds the memory bandwidth savings from the smaller model. I did not isolate this rigorously (thermal state, background processes, and memory pressure could all contribute), but the pattern was consistent enough that I stopped pursuing quantization on this hardware. If you're targeting a different SoC, benchmark before assuming quantization helps.

### Voice Activity Detection: the one result that surprised me

Enabling [Silero VAD](https://github.com/snakers4/silero-vad) (`ggml-silero-v5.1.2.bin`, ~2 MB) via `params.vad = true`:

```
Config     Wall Time   Realtime Factor
────────────────────────────────────────
No VAD     45.2s       4.5x
With VAD    0.96s      0.096x
```

A **47x speedup**. This was measured on the same 10-second synthetic audio with alternating 1-second speech and 1-second silence (50% silence). Real speech with natural pauses would see a smaller but still significant improvement. VAD skips silent segments entirely, so Whisper only processes the speech portions.

### Other inference parameters

Applied in the JNI layer (`whisper_jni.c`):

```c
params.greedy.best_of = 1;      // default is 5 parallel decoders
params.temperature_inc = 0.0f;  // disable retry at higher temperatures
params.token_timestamps = true; // per-word confidence and timestamps
```

### Accuracy on real speech

The synthetic sine wave benchmarks above measure pure inference cost. To measure accuracy, I ran a comparative benchmark on unseen evaluation data — [Earnings-22](https://github.com/revdotcom/speech-datasets) conference calls and [TED-LIUM 3](https://huggingface.co/datasets/distil-whisper/tedlium-long-form) talks — that none of the engines were trained on:

```
Engine                WER     RTF (median of 3)
─────────────────────────────────────────────────
whisper.cpp (q5_1)   20.7%     5.98x
Vosk                 30.6%     0.22x
Sherpa-ONNX (int8)   26.0%     0.14x
```

Source: `TranscriptionBenchmarkTest#benchmark_all_engines_unseen`

Earlier benchmarks on LibriSpeech test-clean showed much lower WER (Whisper 3.7%, Vosk 11.1%, Sherpa 11.2%), but LibriSpeech is clean read audiobook speech — the same domain Whisper and the Sherpa streaming model were trained on. The numbers above reflect realistic accuracy on teleconference audio and TED talks.

### Verdict on Whisper

At 6x realtime on real speech, Whisper is not viable for interactive use on the Snapdragon 690. I implemented it as an opt-in "higher accuracy" mode: the user records first, then waits for transcription with a progress bar. The progress bar self-calibrates over multiple uses by tracking the actual realtime factor via exponential moving average.

Whisper does provide excellent per-word metadata via `whisper_full_get_token_data()`: confidence scores and timestamps for every token. I use this to render red squiggly underlines on low-confidence words and to enable tap-a-word-to-seek-audio. At 20.7% WER on real-world audio it's better than the streaming engines (26-31%), but the two-pass approach with the GigaSpeech offline model achieves 12.3% WER at real-time speed, making Whisper's accuracy advantage less compelling.

---

## Why Vosk Works

[Vosk](https://alphacephei.com/vosk/) ([Android demo](https://github.com/alphacep/vosk-android-demo)) is designed for real-time streaming. You feed PCM frames via `Recognizer.acceptWaveForm()` and get partial/final results back synchronously. Unlike `SpeechRecognizer`, the app owns the `AudioRecord`. There is only one mic consumer, so there is no contention.

The complete working loop:

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    16000, AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT, bufferSize
)
val recognizer = Recognizer(model, 16000f)
recognizer.setWords(true)       // per-word confidence + timestamps
recognizer.setPartialWords(true)

val encoder = AudioRecordCapture(cacheDir)
encoder.startEncoderOnly()      // Opus/WebM encoder, no own AudioRecord

audioRecord.startRecording()
val buffer = ByteArray(4000)    // ~0.125s chunks for responsive streaming

while (recording) {
    val read = audioRecord.read(buffer, 0, buffer.size)
    if (read > 0) {
        encoder.feedPcm(buffer, read)            // → compressed audio file
        if (recognizer.acceptWaveForm(buffer, read)) {
            val result = recognizer.finalResult  // JSON with words
            // Parse and insert TextBlock
        }
    }
}
```

One `AudioRecord`, two consumers (encoder + recognizer), both in the same process and thread. No platform sharing issues because there is only one audio capture session.

The buffer size matters for responsiveness. I started with 1-second buffers (32KB) and had noticeable gaps between recognized sentences. Reducing to ~0.125s (4KB) made partial results more fluid and silence detection more responsive.

Dependency: `com.alphacephei:vosk-android:0.3.75`, model `vosk-model-small-en-us-0.15` (~40 MB). With `setWords(true)`, Vosk returns per-word confidence and timestamps in JSON, enabling the same features as Whisper's token API.

A foreground service (`AudioRecordingService` with `foregroundServiceType="microphone"`) keeps recording alive when the app is backgrounded. This matters for lecture capture where the user may switch apps briefly.

---

## Sherpa-ONNX: Twice as Fast as Vosk

After shipping with Vosk, I benchmarked [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) (from the next-gen Kaldi project). The streaming zipformer processes audio at 0.14x realtime — nearly twice as fast as Vosk's 0.22x — with better WER on conference calls (see the comparison table above for full results).

### The API

Sherpa-ONNX provides the same streaming pattern as Vosk: feed PCM chunks, get results back. The key difference is Sherpa accepts `float[]` samples (normalized to [-1, 1]) instead of Vosk's `byte[]` (16-bit PCM).

```kotlin
val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(encoderPath, decoderPath, joinerPath),
        tokens = tokensPath,
        numThreads = 2,
        modelType = "zipformer2"
    ),
    enableEndpoint = true
))

val stream = recognizer.createStream()
stream.acceptWaveform(floatSamples, sampleRate)
while (recognizer.isReady(stream)) recognizer.decode(stream)
if (recognizer.isEndpoint(stream)) {
    val result = recognizer.getResult(stream)  // .text, .tokens, .timestamps
    recognizer.reset(stream)
}
```

Model: [`sherpa-onnx-streaming-zipformer-en-2023-06-26`](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) int8 quantized (encoder 26 MB, decoder 2 MB, joiner 11 MB). Dependency: `com.bihe0832.android:lib-sherpa-onnx:6.25.12`.

### The 6-second model load

Sherpa's model load time (6,076ms) is 8x slower than Vosk (736ms) and 12x slower than Whisper (514ms). I profiled it by separating native library loading from ONNX model initialization:

```
Phase                          Time
──────────────────────────────────────
Native library load (JNI)        4ms
ONNX model init (3 models)   6,076ms
──────────────────────────────────────
Total                         6,080ms
```

The native `.so` is only 4.5 MB and loads instantly. The cost is entirely in the ONNX runtime's graph optimization pass, which runs separately for each of the three transducer models (encoder, decoder, joiner). Vosk and Whisper each load a single model file; Sherpa loads three.

This is a [known issue](https://github.com/k2-fsa/sherpa-onnx/issues/211). The fix is to convert models to [ORT format](https://onnxruntime.ai/docs/reference/ort-format-models.html) (pre-baked graph optimizations that skip the runtime optimization pass):

```bash
python -m onnxruntime.tools.convert_onnx_models_to_ort --optimization_style=Fixed
```

Pre-converted ORT models for this zipformer are available at [`w11wo/sherpa-onnx-ort-streaming-zipformer-en-2023-06-26`](https://huggingface.co/w11wo/sherpa-onnx-ort-streaming-zipformer-en-2023-06-26) on HuggingFace. I benchmarked both formats on the Palma 2 Pro:

```
Format    Load      RTF      WER
─────────────────────────────────
ONNX      5,837ms   0.138   11.2%
ORT       2,074ms   0.134   11.2%
```

ORT cuts model load time by **2.8x** (5.8s → 2.1s) with identical transcription output across all 12 test utterances — the conversion is lossless. RTF is marginally faster too. The improvement is less dramatic than the [0.6s reported on desktop](https://github.com/k2-fsa/sherpa-onnx/issues/211) because the Snapdragon 690 has less memory bandwidth for the remaining initialization work, but 2 seconds is acceptable with eager pre-loading.

Even with ORT, this is a one-time cost per process. Once loaded, the `OnlineRecognizer` can create streams and decode indefinitely. The natural mitigation is eager pre-loading — but that comes with a steep memory cost.

### The 221 MB memory cost

I profiled native memory, Java heap, and PSS across the full lifecycle (baseline → model loaded → active transcription → stream released → recognizer released):

```
Phase                         Java    Native       PSS
──────────────────────────────────────────────────────
baseline                      3.3      6.9     76.6
model loaded                  3.3    228.1    249.2
during transcription         19.3    231.0    273.3
stream released              19.3    228.8    271.6
recognizer released          19.3     11.7    138.4
──────────────────────────────────────────────────────
Model load delta             +0.0   +221.2   +172.6
Active stream overhead      +16.0     +2.8    +24.1
```

Source: `TranscriptionBenchmarkTest#measure_sherpa_ort_memory`

The ONNX runtime allocates **221 MB of native memory** to hold the three transducer models (39 MB on disk). That's a 5.7x expansion — the runtime unpacks weight tensors, allocates operator workspace buffers, and builds the optimized execution graph in memory. The Java heap barely moves; this is almost entirely native allocation invisible to Android's standard memory reporting.

During active transcription, the stream adds only ~3 MB native and ~16 MB Java (for PCM buffers and result objects). After releasing the stream, memory stays at the model-loaded level. Only releasing the `OnlineRecognizer` itself frees the native memory.

For comparison, I profiled Vosk through the same lifecycle:

```
Phase                         Java    Native       PSS
──────────────────────────────────────────────────────
baseline                      3.0      6.8     68.1
model loaded                  3.4    122.3    195.3
during transcription         22.5    171.5    270.1
recognizer closed            22.5    122.5    239.5
model closed                 22.5      7.0    164.6
──────────────────────────────────────────────────────
Model load delta             +0.4   +115.5   +127.2
Active stream delta         +19.0    +49.2    +74.8
```

Side by side:

```
                          Sherpa ORT     Vosk
                          ──────────     ────
Model on disk               39 MB       68 MB
Model in memory (native)  +221 MB     +116 MB
Stream overhead (native)    +3 MB      +49 MB
Total PSS during use       273 MB      270 MB
```

Sherpa's model consumes nearly 2x the native memory of Vosk for the idle model (221 MB vs 116 MB). But during active transcription, total memory converges — Vosk's recognizer allocates 49 MB for its streaming buffers and language model state, while Sherpa's stream adds only 3 MB. The cost of pre-loading Sherpa is paid at idle; during actual use, both engines consume roughly the same memory.

On the Palma 2 Pro (6 GB RAM), 173 MB PSS is ~3% of total memory. That's tolerable for a foreground app, but it makes the process a high-value target for the OOM killer when backgrounded.

The lifecycle I settled on:

1. **Pre-load on document open.** When the user opens a document (the only screen where recording is possible), start loading the recognizer on a background coroutine. The 2-second ORT load overlaps with document rendering and is invisible.
2. **Block recording until ready.** If the user taps the record button before the model finishes loading, show a brief loading indicator ("Preparing speech engine...") rather than silently dropping the first seconds of speech. The user should know not to start speaking yet. Once the model is ready, transition directly into recording with no additional delay.
3. **Keep the model resident between recordings.** After a recording session ends, keep the `OnlineRecognizer` alive. A second tap to record starts instantly — no 2-second wait. This matters for lecture capture where the user may pause and resume multiple times.
4. **Release on background (unless recording).** When the app leaves the foreground (`onStop()`), release the recognizer and reclaim the 221 MB — but only if no recording session is active. During lecture capture the user may switch apps briefly; the foreground service keeps the mic and transcription running, and the model must stay loaded. Once the recording ends and the app is still in the background, release then.

This gives instant recording on every tap except the very first one after opening a document, and even that first tap only blocks if the user is faster than the 2-second pre-load — which overlaps with the document open animation.

### Partial vs final behavior

I measured how each engine handles partial (volatile, in-progress) and final (committed) transcription results, following the [latency taxonomy from Speechmatics](https://www.speechmatics.com/company/articles-and-news/speed-you-can-trust-the-stt-metrics-that-matter-for-voice-agents):

```
Metric                    Vosk          Sherpa ORT
──────────────────────────────────────────────────
Time-to-first-partial     1.06s avg     1.22s avg
Partial updates/utt       12 avg        11 avg
Partial→final word Δ      0.8 words     0.0 words
```

Source: `TranscriptionBenchmarkTest#measure_partial_final_latency`

Both engines need about 1 second of audio before the first text appears. Vosk produces first partials ~160ms sooner. But the important difference is **partial stability**: Sherpa's partials are append-only — each update only adds tokens, never revises previous ones. Vosk's partials can retract and rewrite words (0.8-word average change between the last partial and the final). In a live transcript on screen, Sherpa's monotonic partials mean text never flickers or jumps backward. Vosk's revisions cause visible rewrites, which is distracting during note-taking.

Sherpa also emits sub-word partials (SentencePiece tokens like "CONCOR" → "CONCORD"), giving earlier visual feedback that speech is being recognized, even before a full word boundary.

### Other trade-offs vs Vosk

Sherpa's `OnlineRecognizerResult` provides token-level timestamps but not confidence values in the current Android AAR (bihe0832 v6.25.12). Per-token log probabilities (`ys_log_probs`) were [merged for offline transducer models](https://github.com/k2-fsa/sherpa-onnx/pull/2843) in Dec 2025 but haven't reached the Android wrapper yet. A [broader PR for online + offline vocab log-prob distributions](https://github.com/k2-fsa/sherpa-onnx/pull/2897) is in draft. Once online `ys_log_probs` ships in an Android release, it would enable the red-underline feature for low-confidence words. Until then, confidence-based highlighting requires Vosk or a post-hoc heuristic (e.g., flagging words where the partial revised before settling — though as noted above, Sherpa's partials rarely revise).

Sherpa's WER on very short utterances (1-2 words) was unreliable — the endpoint detector sometimes cut off speech or produced spurious text. On utterances longer than 3 seconds, Sherpa and Vosk were comparable in accuracy.

### Timestamp alignment: the 320ms correction

Tapping a transcribed word to seek in the audio consistently landed ~300ms past the word onset. The word was already mid-syllable by the time playback started.

This is a known property of streaming transducer models. The RNN-T loss function doesn't enforce temporal alignment — the model is free to emit tokens at any point along the monotonic alignment path. In practice, streaming models need to "see" enough future context before committing to a token, so timestamps are systematically late. [Microsoft measured ~400ms delay for vanilla RNN-T](https://www.microsoft.com/en-us/research/wp-content/uploads/2020/04/rnnt-icassp2020.pdf). Google's [FastEmit](https://arxiv.org/abs/2010.11148) and [Self-Alignment](https://arxiv.org/abs/2105.05005) reduce this to 100-250ms, but those require retraining the model with emission regularization. Our pre-trained zipformer has no such regularization.

The fix: subtract a latency compensation derived from the model's chunk size. For the `chunk-16-left-128` model:

```
chunk_frames = 16 (subsampled encoder frames per chunk)
frame_duration = 40ms (10ms frame shift × 4x conv subsampling)
chunk_duration = 16 × 40ms = 640ms

compensation = chunk_duration / 2 = 320ms
```

The `/ 2` is because the token is emitted somewhere within the chunk — on average, halfway through. The compensation is applied once in `SherpaTokenMerger.mergeTokens()`, clamped to zero (never negative), and flows through to the `WordInfo.startMs` stored in each `TextBlock`. No other code needs to change — the tap handler and audio player use the already-compensated value.

If the model changes (different chunk size or emission-regularized training), update the constants in `SherpaTokenMerger`. The formula is: `compensation = chunk_frames × frame_duration_ms / 2`.

---

## Sherpa Offline: Choosing the Right Model

After shipping with streaming Sherpa, I benchmarked the offline (non-streaming) variants. Initial results on LibriSpeech test-clean were dramatic — 0.5% WER for the offline zipformer vs 11.2% for streaming. But LibriSpeech was in the training set, so those numbers were misleading.

### The LibriSpeech trap

My first offline benchmark used `sherpa-onnx-zipformer-en-2023-04-01`, an offline transducer trained on LibriSpeech 960h. It achieved 0.5% WER on LibriSpeech test-clean — but this is the same acoustic domain as its training data (clean read English audiobooks). Real lectures and meetings are a different beast.

### Cross-domain evaluation

To get honest numbers, I benchmarked the streaming model and four offline models on data **none of them were trained on**: [Earnings-22](https://github.com/revdotcom/speech-datasets) (financial conference calls with diverse accents) and [TED-LIUM 3](https://huggingface.co/datasets/distil-whisper/tedlium-long-form) (TED talks).

All models use [ORT format](https://onnxruntime.ai/docs/reference/ort-format-models.html) (pre-baked graph optimizations) for consistent load-time comparison. ORT conversion is lossless — identical WER and RTF, but ~2-4x faster model init.

```
Model                              Load    Size   Native    RTF   Earn-22   TED   Avg WER  Timestamps
──────────────────────────────────────────────────────────────────────────────────────────────────────
Zipformer streaming (LS)           2.3s    71 MB  +226 MB   0.14   20.0%  32.0%   26.0%      Yes
─ offline models ─────────────────────────────────────────────────────────────────────────────────────
Zipformer 2023-04-01 (LS)         5.5s   181 MB  +200 MB   0.08   13.3%  14.8%   14.0%       No
Zipformer 2023-06-26 (LS)         3.4s    68 MB  +226 MB   0.06   18.7%  30.3%   24.5%       No
Multidataset (LS+GS+CV)           3.9s   123 MB  +200 MB   0.08    8.0%  16.4%   12.2%       No
GigaSpeech (YouTube/podcasts)     3.7s    68 MB  +216 MB   0.06    6.7%  18.0%   12.3%       No
Paraformer (Alibaba)              4.1s   219 MB  +490 MB   0.08    8.0%  19.7%   13.8%       No
Parakeet TDT 0.6B (NeMo)           —     631 MB +1645 MB     —      —      —      OOM        —
```

Source: `TranscriptionBenchmarkTest#benchmark_offline_models`, `benchmark_all_engines_unseen`

Training data key: LS = LibriSpeech (960h audiobooks), GS = GigaSpeech (10Kh YouTube/podcasts), CV = CommonVoice. Native = native memory delta when model is loaded (measured via `Debug.getNativeHeapAllocatedSize()`). Timestamps = whether `OfflineRecognizerResult.getTokens()` and `getTimestamps()` return non-empty arrays (sherpa-onnx AAR v6.25.12).

The Parakeet TDT 0.6B (NVIDIA's SOTA model, 120Kh training data) crashed the Palma 2 Pro — its 1.6 GB native memory footprint is too much for a 6 GB device. Paraformer was expected to return per-word timestamps via its CIF alignment mechanism, but the sherpa-onnx AAR does not populate them.

**GigaSpeech and Multidataset tied at ~12% WER** on unseen data — roughly half the streaming model's error rate. GigaSpeech won on conference calls (6.7% — its YouTube/podcast training domain is closer to teleconference audio). At 68 MB on disk it's the smallest offline model, with the biggest ORT speedup (13.6s ONNX → 3.7s ORT, 3.7x).

### Why offline is so much better

Streaming models must emit tokens as audio arrives — they can't "look ahead." The streaming zipformer sees only a 640ms chunk before committing to a token. The offline zipformer sees the entire utterance before decoding, enabling:

- Better word boundary detection (no premature token emission)
- Global context for ambiguous words (homophones, names)
- No endpoint detection artifacts (the 50% WER on "POOR ALICE" disappears)

### The hybrid approach: per-endpoint two-pass

The sherpa-onnx project already ships a [two-pass Android example](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnx2Pass) that combines both models at the application layer. The pattern: run the streaming recognizer for real-time partials, and when it detects an endpoint (silence between sentences), send the buffered audio segment to the offline recognizer for a second pass. The offline result replaces the streaming result for that segment. A 500ms overlap buffer (8000 samples at 16kHz) bridges segment boundaries.

This is better than the whole-recording re-transcription I originally planned. Instead of waiting until the user stops recording, each sentence is upgraded to near-perfect accuracy within a second of the user finishing it.

I validated the two-pass approach on the Palma 2 Pro using LibriSpeech test data. The per-endpoint segmentation introduces zero WER degradation — every utterance matches the offline-only result. RTF overhead is small: 0.19 vs 0.16 for streaming-only.

The production configuration uses the [GigaSpeech zipformer](https://huggingface.co/imedemi/sherpa-onnx-ort-zipformer-gigaspeech-2023-12-12) (trained on 10,000 hours of YouTube and podcast audio) in ORT format. On unseen evaluation data:

- **6.7% WER** on financial conference calls (Earnings-22)
- **18.0% WER** on TED talks (TED-LIUM 3)
- **68 MB** on disk (vs 181 MB for the LibriSpeech-trained model)
- **3.7s** model load time (ORT format, down from 13.6s with ONNX)
- **0.06x RTF** — 10 seconds of audio transcribed in 0.6 seconds
- **+216 MB** native memory during use

The tradeoff: 68 MB additional model storage, ~4 seconds load time (ORT), and ~216 MB native memory for the offline model. On a 6 GB device this is feasible — the offline model loads on-demand when recording starts and is ready before the first endpoint (~4s of speech).

---

## Hardware: The Mic Is Fine (for This Purpose)

Before blaming transcription quality on hardware, I measured the Palma 2 Pro's microphone SNR using RMS dB readings. Noise floor: -0.7 dB. Peak speech: 10.0 dB. SNR: ~10.7 dB.

This is low by professional recording standards (studio mics target 40+ dB), but adequate for speech recognition in a quiet room. The point of this measurement was narrower than "the mic is good." I wanted to rule out the mic as the bottleneck for transcription accuracy. When Vosk or Whisper produced errors, the audio waveform showed clean speech. The errors were in the model, not the capture.

---

## Gotchas: WebM Audio Recording

Two issues specific to using Opus/WebM for audio recording that cost me significant debugging time.

### Timestamps must be relative

`MediaCodec.queueInputBuffer()` requires a presentation timestamp in microseconds. I initially passed `System.nanoTime() / 1000`, which yields absolute microseconds since boot (~200 billion). This produced WebM files where the container reported a duration of ~55 hours. Seeking to any position jumped to the wrong point in the audio because the player calculated byte offsets against the inflated timeline.

Fix: track `startTimeUs` at recording start, pass `System.nanoTime() / 1000 - startTimeUs`.

### MediaPlayer cannot seek in audio-only WebM

`MediaPlayer.seekTo()` silently fails on audio-only WebM files. `MediaMuxer` does not write a Cues element (seek index) for audio-only WebM because the [WebM specification](https://www.webmproject.org/docs/container/) defines Cues in terms of video keyframes. No video track means no Cues, which means no seeking.

I chose WebM over MP4 for crash resilience. Standard MP4 via `MediaMuxer` writes the `moov` atom (the file's index) at the end of recording. If the app crashes before that final write, the entire file is unplayable. Fragmented MP4 via Media3's [`FragmentedMp4Muxer`](https://developer.android.com/reference/kotlin/androidx/media3/muxer/FragmentedMp4Muxer) would solve this by writing self-contained fragments progressively, but WebM was the simpler path for audio-only recording: each cluster is self-contained, so a truncated file is playable up to the last complete cluster, and Opus is a better codec than AAC for speech at low bitrates.

Fix: replace `MediaPlayer` with [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer) using `DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)`, which estimates byte offsets for approximate seeking without requiring Cues. Opus is a variable bitrate codec, so the seek position is an approximation. In practice the error is small enough (typically under a second) that tap-to-seek from transcript words still lands close to the right moment.

---

## Where This Lands

The working architecture: Sherpa-ONNX two-pass handles transcription — the streaming ORT model provides real-time italic partials during recording, and the offline GigaSpeech ORT model re-transcribes each sentence at endpoint detection for ~12% WER on real-world audio with no user-visible delay. Opus in an OGG container (via a custom `OggOpusWriter`) provides crash-resilient audio recording with sample-accurate seeking via granule positions. ExoPlayer handles playback with a bottom overlay bar for controls. Per-word timestamps (with 320ms latency compensation derived from the model's chunk size) enable tap-a-word-to-seek. A `PlaybackController` state machine manages the IDLE→PLAYING→PAUSED transitions.

Vosk remains available as a fallback. Whisper remains the batch option for users who want maximum accuracy and are willing to wait. The engine is selectable in settings, with Sherpa streaming as the default.

What's still unsolved: audio recording in `SpeechRecognizer` mode. If Google opens up the ML Kit GenAI `fromPfd()` API to third-party apps, that would be the best of both worlds. Until then, Sherpa-ONNX is the best option I have found for real-time on-device transcription on Android.

---

## Links

- [Android Audio Input Sharing](https://developer.android.com/guide/topics/media/sharing-audio-input)
- [ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- [Whisper models](https://huggingface.co/ggerganov/whisper.cpp)
- [Silero VAD](https://github.com/snakers4/silero-vad)
- [Vosk](https://alphacephei.com/vosk/)
- [Vosk Android demo](https://github.com/alphacep/vosk-android-demo)
- [Vosk models](https://alphacephei.com/vosk/models)
- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)
- [Sherpa-ONNX streaming zipformer model](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26)
- [Sherpa-ONNX ORT streaming zipformer model](https://huggingface.co/w11wo/sherpa-onnx-ort-streaming-zipformer-en-2023-06-26)
- [Sherpa-ONNX two-pass Android example](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnx2Pass)
- [Sherpa-ONNX GigaSpeech ORT model](https://huggingface.co/imedemi/sherpa-onnx-ort-zipformer-gigaspeech-2023-12-12)
- [Sherpa-ONNX Multidataset ORT model](https://huggingface.co/imedemi/sherpa-onnx-ort-multidataset-transducer-2023-05-04)
- [Sherpa-ONNX Paraformer ORT model](https://huggingface.co/imedemi/sherpa-onnx-ort-paraformer-en-2024-03-09)
- [Sherpa-ONNX offline zipformer 2023-04-01 ORT model](https://huggingface.co/imedemi/sherpa-onnx-ort-zipformer-en-2023-04-01)
- [Sherpa-ONNX offline zipformer 2023-06-26 ORT model](https://huggingface.co/imedemi/sherpa-onnx-ort-zipformer-en-2023-06-26)
- [Earnings-22 benchmark dataset](https://github.com/revdotcom/speech-datasets)
- [TED-LIUM 3 (distil-whisper long-form)](https://huggingface.co/datasets/distil-whisper/tedlium-long-form)
- [FastEmit: Low-latency Streaming ASR](https://arxiv.org/abs/2010.11148)
- [Self-Alignment for RNN-T](https://arxiv.org/abs/2105.05005)
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)
- [Just Press Record](https://www.openplanetsoftware.com/just-press-record/)
- [Mokke Ink](https://github.com/imedwei/mokke-ink-notes)
- [eWritable: Boox Palma 2 Pro](https://ewritable.net/how-the-boox-palma-2-pro-quietly-became-part-of-my-daily-life/)
