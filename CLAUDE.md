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
"/c/Users/Durham/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

### Tests

```bash
# Unit tests only (no device required)
./gradlew testDebugUnitTest

# All tests (unit + instrumented, requires connected device)
./gradlew allTests
```

## PR Workflow

Before creating a PR, run the self-review cycle locally:

1. **Review local changes**: `REVIEW=$(./scripts/review-pr.sh --local --no-post)` — reviews the branch diff against master, saves to `.claude/reviews/`.
2. **Read the review file** (`cat "$REVIEW"`) and address every actionable item by editing the code and committing.
3. **Verify fixes**: `./scripts/review-check.sh --local --no-post "$REVIEW"` — confirm all items are addressed.
4. If any items remain open, go back to step 2.
5. Once all items are resolved, **create the PR** and post the review:
   - `gh pr create --draft` (or `gh pr create` if ready)
   - `./scripts/review-pr.sh --post` (runs a fresh review and posts to the PR)
   - `./scripts/review-check.sh --post "$REVIEW"` (posts the resolution checklist)
   - `gh pr ready` (if created as draft)

The `--local` flag works without a remote or PR. The `--no-post` / `--post` flags make scripts non-interactive for agent use.
