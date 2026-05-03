---
status: approved
author: spm@edwei.com
created: 2026-04-12
updated: 2026-04-12
---

# Audio-to-Text Transcription — Engineering Design

## Context

Mokke is a handwriting-to-text note-taking app for e-ink Android tablets. The user wants to add audio transcription so they can:
1. **Quick voice memo** — dictate a quick note instead of writing it
2. **Lecture/talk capture** — record audio during a talk, get transcribed text that can be marked up with the stylus

This aligns with VISION.md's Layer 1 (Capture) — adding a third input modality alongside stylus and finger. The output still flows into the same note object model (strokes + line_text_cache + search index).

---

## Use Cases Driving the Design

| Use case | Duration | Connectivity | Latency need | Quality need |
|----------|----------|-------------|-------------|-------------|
| Quick voice memo | 5–30s | Variable (on the go) | Low (<2s) — feel instant | Medium — short phrases |
| Lecture capture | 30–90 min | Usually WiFi | Can be async | High — dense, technical speech |

These two cases pull in opposite directions on almost every axis, which is the central tension in the design.

---

## Approach A: On-Device Transcription

### Technology options

| Engine | Model size | Languages | Accuracy (WER) | Streaming | License |
|--------|-----------|-----------|----------------|-----------|---------|
| **Android SpeechRecognizer** (system) | 0 (OS-provided) | ~60 | ~15–20% | Yes | Free, bundled |
| **Vosk** (offline models) | 50–200 MB | ~20 | ~12–18% | Yes | Apache 2.0 |
| **Whisper.cpp** (GGML port) | 75 MB (base) – 1.5 GB (medium) | 99 | ~8–12% (medium) | Chunked | MIT |
| **Google MLKit on-device** | ~50 MB per lang | Limited | Similar to SpeechRecognizer | No | Free |

### Recommended on-device engine: Android SpeechRecognizer (primary) + Whisper.cpp (optional)

**Android SpeechRecognizer** is the pragmatic first step:
- Zero additional APK size — every Android device ships with it
- Streaming partial results for real-time feedback
- On Boox devices, Google's offline speech model is available (Settings > Google > Voice > Offline)
- Handles the quick-memo case well

**Whisper.cpp** is the quality upgrade for lecture capture:
- `whisper-base.en` (75 MB) gives good English results on tablet-class hardware
- Can process 30s chunks in ~3–5s on a mid-range ARM SoC
- No network dependency — critical for airplane/conference WiFi
- But: 1.5 GB for multilingual `medium` model, and real-time factor ~0.3–0.5x on e-ink tablets (i.e., takes 30–50% of real-time to process)

### Tradeoffs

| Dimension | Assessment |
|-----------|-----------|
| **Latency** | SpeechRecognizer: real-time streaming. Whisper: 3–5s per 30s chunk (acceptable for lecture). |
| **Accuracy** | SpeechRecognizer: good for clear speech, degrades in noise. Whisper base: better noise handling, worse than cloud. |
| **Offline** | Full offline capability — critical for e-ink tablets used in airplane mode or poor WiFi venues. |
| **APK size** | SpeechRecognizer: +0 MB. Whisper base: +75 MB (downloaded on demand, not bundled). |
| **Battery** | Significant CPU drain for continuous Whisper inference. SpeechRecognizer uses hardware DSP on many devices. |
| **Privacy** | All processing local. No audio leaves device. Strong selling point for personal notes. |
| **Languages** | SpeechRecognizer: depends on installed languages. Whisper: multilingual with larger models. |
| **E-ink concern** | CPU-intensive inference competes with stroke rendering. Need background thread isolation. |

---

## Approach B: Cloud Transcription

### Technology options

| Service | Cost | Accuracy (WER) | Streaming | Max duration | Languages |
|---------|------|----------------|-----------|-------------|-----------|
| **Google Cloud Speech-to-Text v2** | $0.006/15s (standard) | ~5–8% | Yes (streaming RPC) | Unlimited (streaming) | 125+ |
| **OpenAI Whisper API** | $0.006/min | ~5–8% | No (batch) | 25 MB file | 57 |
| **Deepgram** | $0.0043/min (Nova-2) | ~5–7% | Yes | Unlimited | 36 |
| **AssemblyAI** | $0.01/min (Universal-2) | ~4–6% | Yes | Unlimited | 20+ |

### Recommended cloud engine: Google Cloud Speech-to-Text v2

- Already have Google dependency (ML Kit for handwriting recognition)
- Streaming API gives real-time partials — good UX for quick memos
- Best language coverage (125+) — matches Mokke's multilingual handwriting recognition
- Medical/legal vocabulary models available for domain-specific use

### Tradeoffs

| Dimension | Assessment |
|-----------|-----------|
| **Latency** | Streaming: ~300ms partial results. Batch: seconds after upload. Both excellent. |
| **Accuracy** | Best-in-class. 5–8% WER, handles accents/noise/technical vocabulary well. |
| **Offline** | Not available. Complete dependency on network. Lectures in poor-WiFi venues are a problem. |
| **Cost** | Lecture capture: 60 min = ~$1.44 (Google) or ~$0.36 (Deepgram). Adds up for daily use. |
| **Privacy** | Audio sent to third-party servers. Potential concern for sensitive meeting notes. |
| **Complexity** | API key management, auth flow, billing, rate limiting, retry logic. |
| **APK size** | +0 MB (API calls only). |
| **Battery** | Minimal — just network I/O. Much less CPU than on-device inference. |

---

## Approach C: Hybrid (Recommended)

Use on-device as the default, cloud as an opt-in quality upgrade.

### Architecture

```
                    ┌─────────────────────┐
                    │  AudioCaptureManager │  ← manages MediaRecorder / AudioRecord
                    │  (WritingActivity)   │
                    └──────────┬──────────┘
                               │ audio frames / file URI
                    ┌──────────▼──────────┐
                    │  AudioTranscriber    │  ← interface (like TextRecognizer)
                    │  (interface)         │
                    └──────────┬──────────┘
                    ┌──────────┴──────────┐
            ┌───────▼───────┐    ┌────────▼────────┐
            │ SystemSpeech  │    │ CloudSpeech      │
            │ Transcriber   │    │ Transcriber      │
            │ (default)     │    │ (opt-in)         │
            └───────────────┘    └─────────────────┘
```

### How it works

1. **Quick memo**: User taps mic button → `SystemSpeechTranscriber` streams recognition → partial results appear as they speak → final text inserted at cursor position as recognized text in `lineTextCache`
2. **Lecture capture**: User taps record → audio saved to file alongside document → `SystemSpeechTranscriber` provides real-time rough transcript → when recording stops (or later, on demand), user can optionally re-transcribe via cloud for higher accuracy
3. **Offline fallback**: If cloud is selected but no network, fall back to on-device with a toast notification

### Key design decisions

**Q: Where does transcribed text go in the document model?**

**Answer: New `TextBlock` object** — a first-class canvas object (paralleling `DiagramArea`) that holds transcribed text, audio file reference, and timestamp correlation. This gives:
- Visible rendering on the canvas (not ghost text in lineTextCache)
- Per-block audio timestamp mapping — tap a block to jump to that moment in the recording
- Same appearance as handwriting-recognized text (no visual distinction)
- Clean per-sentence granularity for lecture capture (each pause/sentence = one TextBlock)
- Text also written to `lineTextCache` so search indexing works unchanged

**Q: Should we store the audio file?**

Yes, always. Even for quick memos. The audio file is the source of truth — transcription can be re-run with better models later. Store as `.opus` (good compression, ~16 KB/s at voice quality).

**Q: How to package documents with audio dependencies?**

**Answer: ZIP-based document bundle.** The `.inkup` extension stays but the file becomes a ZIP container (like `.docx`, `.epub`, `.key`):

```
My Lecture Notes.inkup              ← ZIP file
├── document.pb                     ← binary protobuf (current content)
├── audio/
│   ├── rec-001.opus                ← first recording
│   └── rec-002.opus                ← second recording
└── (future: images/, attachments/)
```

**Backward compatibility:** Detect format by checking magic bytes — ZIP starts with `PK` (0x504B), raw protobuf doesn't. Legacy `.inkup` files load as raw protobuf. New saves always write ZIP format (even without audio — just `document.pb` inside the ZIP). This is a one-way migration: once re-saved, the file is ZIP format.

**Implications for storage layer:**
- `DocumentStorage.save()` writes a `ZipOutputStream` instead of raw bytes
- `DocumentStorage.load()` checks magic bytes → ZIP path or legacy raw-proto path
- `AtomicFile` pattern still works (atomic write of the ZIP file)
- Sync folder export: just copy the single `.inkup` ZIP — no change needed
- Share intents: single file, works naturally
- Audio files referenced by filename in `TextBlockProto.audio_file` / `AudioRecordingProto.audio_file`, matching paths inside the ZIP

**Q: Proto schema changes?**

```protobuf
// New: text block rendered on canvas, optionally linked to audio
message TextBlockProto {
    optional string id = 1 [default = ""];
    optional int32 start_line_index = 2 [default = 0];
    optional int32 height_in_lines = 3 [default = 1];
    optional string text = 4 [default = ""];
    optional string audio_file = 5 [default = ""];       // filename relative to doc folder
    optional int64 audio_start_ms = 6 [default = 0];     // offset into recording
    optional int64 audio_end_ms = 7 [default = 0];       // offset into recording
}

// New: metadata for an audio recording session
message AudioRecordingProto {
    optional string audio_file = 1 [default = ""];       // filename relative to doc folder
    optional int64 start_time_ms = 2 [default = 0];      // epoch ms when recording started
    optional int64 duration_ms = 3 [default = 0];
}

message ColumnDataProto {
    // ... existing fields 1-4 ...
    repeated TextBlockProto text_blocks = 5;             // NEW
}

message DocumentProto {
    // ... existing fields 1-7 ...
    repeated AudioRecordingProto audio_recordings = 8;   // NEW
}
```

`TextBlockProto` lives in `ColumnDataProto` (alongside strokes and diagram_areas) because it's a canvas object with a spatial position. `AudioRecordingProto` lives in `DocumentProto` as document-level metadata about recording sessions.

This is additive and backward-compatible. Old clients ignore field 8.

---

## Comparison Summary

| Factor | On-Device | Cloud | Hybrid |
|--------|-----------|-------|--------|
| Offline support | Full | None | Full (degraded quality) |
| Accuracy | Good (8–18% WER) | Best (4–8% WER) | Best of both |
| Latency | Good to moderate | Excellent (streaming) | Excellent |
| Cost | Free | $0.25–1.50/hr | Free default, pay for quality |
| Privacy | Full | Audio leaves device | User chooses |
| APK size | +0–75 MB | +0 MB | +0 MB (models downloaded on demand) |
| Battery | High (inference) | Low (network only) | Depends on mode |
| Implementation effort | Medium | Medium | Medium-high |
| Multilingual | Limited (on-device models) | Excellent | Excellent when online |

---

## Recommended Implementation Plan

### Phase 1: Voice Memo + Lecture Capture (on-device, ships together)

**Infrastructure:**
- Add `RECORD_AUDIO` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` permissions
- Proto schema: add `AudioSegmentProto` to `DocumentProto` (field 8)
- Golden file for new schema version per existing protocol
- `AudioTranscriber` interface (mirrors `TextRecognizer`): `start()`, `stop()`, `onPartialResult`, `onFinalResult`
- `SystemSpeechTranscriber` implementation using Android `SpeechRecognizer`
- Audio file storage: `.opus` files in document folder, referenced from proto

**Quick memo flow:**
- Mic toggle button in gutter/toolbar
- Tap to start → SpeechRecognizer streams partial results → rendered as a live TextBlock at current line position
- Tap to stop → final TextBlock committed with text + audio timestamps, audio file saved, `AudioRecordingProto` written

**Lecture capture flow:**
- Long-press mic (or toggle in a small popover) to enter lecture mode
- `MediaRecorder` in a foreground service with persistent notification
- `SpeechRecognizer` runs in parallel for real-time rough transcript
- Each sentence/pause boundary creates a new `TextBlock` with `audio_start_ms`/`audio_end_ms` pointing into the recording
- User can continue writing with the stylus while recording — TextBlocks and strokes coexist on the canvas
- Recording state indicator (small pulsing dot) — minimal e-ink refresh impact

**TextBlock rendering:**
- Canvas renders TextBlocks as typeset text at their line position (same font/style as recognized handwriting text)
- TextBlock text also mirrored to `lineTextCache` so search, export, and markdown pipelines work unchanged
- Tap a TextBlock → future: jump to that audio position (Phase 3 rich interaction)

**Storage & export:**
- `.inkup` becomes a ZIP bundle containing `document.pb` + `audio/*.opus`
- `DocumentStorage` migrated to ZIP I/O: magic-byte detection for backward compat with raw protobuf
- Audio written to ZIP as separate entries (not embedded in protobuf — keeps proto small)
- Sync folder: single `.inkup` ZIP file copies cleanly
- `SearchIndexManager` already indexes `lineTextCache` — transcribed text searchable automatically
- `MarkdownExporter` includes TextBlock text inline at correct line positions

### Phase 2: Cloud Upgrade (opt-in)
- Settings toggle: "Use cloud transcription for higher accuracy"
- Google Cloud Speech-to-Text v2 integration
- Re-transcribe existing recordings on demand
- Batch processing of lecture recordings

### Phase 3: Rich Audio Interaction
- Tap a TextBlock → inline playback controls, scrub to that timestamp in the recording
- Swipe between TextBlocks to navigate the audio timeline
- Re-transcribe a single TextBlock (send its audio range to cloud for better accuracy)
- Edit transcribed text inline (correct misrecognitions without re-recording)

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| E-ink refresh conflict during recording UI updates | Minimize UI updates; use a simple pulsing dot, not waveform |
| Battery drain from continuous on-device transcription | Use SpeechRecognizer (hardware-accelerated) not Whisper for real-time |
| Audio recording interrupted by app lifecycle | Use foreground service with notification for lecture capture |
| Proto schema evolution | Follow existing golden file protocol — add AudioSegmentProto as new repeated field |
| SpeechRecognizer unavailable on some Boox firmwares | Graceful feature detection; disable mic button if unavailable |
| ZIP format migration breaks older app versions | Magic-byte detection: old files load as raw proto, new files always ZIP. One-way migration on save. |
| Large ZIP rewrites during lecture (audio growing) | Buffer audio to temp file during recording; only write to ZIP bundle on save/stop. AutoSaver skips audio mid-recording. |

---

## Resolved Design Decisions

- **Text styling**: Transcribed text uses the same appearance as handwriting-recognized text — unified experience, no visual distinction.
- **Scope**: Both quick memo AND lecture capture in v1. Phase 1 and 2 from the plan above ship together.
- **Audio export**: Audio files always travel with the document — included in sync/export for re-listening and re-transcription.
- **Cloud provider**: Google Cloud Speech-to-Text v2 (deferred to Phase 3), consistent with existing Google/ML Kit dependency.
