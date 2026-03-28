# Writer - Android Handwriting-to-Text App

## Environment

- **JAVA_HOME**: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` — must be set before running Gradle commands
- **ADB path**: `/c/Users/Durham/AppData/Local/Android/Sdk/platform-tools/adb.exe` — not on PATH, use full path

### Build

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

### Install to tablet

```bash
./gradlew installDebug
```

### Tests

```bash
# Unit tests only (no device required)
./gradlew testDebugUnitTest

# All tests (unit + instrumented, requires connected device)
./gradlew allTests
```

## Development Workflow

Before starting any implementation, review [`docs/VISION.md`](docs/VISION.md) to ensure the new feature or UX aligns with the overall product vision and design principles.

Break implementation tasks into small, independently testable pieces. For each piece, follow this cycle:

1. **Write tests first**: Create unit tests that define the expected behavior before writing any implementation code. Run them to confirm they fail for the right reasons.
2. **Implement**: Write the minimum code to make the tests pass.
3. **Run all tests**: `./gradlew testDebugUnitTest` — ensure no regressions.
4. **Commit**: Commit the piece to the feature/fix/dev branch.

Repeat steps 1-4 for each piece. After all pieces are complete:

5. **Local review**: `REVIEW=$(./scripts/review-pr.sh --local --no-post)` — review the full diff and address all actionable items.
6. **Final test run**: `./gradlew testDebugUnitTest` — confirm everything still passes after review fixes.
7. **Push**: Push to both local and remote feature/fix/dev branch.

## Device Debugging

Bug reports are saved to `tmp/` (gitignored). When debugging a device issue:

1. **Capture a screenshot**: Use the Android MCP tool `get_screenshot` — returns the image inline, no file needed.
2. **Generate a bug report** on the device: hamburger menu → Bug Report
3. **Pull the bug report**: `./gradlew bugReport` — pulls the latest report to `tmp/`
4. **Read the screenshot and bug report** to understand device state before making changes

Bug reports contain device info, recent stroke history, processing events, and document state.

## PR Workflow

Before creating a PR, run the self-review cycle locally:

1. **Review local changes**: `REVIEW=$(./scripts/review-pr.sh --local --no-post)` — reviews the branch diff against master (use `--base <branch>` to diff against a different branch), saves to `.claude/reviews/`.
2. **Read the review file** (`cat "$REVIEW"`) and address every actionable item by editing the code and committing.
3. **Verify fixes**: `./scripts/review-check.sh --local --no-post "$REVIEW"` — confirm all items are addressed.
4. If any items remain open, go back to step 2.
5. Once all items are resolved, **create the PR** and post the review:
   - `gh pr create --draft` (or `gh pr create` if ready)
   - `./scripts/review-pr.sh --post` (runs a fresh review and posts to the PR)
   - `./scripts/review-check.sh --post "$REVIEW"` (posts the resolution checklist)
   - `gh pr ready` (if created as draft)

The `--local` flag works without a remote or PR. The `--no-post` / `--post` flags make scripts non-interactive for agent use.

## Document Format Safety (Proto Schema)

The document format is defined in `app/src/main/proto/com/writer/model/proto/document.proto` and serialized with [Wire](https://github.com/square/wire). Documents are stored as binary protobuf `.inkup` files. Legacy `.json` files are still loadable via fallback.

Follow these rules when changing the document schema:

1. **Never reuse a field number.** Reserve removed field numbers with `reserved`.
2. **All fields must be `optional` with defaults** (never use `required`). This ensures any field can be safely removed in the future.
3. **Add a golden file** for each schema version: add a builder in `GoldenFileGenerator.kt`, then run `./gradlew testDebugUnitTest --tests "com.writer.storage.GoldenFileGeneratorRunner" -PgoldenVersion=vN`.
4. **Never modify or delete existing golden files.** They are the permanent backward-compatibility contract.
5. **Run `./gradlew testDebugUnitTest`** — golden file tests must pass for all historical versions.
