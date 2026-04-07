# Speech-to-Text on Android E-Ink: Lessons from the Boox Palma 2 Pro

Android's built-in speech recognizer cannot record audio and transcribe it at the same time.

That's not a bug. It's a hard platform constraint. The `SpeechRecognizer` API owns the microphone exclusively, and Android's audio sharing policy prevents a second `AudioRecord` from capturing simultaneously. If you want to save the audio while transcribing, you have to bring your own speech recognition engine.

I discovered this while building a feature for [Mokke Ink](https://github.com/imedwei/mokke-ink-notes), a handwriting notes app I'm developing for Boox e-ink devices. The feature I wanted: record a lecture or meeting, transcribe it in real time onto the notes screen, and let the user annotate the transcript with lightweight margin notes. After the session, the user can play back the audio synced to the transcript, correct mis-transcribed words by tapping them, and generate a summary that weights the parts they marked as important.

This requires three things from the speech-to-text engine:

1. Real-time transcription while simultaneously saving the audio
2. Per-word timestamps and confidence scores (for tap-to-seek and error highlighting)
3. Accuracy good enough for note-taking on a mid-range mobile SoC

Cloud transcription services (Google Cloud Speech, Deepgram, AssemblyAI) would be more accurate, but I ruled them out. This is an open-source app for personal note-taking. Streaming meeting audio to a third-party server is a privacy problem, and ongoing API costs don't make sense for a passion project. Everything needed to run on-device.

I tried three approaches. Here is where I landed:

```
Engine               Real-time   Audio   Per-word   Model
───────────────────────────────────────────────────────────
SpeechRecognizer     ✓           ✗       ✗          0 MB
whisper.cpp          ✗ (4.5x)    ✓       ✓          75 MB
Vosk                 ✓           ✓       ✓          40 MB
```

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

### Verdict on Whisper

At 4.5x realtime even with all optimizations, Whisper is not viable for interactive use on the Snapdragon 690. I implemented it as an opt-in "higher accuracy" mode: the user records first, then waits for transcription with a progress bar. The progress bar self-calibrates over multiple uses by tracking the actual realtime factor via exponential moving average.

Whisper does provide excellent per-word metadata via `whisper_full_get_token_data()`: confidence scores and timestamps for every token. I use this to render red squiggly underlines on low-confidence words and to enable tap-a-word-to-seek-audio. So it earns its place as a batch option, even if it cannot drive the real-time experience.

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

I did not evaluate every on-device speech-to-text option. [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) (from the next-gen Kaldi project) is another strong candidate for streaming on-device recognition on Android. I chose Vosk because it had a mature Android library, well-documented streaming API, and met my requirements without further investigation. If you're starting fresh, Sherpa-ONNX is worth benchmarking.

A foreground service (`AudioRecordingService` with `foregroundServiceType="microphone"`) keeps recording alive when the app is backgrounded. This matters for lecture capture where the user may switch apps briefly.

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

The working architecture: Vosk handles real-time streaming transcription, Opus/WebM provides crash-resilient audio recording, and ExoPlayer enables seeking in the saved audio. Per-word confidence and timestamps from both Vosk and Whisper drive the interactive features: tap a word to jump to that moment in the audio, red underlines on low-confidence words to guide correction. Whisper remains available as a batch option for users who want higher accuracy and are willing to wait.

What's still unsolved: audio recording in `SpeechRecognizer` mode. If Google opens up the ML Kit GenAI `fromPfd()` API to third-party apps, that would be the best of both worlds, combining Google's on-device models with full audio access. Until then, Vosk is the best option I have found for real-time on-device transcription on Android.

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
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)
- [Just Press Record](https://www.openplanetsoftware.com/just-press-record/)
- [Mokke Ink](https://github.com/imedwei/mokke-ink-notes)
- [eWritable: Boox Palma 2 Pro](https://ewritable.net/how-the-boox-palma-2-pro-quietly-became-part-of-my-daily-life/)