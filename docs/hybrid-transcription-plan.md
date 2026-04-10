# Hybrid Two-Pass Transcription: Streaming Partials → Offline Re-transcription

## Context

Streaming Sherpa gives 11.2% WER with real-time partials. Offline Sherpa gives 0.5% WER at 0.06x RTF. The hybrid approach: show live partials during recording (streaming), then automatically re-transcribe with the offline model after stop() for near-perfect accuracy. The user sees text "upgrade" within ~1 second of stopping.

## Architecture

```
Recording:    Streaming Sherpa → live italic partials → committed TextBlocks (11% WER)
                                                           ↓
After stop(): Read OGG audio → decode to PCM → Offline Sherpa → replace TextBlocks (0.5% WER)
```

## Implementation pieces

### 1. OfflineTranscriptionManager — model lifecycle + re-transcription

**Create:** `app/src/main/java/com/writer/recognition/OfflineTranscriptionManager.kt`

Manages the offline model (180MB, ~9s load) and provides a re-transcription API.

```kotlin
class OfflineTranscriptionManager {
    enum class State { UNLOADED, LOADING, READY, TRANSCRIBING }
    
    fun preload(context: Context)  // async download + load model
    fun retranscribe(
        audioBytes: ByteArray,
        sampleRate: Int,
        onResult: (text: String, words: List<WordInfo>) -> Unit,
        onProgress: ((Float) -> Unit)? = null
    )
    fun release()
}
```

Flow:
1. Decode OGG bytes → float[] PCM via MediaExtractor + MediaCodec (same pattern as `decodeFlacToPcm` in `TranscriptionBenchmarkTest`)
2. Feed entire PCM to `OfflineRecognizer.createStream().acceptWaveform(pcm, sampleRate)`
3. `recognizer.decode(stream)` — synchronous, blocks until done
4. `recognizer.getResult(stream)` → text, tokens, timestamps
5. `SherpaTokenMerger.mergeTokens(tokens, timestamps)` → List<WordInfo>
6. Invoke callback with improved text + words

**Model:** `sherpa-onnx-zipformer-en-2023-04-01` int8, converted to ORT format for faster loading.
**Model cache:** `context.filesDir/sherpa_offline_models/`

**ORT conversion:** No pre-converted ORT model exists for the offline zipformer. Convert locally:
```bash
pip install onnxruntime
python -m onnxruntime.tools.convert_onnx_models_to_ort --optimization_style=Fixed \
  encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx
```
Host the resulting `.ort` files (same repo or new HuggingFace repo). Expected load time reduction: ~8.9s → ~3s (based on streaming ORT results: 2.8x speedup).

**Test:** `app/src/test/java/com/writer/recognition/OfflineTranscriptionManagerTest.kt`
- State machine tests (same pattern as SherpaModelManagerTest)
- Can't test actual transcription without device (model is JNI)

### 2. OGG-to-PCM decoder

**Create:** `app/src/main/java/com/writer/audio/OggDecoder.kt`

```kotlin
object OggDecoder {
    /** Decode OGG/Opus bytes to 16kHz mono float32 PCM. */
    fun decode(oggBytes: ByteArray, sampleRate: Int = 16000): FloatArray
}
```

Uses `MediaExtractor` + `MediaCodec` — same pattern as `decodeFlacToPcm()` in the benchmark test (lines 147-195). Write the OGG bytes to a temp file, set as MediaExtractor data source, decode to 16-bit PCM, convert to float32.

**Test:** `app/src/androidTest/java/com/writer/audio/OggDecoderTest.kt` (instrumented — needs MediaCodec)
- Encode silence via AudioRecordCapture → decode via OggDecoder → verify output is float[] of correct length
- Round-trip: encode known signal → decode → verify samples match

### 3. TextBlock replacement in WritingCoordinator

**Modify:** `app/src/main/java/com/writer/ui/writing/WritingCoordinator.kt`

Add method to replace text blocks by audio file:

```kotlin
fun replaceTextBlocksForRecording(
    audioFile: String,
    newText: String,
    newWords: List<WordInfo>
) {
    val indices = columnModel.textBlocks.indices
        .filter { columnModel.textBlocks[it].audioFile == audioFile }
    if (indices.isEmpty()) return
    
    // Replace all blocks for this recording with a single improved block
    // Keep the first block's position, remove the rest
    val first = columnModel.textBlocks[indices.first()]
    val improved = first.copy(
        text = newText,
        heightInLines = computeHeightInLines(newText),
        words = newWords,
        audioStartMs = newWords.firstOrNull()?.startMs ?: 0,
        audioEndMs = newWords.lastOrNull()?.endMs ?: 0
    )
    
    // Remove old blocks (reverse order to preserve indices)
    for (i in indices.reversed()) columnModel.textBlocks.removeAt(i)
    // Insert improved block at the original position
    columnModel.textBlocks.add(indices.first(), improved)
    
    inkCanvas.textBlocks = columnModel.textBlocks.toList()
    inkCanvas.drawToSurface()
}
```

**Test:** `app/src/test/java/com/writer/ui/writing/TextBlockReplacementTest.kt`
- Replace single block: text and words updated, position preserved
- Replace multiple blocks from same recording: merged into one improved block
- Replace preserves blocks from other recordings

### 4. Wire into WritingActivity stopLectureCapture

**Modify:** `app/src/main/java/com/writer/ui/writing/WritingActivity.kt`

After `transcriber?.stop()` and `readRecordedBytes()`, trigger offline re-transcription:

```kotlin
// In stopLectureCapture(), after audioBytes is obtained:
if (audioBytes != null) {
    // Save immediately with streaming results (user sees them right away)
    val recordingName = "rec-${lectureRecordingStartMs}.ogg"
    val snapshot = createSaveSnapshot()
    if (snapshot != null) {
        DocumentStorage.save(this, snapshot.name, snapshot.state, mapOf(recordingName to audioBytes))
    }
    
    // Re-transcribe in background with offline model for improved accuracy
    offlineManager.retranscribe(audioBytes, 16000,
        onResult = { text, words ->
            runOnUiThread {
                activeCoordinator?.replaceTextBlocksForRecording(recordingName, text, words)
                snapshotAndSaveBlocking() // save improved version
                Toast.makeText(this, "Transcription improved", Toast.LENGTH_SHORT).show()
            }
        },
        onProgress = { progress ->
            // Optional: show progress in the overlay
        }
    )
}
```

### 5. Model lifecycle

**Modify:** `app/src/main/java/com/writer/ui/writing/WritingActivity.kt`

- `offlineManager` field (lazy, application-scoped)
- Do NOT preload on `onResume()` — offline model is 180MB, only load on demand
- Load on first `retranscribe()` call (inside OfflineTranscriptionManager)
- Release on `onStop()` (same as streaming model)
- If the user starts a new recording before offline finishes, cancel the re-transcription

### 6. UX feedback

During offline re-transcription (~0.6s per 10s of audio):
- Show the playback overlay or a toast: "Improving transcription..."
- When done: briefly flash or highlight the updated text blocks
- If the text didn't change (already correct), don't disturb the user

## Key files

| File | Action |
|------|--------|
| `recognition/OfflineTranscriptionManager.kt` | Create |
| `audio/OggDecoder.kt` | Create |
| `ui/writing/WritingCoordinator.kt` | Modify (add replaceTextBlocksForRecording) |
| `ui/writing/WritingActivity.kt` | Modify (wire offline re-transcription after stop) |
| `recognition/SherpaTokenMerger.kt` | Reuse (merge offline tokens → WordInfo) |
| `perf/TranscriptionBenchmarkTest.kt` | Reference (offline API pattern, OGG decoding) |

## Execution order

| Step | Piece | Dependencies |
|------|-------|-------------|
| 1 | OggDecoder | None |
| 2 | OfflineTranscriptionManager | OggDecoder |
| 3 | TextBlock replacement | None |
| 4 | WritingActivity wiring | Pieces 1-3 |
| 5 | Model lifecycle | Piece 2 |

## Verification

1. `./gradlew testDebugUnitTest` — state machine + replacement tests pass
2. Record a memo → streaming text appears → stop → "Improving transcription..." → text updates within ~2s
3. Tap words in improved text → audio seeks correctly (timestamps from offline model)
4. Multiple recordings in same document → only the latest recording's blocks are replaced
5. Start new recording while offline is running → offline result discarded (no stale replacement)
6. Background the app during offline → model released, no crash

## Open questions

1. **Should we keep the streaming text blocks or replace them?** Plan assumes replace. Alternative: keep streaming blocks as "draft" and add improved blocks below, letting the user compare. Simpler to replace.

2. **What if offline WER is worse on some utterances?** Unlikely given the benchmarks (0.5% vs 11.2%), but possible for edge cases. Could compare and keep whichever is better per-block — but adds complexity. Start with always replacing.

3. **Should offline re-transcription be optional?** Could add a setting. For now, always run when using Sherpa engine — the 0.6s/10s cost is negligible.
