#!/bin/bash
# Compila Pera.app (Apple Silicon e Intel) con firma local "ad hoc". Requiere Xcode o sus herramientas.
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release --arch arm64 --arch x86_64
BIN="$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/Pera"

APP=build/Pera.app
rm -rf build
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/Pera"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"

(cd build && ditto -c -k --keepParent Pera.app Pera-mac.zip)
echo "Listo: $(pwd)/build/Pera.app y build/Pera-mac.zip"
