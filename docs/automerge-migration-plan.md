# Migration to Automerge + SAF Export Decoupling

## Context

The current `.mok` ZIP format rewrites the entire document (protobuf + audio) on every 5-second auto-save, including to SAF cloud storage. This is expensive, especially with audio recordings. The target architecture:

- **Automerge** for live document state (incremental persistence, versioning, future multi-device sync)
- **Audio files** as write-once blobs on disk, referenced by content hash
- **.mok ZIP** retained as interchange/export format (backward compatible)
- **SAF export** decoupled from auto-save (on document close / app background only)

## Phase 1: Decouple SAF from Auto-Save (no format change)

**Goal:** Stop exporting to SAF on every 5s auto-save. Export only on document close, app background, or explicit action.

### 1.1 Add dirty flag to AutoSaver

**Modify:** `app/src/main/java/com/writer/ui/writing/AutoSaver.kt`

- Add `var syncDirty: Boolean = false` — set true when content changes, cleared after SAF export
- Add `fun exportIfDirty(snapshotProvider: () -> Snapshot?)` — checks `syncDirty` before exporting
- `schedule()` snapshots stop including `syncUri`/`markdown` — local save only

**Test:** `app/src/test/java/com/writer/ui/writing/AutoSaverTest.kt`
- `schedule_doesNotExport_whenSyncUriAbsent` — verify Sink.export() never called during auto-save
- `exportIfDirty_exports_whenDirty` — verify export fires when flag is true
- `exportIfDirty_skips_whenClean` — verify no export when flag is false
- `exportIfDirty_clearsDirtyFlag` — verify flag reset after export
- `syncDirty_setTrue_onSchedule` — verify flag set when save is scheduled

### 1.2 Move SAF export to lifecycle events

**Modify:** `app/src/main/java/com/writer/ui/writing/WritingActivity.kt`

- Auto-save snapshot provider: omit `syncUri` and `markdown`
- `onStop()`: call `autoSaver.exportIfDirty(...)` with sync snapshot
- `stopLectureCapture()`: call export after audio save
- Mark `syncDirty = true` in `onStrokeCompleted`, `insertTextBlock`, and other mutation points

**Test:** Verify via logcat that `Exported $name to sync folder` appears once on document close, not every 5s.

### 1.3 Modify DocumentStorageSink

**Modify:** `app/src/main/java/com/writer/storage/DocumentStorageSink.kt`

- `export()` must include audio files from the sidecar directory (current code passes `emptyMap()`)

**Test:** Export a document with audio to SAF folder, verify the .mok ZIP contains audio entries.

---

## Phase 2: Add Automerge Dependency and Adapter Layer

**Goal:** Add Automerge to the project and build a bidirectional adapter between `DocumentData` and Automerge documents, without changing any existing code paths.

### 2.1 Add automerge-java dependency

**Modify:** `app/build.gradle.kts`

```kotlin
implementation("org.automerge:automerge:0.2.0")  // verify latest version
```

**Test:** `./gradlew assembleDebug` — verify AAR includes arm64-v8a .so

### 2.2 Create AutomergeDocumentAdapter

**Create:** `app/src/main/java/com/writer/storage/AutomergeAdapter.kt`

Bidirectional conversion:
- `fun toAutomerge(data: DocumentData): org.automerge.Document`
- `fun fromAutomerge(doc: org.automerge.Document): DocumentData`

Automerge schema mapping:

```
Document root (synced content only)
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

Local-only state (NOT in Automerge — stored per device):
  scrollOffsetY, highestLineIndex, currentLineIndex, userRenamed

Derived state (rebuilt on load, not persisted in Automerge):
  lineTextCache (from handwriting recognition), everHiddenLines (UI display state)
```

**Schema evolution strategy:**

Automerge is schema-less (JSON-like CRDT). Safety comes from the adapter layer + tests, not a compiler:

- **Schema version marker**: `doc.put("_schemaVersion", N)` in the document root. Adapter checks version on load and applies forward migrations (v1→v2→v3).
- **Adding a field**: read with default in adapter (`doc.get("newField")?.asString() ?: "default"`). Old documents without the key get the default.
- **Removing a field**: stop reading the key. Old data preserves it harmlessly.
- **Forward compatibility**: Automerge automatically preserves unknown keys. Device on v2 won't delete fields added by device on v3 — they survive sync round-trips.
- **Canonical spec**: `document.proto` remains the source of truth for what fields exist and their types. The Automerge adapter is derived from this understanding. Add a field to proto → add to adapter → add golden test.
- **Golden files**: same pattern as existing `GoldenFileGenerator` — one golden Automerge binary per schema version, tested on every build. Old versions must always load.

**Test:** `app/src/test/java/com/writer/storage/AutomergeAdapterTest.kt`
- `roundTrip_emptyDocument` — empty DocumentData → Automerge → DocumentData, assert equal
- `roundTrip_withStrokes` — document with strokes round-trips exactly
- `roundTrip_withTextBlocks` — text blocks with WordInfo round-trip
- `roundTrip_withDiagramAreas` — diagram areas round-trip
- `roundTrip_withAudioRecordings` — audio recordings round-trip
- `roundTrip_fullDocument` — sampleData() with all fields round-trips
- `roundTrip_preservesStrokePointPrecision` — float coordinates survive
- `incrementalEdit_addsStroke` — add stroke to Automerge doc, convert back, verify

### 2.3 Golden file compatibility

**Create:** `app/src/test/java/com/writer/storage/AutomergeGoldenTest.kt`

Two categories of golden tests:

**Protobuf→Automerge migration golden tests:**
- Load each existing golden `.inkup` file → `DocumentData` → Automerge → `DocumentData` → assert matches original
- Ensures the Automerge adapter handles all historical protobuf schema versions

**Automerge-native golden files:**
- **Create:** `app/src/test/java/com/writer/storage/AutomergeGoldenFileGenerator.kt`
- Same pattern as existing `GoldenFileGenerator.kt`: one builder per schema version
- Run: `./gradlew testDebugUnitTest --tests "*.AutomergeGoldenFileGeneratorRunner" -PgoldenVersion=v1`
- Generates an Automerge binary snapshot saved to `app/src/test/resources/automerge-golden/`
- **Never modify or delete existing golden files** — same rule as protobuf goldens
- On schema version bump: add a new builder with the new fields, generate a new golden file, add a test that loads all previous versions

**Golden test cases:**
- `goldenV1_loadsCorrectly` — first Automerge schema version loads with all fields
- `goldenV1_migratesForward` — v1 doc gets `_schemaVersion` bumped and new fields defaulted
- `allProtobufGoldens_roundTripThroughAutomerge` — every historical `.inkup` golden survives the conversion

---

## Phase 3: Automerge Persistence Layer

**Goal:** Replace ZIP-based persistence with Automerge's native save/load for the internal storage path. ZIP export stays for SAF.

### 3.1 Create AutomergeStorage

**Create:** `app/src/main/java/com/writer/storage/AutomergeStorage.kt`

```kotlin
class AutomergeStorage(private val docsDir: File) {
    fun save(name: String, doc: org.automerge.Document)  // doc.save() → bytes → file
    fun load(name: String): org.automerge.Document?      // bytes → Document.load()
    fun saveIncremental(name: String, doc: org.automerge.Document)  // doc.saveIncremental() → append
    fun exists(name: String): Boolean
    fun delete(name: String)
    fun list(): List<String>
}
```

File format: `.automerge` — raw Automerge binary, not ZIP.

`saveIncremental()` uses Automerge's built-in incremental save — only writes changes since last save. This is the auto-save path.

**Test:** `app/src/test/java/com/writer/storage/AutomergeStorageTest.kt`
- `save_load_roundTrips` — save doc, load back, assert equal
- `saveIncremental_accumulatesChanges` — save, make edits, saveIncremental, load, verify all edits present
- `saveIncremental_afterFullSave_producesSmallFile` — verify incremental save is much smaller than full
- `delete_removesFile`
- `list_returnsDocumentNames`

### 3.2 Create AutomergeSink (AutoSaver adapter)

**Create:** `app/src/main/java/com/writer/storage/AutomergeSink.kt`

Implements `AutoSaver.Sink`:
- `save()`: convert `DocumentData` → update Automerge doc → `saveIncremental()`
- `export()`: convert → full Automerge save → ZIP export via `DocumentBundle.writeZip()` for SAF

Holds a reference to the live `org.automerge.Document` for the open document.

**Test:** `app/src/test/java/com/writer/storage/AutomergeSinkTest.kt`
- `save_writesIncrementalFile` — verify incremental, not full rewrite
- `save_multipleTimes_accumulates` — 10 saves, load, all data present
- `export_producesValidMokZip` — verify exported ZIP is readable by `DocumentBundle.readZip()`

### 3.3 Wire AutomergeSink into WritingActivity

**Modify:** `app/src/main/java/com/writer/ui/writing/WritingActivity.kt`

- Replace `DocumentStorageSink` with `AutomergeSink` as the `AutoSaver.Sink`
- On document open: load `.automerge` file (or migrate from `.mok` ZIP on first open)
- On document close: full save + SAF export if dirty

**Modify:** `app/src/main/java/com/writer/storage/DocumentStorage.kt`

- Add migration method: `migrateToAutomerge(context, name)` — reads .mok ZIP, converts to Automerge, saves as .automerge

### 3.4 Migration logic

On document open:
1. Check for `.automerge` file → load Automerge doc
2. If not found, check for `.mok` file → load ZIP → convert to Automerge → save as `.automerge`
3. If neither found, create new Automerge doc

**Test:** `app/src/test/java/com/writer/storage/MigrationTest.kt`
- `migrate_mokToAmok_preservesAllData` — roundtrip via migration, compare DocumentData
- `migrate_mokWithAudio_preservesAudioFiles` — audio sidecar created from ZIP
- `migrate_legacyInkup_works` — oldest format migrates correctly
- `openDocument_preferAmok_overMok` — .automerge takes priority
- `openDocument_migratesOnFirstOpen` — .mok → .automerge created

---

## Phase 4: Audio as Content-Addressed Blobs

**Goal:** Store audio files by content hash, referenced from TextBlocks. Deduplication and sync-friendliness.

### 4.1 AudioBlobStore

**Create:** `app/src/main/java/com/writer/storage/AudioBlobStore.kt`

```kotlin
class AudioBlobStore(private val blobDir: File) {
    fun store(bytes: ByteArray): String  // returns SHA-256 hash, writes to blobDir/<hash>.webm
    fun load(hash: String): ByteArray?
    fun exists(hash: String): Boolean
    fun delete(hash: String)
    fun garbageCollect(referencedHashes: Set<String>)  // remove unreferenced blobs
}
```

**Test:** `app/src/test/java/com/writer/storage/AudioBlobStoreTest.kt`
- `store_returnsConsistentHash` — same bytes → same hash
- `store_load_roundTrips`
- `store_deduplicates` — storing same bytes twice doesn't create two files
- `garbageCollect_removesUnreferenced`
- `garbageCollect_keepsReferenced`

### 4.2 Update TextBlock audio references

**Modify:** `app/src/main/java/com/writer/model/TextBlock.kt`

- `audioFile` field now stores a content hash instead of a filename
- Backward compatible: old filenames still work (detected by format — hashes are 64 hex chars)

### 4.3 Update save/export to use AudioBlobStore

- Internal save: audio bytes → `AudioBlobStore.store()` → hash in TextBlock
- SAF export: read blobs by hash → pack into ZIP with original filenames
- SAF import: extract audio from ZIP → store in AudioBlobStore → update hashes

---

## Phase 5: Version History / Checkpointing

**Goal:** Let users navigate back to earlier versions of a document.

### 5.1 Automerge heads as checkpoints

Automerge tracks all changes as a DAG. Every save point has a set of "heads" (hashes of the latest changes). Saving the heads at known points = checkpoints.

**Create:** `app/src/main/java/com/writer/storage/VersionHistory.kt`

```kotlin
data class Checkpoint(val label: String, val timestamp: Long, val heads: List<ByteArray>)

class VersionHistory(private val storage: AutomergeStorage) {
    fun createCheckpoint(doc: Document, label: String): Checkpoint
    fun listCheckpoints(name: String): List<Checkpoint>
    fun restoreCheckpoint(doc: Document, checkpoint: Checkpoint): Document
}
```

**Test:** `app/src/test/java/com/writer/storage/VersionHistoryTest.kt`
- `createAndRestore_returnsOriginalState`
- `multipleCheckpoints_eachRestoresToCorrectState`
- `checkpoint_afterStrokeAdd_restoresWithoutStroke`

---

## Execution Order

| Order | Phase | Dependencies | Can ship independently |
|-------|-------|-------------|----------------------|
| 1 | Phase 1: SAF decoupling | None | Yes — immediate perf win |
| 2 | Phase 2.1: Add Automerge dep | None | Yes — no behavior change |
| 3 | Phase 2.2-2.3: Adapter + golden tests | Phase 2.1 | Yes — adapter is unused code |
| 4 | Phase 3: Automerge persistence | Phase 2 | Yes — replaces internal format |
| 5 | Phase 4: Audio blob store | Phase 3 | Yes — replaces sidecar |
| 6 | Phase 5: Version history | Phase 3 | Yes — additive feature |

Each phase has its own automated tests. No phase depends on device testing — all tests are unit tests using temp files and in-memory Automerge documents.

## Key Files

| File | Phase | Action |
|------|-------|--------|
| `ui/writing/AutoSaver.kt` | 1 | Modify (dirty flag, exportIfDirty) |
| `ui/writing/WritingActivity.kt` | 1, 3 | Modify (SAF timing, sink swap) |
| `storage/DocumentStorageSink.kt` | 1 | Modify (include audio in export) |
| `app/build.gradle.kts` | 2 | Modify (add automerge dep) |
| `storage/AutomergeAdapter.kt` | 2 | Create |
| `storage/AutomergeStorage.kt` | 3 | Create |
| `storage/AutomergeSink.kt` | 3 | Create |
| `storage/AudioBlobStore.kt` | 4 | Create |
| `storage/VersionHistory.kt` | 5 | Create |
| `storage/DocumentStorage.kt` | 3 | Modify (migration) |
| `storage/DocumentBundle.kt` | — | Unchanged (export format) |
| `proto/document.proto` | — | Unchanged (wire format) |

## Verification

Each phase verified by automated tests before proceeding to the next:
- Phase 1: `AutoSaverTest` — dirty flag, export timing
- Phase 2: `AutomergeAdapterTest` + `AutomergeGoldenTest` — round-trip fidelity
- Phase 3: `AutomergeStorageTest` + `AutomergeSinkTest` + `MigrationTest` — persistence + migration
- Phase 4: `AudioBlobStoreTest` — content-addressed storage
- Phase 5: `VersionHistoryTest` — checkpoint/restore

No phase requires a connected device. All tests run via `./gradlew testDebugUnitTest`.
