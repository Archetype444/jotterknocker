#!/usr/bin/env bash
set -euo pipefail

# The emulator action stops the device as soon as this script returns.
trap 'timeout 10s adb logcat -d -t 5000 > emulator-logcat.txt || true' EXIT
adb shell wm size "$2"
adb shell wm density "$3"
adb shell settings put system font_scale "$4"
bash gradlew connectedDebugAndroidTest --no-daemon
adb exec-out run-as app.scratchpad.launcher.debug cat files/jotterknocker-preview.png > "preview-api-$1.png"
test -s "preview-api-$1.png"
