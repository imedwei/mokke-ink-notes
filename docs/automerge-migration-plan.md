# Migration to Automerge + SAF Export Decoupling

## Context

The current `.mok` ZIP format rewrites the entire document (protobuf + audio) on every 5-second auto-save, including to SAF cloud storage. This is expensive, especially with audio recordings. The target architecture:

- **Automerge** for live document state (incremental persistence, versioning, future multi-device sync)
- **Audio files** in sidecar directory on disk, referenced by filename (content-addressed dedup deferred to sync phase)
- **.mok ZIP** retained as interchange/export format and local-state persistence (backward compatible)
- **SAF export** decoupled from auto-save (on document close / app background only)

## Status

All phases are implemented and tested (1025 unit tests, 0 failures).

| Phase | Status | Key outcome |
|-------|--------|-------------|
| 1. SAF decoupling | **Done** | SAF export only on close, not every 5s |
| 2. Automerge adapter | **Done** | Bidirectional DocumentData ↔ Automerge conversion |
| 3. Automerge persistence | **Done** | Auto-save writes `.automerge` binary, no ZIP/audio re-read |
| 4. AudioBlobStore | **Done** | Content-addressed store built, wiring deferred to sync phase |
| 5. Version history | **Done** | Checkpoint/restore via Automerge heads |

---

## Phase 1: Decouple SAF from Auto-Save ✓

**Goal:** Stop exporting to SAF on every 5s auto-save. Export only on document close, app background, or explicit action.

### 1.1 Dirty flag and exportIfDirty ✓

**Modified:** `AutoSaver.kt`

- `var syncDirty: Boolean` — set true on `schedule()`, cleared after SAF export
- `fun exportIfDirty(snapshotProvider)` — exports only when dirty
- `performSave()` — local save only, no SAF export
- Injected `ioDispatcher` for reliable test scheduling

### 1.2 SAF export on lifecycle events ✓

**Modified:** `WritingActivity.kt`

- `createSaveSnapshot()` — omits `syncUri`/`markdown` (local save only)
- `createExportSnapshot()` — includes sync folder info for SAF export
- `onStop()` — calls `exportIfDirty` after save
- `stopLectureCapture()` — calls `exportIfDirty` after audio save

### 1.3 DocumentStorageSink includes audio ✓

**Modified:** `DocumentStorageSink.kt`, `DocumentStorage.kt`

- `export()` reads audio from sidecar directory via `getAudioFiles()`
- Exported `.mok` ZIPs now contain audio entries

---

## Phase 2: Automerge Dependency and Adapter Layer ✓

**Goal:** Add Automerge and build a bidirectional adapter between `DocumentData` and Automerge documents.

### 2.1 automerge-java dependency ✓

**Modified:** `app/build.gradle.kts`

```kotlin
implementation("org.automerge:automerge:0.0.8")
```

Desktop natives excluded from APK packaging (only arm64-v8a .so needed).

### 2.2 AutomergeAdapter ✓

**Created:** `AutomergeAdapter.kt`

Bidirectional conversion using `listItems()` for reliable list reads (the `get(ObjectId, int)` API in 0.0.7 was buggy — fixed in 0.0.8 to `get(ObjectId, long)` but `listItems()` is simpler).

Automerge schema (v1):

```
Document root
├── _schemaVersion: 1
├── main (Map)
│   ├── strokes (List of Maps)
│   │   └── { strokeId, strokeWidth, strokeType, isGeometric,
│   │         points: List of { x, y, pressure, timestamp } }
│   ├── textBlocks (List of Maps)
│   │   └── { id, startLineIndex, heightInLines, text, audioFile,
│   │         audioStartMs, audioEndMs, words: List of { text, confidence, startMs, endMs } }
│   └── diagramAreas (List of Maps)
│       └── { id, startLineIndex, heightInLines }
├── cue (Map) — same structure as main
└── audioRecordings (List of Maps)
    └── { audioFile, startTimeMs, durationMs }

Local-only state (NOT in Automerge — persisted to .mok on close):
  scrollOffsetY, highestLineIndex, currentLineIndex, userRenamed

Derived state (rebuilt on load, not persisted):
  lineTextCache, everHiddenLines
```

**Tests:** `AutomergeAdapterTest.kt` — 10 round-trip tests covering empty docs, strokes, text blocks, diagram areas, audio recordings, cue column, all stroke types, float precision, incremental edits.

### 2.3 Golden file compatibility ✓

**Created:** `AutomergeGoldenFileGenerator.kt`, `AutomergeGoldenTest.kt`

- v1 golden binary at `app/src/test/resources/golden/automerge/document_v1.automerge`
- `goldenV1_loadsCorrectly` — all fields verified
- `goldenV1_roundTrips` — encode → decode → encode → decode matches
- `allProtobufGoldens_roundTripThroughAutomerge` — all 5 historical `.inkup` versions survive conversion

---

## Phase 3: Automerge Persistence Layer ✓

**Goal:** Replace ZIP-based persistence with Automerge binary for auto-save. ZIP stays for SAF export and local-state persistence.

### 3.1 AutomergeStorage ✓

**Created:** `AutomergeStorage.kt`

- `.automerge` files — raw Automerge binary
- `.automerge.inc` files — incremental changes appended between full saves
- Tracks `lastSavedHeads` per document for `encodeChangesSince()` deltas

### 3.2 AutomergeSink ✓

**Created:** `AutomergeSink.kt`

- `save()` — converts DocumentData → Automerge doc → `storage.save()` (full save, no ZIP)
- `export()` — delegates to `DocumentStorageSink` for ZIP interchange

Each auto-save creates a fresh Automerge document from the full DocumentData snapshot. True incremental Automerge edits (mutating a live document) requires deeper integration with the coordinator/model layer — deferred to sync phase.

### 3.3 WritingActivity wiring ✓

**Modified:** `WritingActivity.kt`

- Auto-save uses `AutomergeSink` (no ZIP, no audio re-read)
- `loadDocument()` tries `.automerge` first, falls back to `.mok` with auto-migration
- Local-only state merged from `.mok` on load
- `onStop()` writes `.mok` once for local-state persistence + SAF export if dirty

### 3.4 Migration ✓

**Modified:** `DocumentStorage.kt`

- `migrateToAutomerge(mokFile, amStorage, name)` — reads `.mok` ZIP → Automerge → `.automerge`
- Audio sidecar left in place (not deleted by migration)

---

## Phase 4: Audio Storage ✓

**Goal:** Prepare content-addressed audio infrastructure for future sync/dedup.

### 4.1 AudioBlobStore ✓

**Created:** `AudioBlobStore.kt`

```kotlin
class AudioBlobStore(private val blobDir: File) {
    fun store(bytes: ByteArray): String  // SHA-256 hash, deduplicates
    fun load(hash: String): ByteArray?
    fun exists(hash: String): Boolean
    fun delete(hash: String)
    fun toFile(hash: String): File?      // resolve hash → file for playback
    fun garbageCollect(referencedHashes: Set<String>)

    companion object {
        fun isContentHash(ref: String): Boolean  // detect 64-char hex vs filename
    }
}
```

### 4.2–4.3 Wiring deferred

Audio files continue to use filename-based references (`rec-{timestamp}.ogg`) in TextBlock and AudioRecording. The AudioBlobStore is built and tested but not wired into save/load/playback paths.

**Rationale:** The main performance win (no audio re-read on auto-save) is already achieved by the Automerge persistence layer. Content-addressed audio adds dedup and sync-friendly identifiers, but replacing filenames with hashes in TextBlock leaks a storage concern into the data model. When multi-device sync arrives, a filename→hash manifest in the storage layer is the cleaner approach — TextBlock.audioFile stays as a stable identifier.

---

## Phase 5: Version History / Checkpointing ✓

**Goal:** Let users navigate back to earlier versions of a document.

### 5.1 Automerge heads as checkpoints ✓

**Created:** `VersionHistory.kt`

```kotlin
data class Checkpoint(val label: String, val timestamp: Long, val heads: Array<ChangeHash>)

class VersionHistory {
    fun createCheckpoint(doc: Document, label: String): Checkpoint
    fun listCheckpoints(): List<Checkpoint>
    fun restoreCheckpoint(doc: Document, checkpoint: Checkpoint): Document  // fork at heads
}
```

UI for browsing/restoring checkpoints is not yet implemented.

---

## Architecture After Migration

```
Auto-save (every 5s):
  WritingActivity → AutoSaver → AutomergeSink → AutomergeStorage
  Writes: .automerge binary (strokes, text, diagrams — no audio)

Document close (onStop):
  1. AutomergeSink → .automerge (content)
  2. DocumentStorage.save() → .mok ZIP (local-only state: scroll, lineIndex)
  3. exportIfDirty → DocumentStorageSink → .mok ZIP to SAF (if sync folder configured)

Document open:
  1. Try .automerge → AutomergeAdapter.fromAutomerge()
  2. Merge local-only state from .mok (scrollOffsetY, lineIndex, etc.)
  3. If no .automerge: load .mok → auto-migrate → save .automerge

Audio:
  Recorded → sidecar directory (.audio-{docname}/)
  Referenced by filename in TextBlock.audioFile ("rec-{timestamp}.ogg")
  Packed into .mok ZIP only during SAF export / document close
```

## Key Files

| File | Phase | Action |
|------|-------|--------|
| `ui/writing/AutoSaver.kt` | 1 | Modified (dirty flag, exportIfDirty, injected dispatcher) |
| `ui/writing/WritingActivity.kt` | 1, 3 | Modified (SAF timing, AutomergeSink, loadDocument) |
| `storage/DocumentStorageSink.kt` | 1 | Modified (include audio in export) |
| `app/build.gradle.kts` | 2 | Modified (automerge 0.0.8, exclude desktop natives) |
| `storage/AutomergeAdapter.kt` | 2 | Created |
| `storage/AutomergeStorage.kt` | 3 | Created |
| `storage/AutomergeSink.kt` | 3 | Created |
| `storage/AudioBlobStore.kt` | 4 | Created (not yet wired) |
| `storage/VersionHistory.kt` | 5 | Created (no UI yet) |
| `storage/DocumentStorage.kt` | 3 | Modified (migrateToAutomerge, getAudioFiles) |
| `storage/DocumentBundle.kt` | — | Unchanged (export format) |
| `proto/document.proto` | — | Unchanged (wire format) |

## Verification

All phases verified by automated tests (1025 total, 0 failures):

- Phase 1: `AutoSaverTest` (11 tests) — dirty flag, export timing, no-export on save
- Phase 2: `AutomergeAdapterTest` (10) + `AutomergeGoldenTest` (3) — round-trip fidelity
- Phase 3: `AutomergeStorageTest` (5) + `AutomergeSinkTest` (3) + `MigrationTest` (5) — persistence + migration
- Phase 4: `AudioBlobStoreTest` (8) — content-addressed storage + helpers
- Phase 5: `VersionHistoryTest` (4) — checkpoint/restore

No phase requires a connected device. All tests run via `./gradlew testDebugUnitTest`.

## Future Work

- **True incremental Automerge saves**: Mutate a live Automerge document directly instead of converting full DocumentData snapshots. Requires tighter integration with WritingCoordinator.
- **Multi-device sync**: Automerge merge/fork across devices. AudioBlobStore wired with filename→hash manifest for dedup.
- **Version history UI**: Browse and restore checkpoints from the document menu.
- **Checkpoint persistence**: Store checkpoints to disk (currently in-memory only).
