# Writer - Android Handwriting-to-Text App

## Environment

- **JAVA_HOME**: Set via `.envrc` (requires [direnv](https://direnv.net/) or `source .envrc`)
- **ADB path**: `/c/Users/Durham/AppData/Local/Android/Sdk/platform-tools/adb.exe` — not on PATH, use full path

### Build

```bash
./gradlew assembleDebug
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
