#!/usr/bin/env bash
# NuvioMate Dev: builds the debug app (com.nuviomate.tv.debug, "NuvioMate Dev", DEV icon) and installs it
# next to NuvioMate, with its own data. It doesn't touch the installed NuvioMate and doesn't launch.
#
# Usage (from the repository root):
#   scripts/nuviomate_dev.sh                  build, then install on the TV (10.0.0.68:5555)
#   scripts/nuviomate_dev.sh emulator-5554    build, then install on that device
#   scripts/nuviomate_dev.sh --no-install     build only
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT_PARENT="$(cd .. && pwd)"
DEVICE="${1:-10.0.0.68:5555}"

export JAVA_HOME="${JAVA_HOME_NUVIOMATE:-$ROOT_PARENT/jdk17/jdk-17.0.21+9/Contents/Home}"
export NUVIO_GO="${NUVIO_GO:-$ROOT_PARENT/go-sdk/go/bin/go}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

./gradlew :app:assembleFullDebug -x lintVitalFullDebug

[ "$DEVICE" = "--no-install" ] && exit 0

case "$DEVICE" in *:*) "$ADB" connect "$DEVICE" >/dev/null ;; esac
ABI=$("$ADB" -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')
APK="app/build/outputs/apk/full/debug/app-full-$ABI-debug.apk"
[ -f "$APK" ] || APK="app/build/outputs/apk/full/debug/app-full-universal-debug.apk"
echo "Installing $(basename "$APK") on $DEVICE ($ABI)"
"$ADB" -s "$DEVICE" install -r "$APK"
