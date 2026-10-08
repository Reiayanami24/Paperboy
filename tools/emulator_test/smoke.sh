#!/bin/sh
# Emulator smoke test (run by .github/workflows/emulator-test.yml on an API 30 emulator).
# Installs the debug APK, puts a test ROM into the library, starts the game in normal and
# e-ink mode, in portrait and landscape, and fails if the app dies. Logs and screenshots
# go to emulator-logs/.
set -u
PKG=com.paperboy.android
APK=app/build/outputs/apk/debug/app-debug.apk
ROM=build/test.gb
OUT=emulator-logs
mkdir -p "$OUT"
FAILED=0

adb install -r "$APK" || exit 1
adb root >/dev/null; sleep 3; adb wait-for-device
adb shell am start -W -n $PKG/.MainActivity >/dev/null # creates the app's data folders
sleep 3
adb shell am force-stop $PKG

ID=$(sha1sum "$ROM" | cut -d' ' -f1)
DATA=/data/data/$PKG
adb push "$ROM" /data/local/tmp/test.gb >/dev/null
APPUID=$(adb shell stat -c %u $DATA | tr -d '\r')
adb shell "mkdir -p $DATA/files/roms $DATA/shared_prefs && cp /data/local/tmp/test.gb $DATA/files/roms/$ID.gb && echo 'Smoke test' > $DATA/files/roms/$ID.meta"

run_case() { # name eink(true|false)
    name=$1
    adb shell am force-stop $PKG
    adb shell "echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name=\\\"eink_mode\\\" value=\\\"$2\\\" /></map>\" > $DATA/shared_prefs/settings.xml"
    adb shell "chown -R $APPUID:$APPUID $DATA/files $DATA/shared_prefs && restorecon -R $DATA/files $DATA/shared_prefs"
    adb shell settings put system accelerometer_rotation 0
    adb shell settings put system user_rotation 0
    adb logcat -c
    adb shell am start -W -n $PKG/.GameActivity --es rom_id "$ID" --es rom_name Smoke
    sleep 12
    adb exec-out screencap -p > "$OUT/$name-portrait.png"
    adb shell settings put system user_rotation 1
    sleep 8
    adb exec-out screencap -p > "$OUT/$name-landscape.png"
    adb logcat -d -v threadtime > "$OUT/$name-logcat.txt"
    if ! adb shell pidof $PKG >/dev/null || grep -q "FATAL EXCEPTION\|Fatal signal\|Unexpected error\|Emulation stopped" "$OUT/$name-logcat.txt"; then
        echo "::error::$name: the app died or reported an error"
        grep -A40 "FATAL EXCEPTION\|Fatal signal\|backtrace:" "$OUT/$name-logcat.txt" | head -80
        FAILED=1
    else
        echo "$name: still running after start and rotation"
    fi
    grep " Paperboy" "$OUT/$name-logcat.txt" | head -20
}

run_case normal false
run_case eink true
adb shell settings put system user_rotation 0
exit $FAILED
