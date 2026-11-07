#!/usr/bin/env bash
set -euo pipefail
trap 'timeout 10s adb logcat -d -t 6000 > emulator-logcat.txt || true' EXIT
adb shell wm size 480x800
adb shell wm density 160
adb shell settings put system font_scale 1.0
adb install -r fossify-calendar.apk
adb shell pm grant org.fossify.calendar android.permission.READ_CALENDAR
adb shell pm grant org.fossify.calendar android.permission.WRITE_CALENDAR
adb shell pm grant org.fossify.calendar android.permission.READ_CONTACTS
adb shell pm grant org.fossify.calendar android.permission.WRITE_EXTERNAL_STORAGE
status=0
bash gradlew connectedDebugAndroidTest --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=app.olauncher.FossifyCalendarIntegrationTest -Pandroid.testInstrumentationRunnerArguments.requireFossify=true || status=$?
timeout 15s adb pull /data/local/tmp/jotterknocker-fossify-preview.png fossify-reminder-preview.png || true
timeout 15s adb pull /data/local/tmp/jotterknocker-fossify-final.png fossify-final.png || true
timeout 15s adb pull /sdcard/Android/data/app.scratchpad.launcher.debug/files/fossify-final.xml fossify-final.xml || true
exit "$status"
