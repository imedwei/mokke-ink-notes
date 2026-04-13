# Hybrid Two-Pass Transcription: Per-Endpoint Streaming → Offline Upgrade

## Context

Streaming Sherpa (zipformer ORT) gives 26% avg WER on unseen evaluation data (Earnings-22 + TED-LIUM 3). The GigaSpeech offline zipformer gives 12.3% avg WER at 0.06x RTF. The hybrid approach: show live partials during recording (streaming model), and at each endpoint (sentence boundary), immediately re-transcribe that segment with the offline model. The user sees text "upgrade" within ~1 second of finishing each sentence — no waiting until the recording stops.

WER measured on unseen data only (Earnings-22 financial conference calls, TED-LIUM 3 talks). Earlier benchmarks on LibriSpeech test-clean showed misleadingly low WER (0.5% offline, 11.2% streaming) because LibriSpeech overlaps with the models' training data.

```
Streaming only:  26.0% avg WER (20.0% Earn-22, 32.0% TED)
Two-pass:        12.3% avg WER ( 6.7% Earn-22, 18.0% TED)
RTF overhead:    0.19x vs 0.14x streaming-only
```

## Architecture

```
Recording:    Streaming Sherpa → live italic partials → committed TextBlock
                          │                                    ↑
              endpoint    │    buffer PCM between endpoints    │
              detected ───┤                                    │
                          ↓                                    │
                Offline GigaSpeech → re-transcribe segment → replace TextBlock
```

Per-endpoint, not whole-recording: each sentence is upgraded as it completes, not after the user stops recording.

## Implementation (as built)

### SherpaTranscriber two-pass flow

In `SherpaTranscriber`, the recording thread:

1. Feeds each PCM chunk to both the streaming recognizer and `AudioRecordCapture` (OGG/Opus encoder)
2. Accumulates float PCM in `pcmSegmentBuffer` between endpoints
3. When `recognizer.isEndpoint()` fires:
   - Gets the streaming result (tokens + timestamps via `SherpaTokenMerger`)
   - Flattens the PCM buffer with a 500ms overlap (`OVERLAP_SAMPLES = 8000`)
   - Runs the offline GigaSpeech model on the segment: `offlineRec.decode(segmentPcm, sampleRate)`
   - Aligns offline words to streaming timestamps via LCS matching (`alignTimestamps`)
   - Delivers the improved text as a final result
4. On `stop()`, flushes any remaining buffered audio through the same two-pass path

### Timestamp alignment via LCS

The offline model produces better text but no per-word timestamps (the GigaSpeech ORT model's `getTimestamps()` returns empty arrays in sherpa-onnx AAR v6.25.12). The streaming model has timestamps but worse text.

`alignTimestamps()` matches offline words to streaming words using Longest Common Subsequence:
- Matched words inherit the streaming word's timestamp
- Unmatched words get linearly interpolated timestamps within the segment

This preserves tap-to-seek accuracy while using the offline model's superior text.

### Model configuration

| Model | Role | Size | Load | Training data |
|-------|------|------|------|---------------|
| Zipformer streaming ORT | Real-time partials + timestamps | 71 MB | 2.3s | LibriSpeech 960h |
| GigaSpeech zipformer ORT | Offline second pass | 68 MB | 3.7s | 10Kh YouTube/podcasts |

Total: 139 MB on disk. Both use ORT format for ~3x faster model init vs ONNX.

### Key files

| File | Role |
|------|------|
| `recognition/SherpaTranscriber.kt` | Two-pass recording loop, `alignTimestamps()` LCS |
| `recognition/SherpaModelManager.kt` | Streaming model lifecycle |
| `recognition/OfflineModelManager.kt` | Offline model lifecycle |
| `recognition/SherpaTokenMerger.kt` | Streaming token → WordInfo merging |
| `recognition/ModelDownloader.kt` | Model download with cross-domain redirect handling |

## Verification

1. `./gradlew testDebugUnitTest` — SherpaTranscriberTest, SherpaTokenMergerOffsetTest pass
2. Record a sentence → streaming partial appears → endpoint fires → text upgrades in place
3. Tap words after upgrade → audio seeks correctly (timestamps from LCS alignment)
4. Multiple sentences in one recording → each upgrades independently at its endpoint
5. Stop recording mid-sentence → flush path runs offline on remaining audio

## Design decisions

1. **Per-endpoint vs whole-recording re-transcription.** Earlier plan called for re-transcribing the entire recording after stop(). Per-endpoint is better: the user sees improved text within ~1s of each sentence boundary, not after the entire session ends.

2. **LCS alignment vs linear interpolation.** Pure linear interpolation (dividing segment duration equally among words) produces noticeably wrong seek positions when word durations vary. LCS matching inherits accurate timestamps for words that appear in both outputs (~70-80% of words), with linear interpolation only for insertions.

3. **500ms overlap buffer.** The streaming endpoint detector can split words at segment boundaries. A 500ms overlap (8000 samples at 16kHz) gives the offline model context across the boundary, matching the pattern from sherpa-onnx's own two-pass example.

4. **GigaSpeech over Multidataset.** Both achieve ~12% WER on unseen data. GigaSpeech is smaller (68 MB vs 123 MB) and better on conference calls (6.7% vs 8.0% Earn-22), which is closer to the lecture/meeting use case.
