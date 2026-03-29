#!/bin/bash
# macOS launcher for .inkup files — standalone alternative to the .app bundle.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VIEWER="$SCRIPT_DIR/build/install/inkup-viewer/bin/inkup-viewer"

if [ ! -f "$VIEWER" ]; then
    echo "InkUp Viewer not built. Run: ./gradlew :tools:inkup-viewer:installDist" >&2
    exit 1
fi

for f in "$@"; do
    "$VIEWER" "$f"
done
