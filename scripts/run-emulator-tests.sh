#!/usr/bin/env bash
set -euo pipefail

# The emulator action stops the device as soon as this script returns.
trap 'timeout 10s adb logcat -d -t 5000 > emulator-logcat.txt || true' EXIT
adb shell wm size "$2"
adb shell wm density "$3"
adb shell settings put system font_scale "$4"
adb shell wm size
adb shell wm density
status=0
args=()
if [ -n "${5:-}" ]; then args+=("-Pandroid.testInstrumentationRunnerArguments.class=$5"); fi
bash gradlew connectedDebugAndroidTest --no-daemon "${args[@]}" || status=$?
preview=jotterknocker-preview.png
if [ -n "${5:-}" ]; then preview=jotterknocker-compact-preview.png; fi
timeout 15s adb pull "/data/local/tmp/$preview" "preview-api-$1.png" || {
    if [ "$status" -eq 0 ]; then status=1; fi
}
timeout 15s adb pull /data/local/tmp/jotterknocker-compact-preview.png "compact-preview-api-$1.png" || true
exit "$status"
