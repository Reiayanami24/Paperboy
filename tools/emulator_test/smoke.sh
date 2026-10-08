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
    adb exec-out screencap -p > "$OUT/$name-start.png"
    adb shell settings put system user_rotation 1
    sleep 8
    adb exec-out screencap -p > "$OUT/$name-rotated.png"
    PID=$(adb shell pidof $PKG | tr -d '\r')
    adb logcat -d -v threadtime > "$OUT/$name-logcat.txt"
    echo "$name: app pid after rotation: '${PID}'"
    if [ -z "$PID" ]; then
        echo "::error::$name: the app is no longer running"
        adb shell dumpsys activity exit-info $PKG | head -40
        FAILED=1
    fi
    # Only this app's lines: other apps on the emulator log their own errors.
    APPLOG="$OUT/$name-app.txt"
    grep "com.paperboy.android\| Paperboy\|${PID:-NOPID}" "$OUT/$name-logcat.txt" > "$APPLOG"
    if grep -q "FATAL EXCEPTION\|Fatal signal\| E Paperboy" "$APPLOG"; then
        echo "::error::$name: crash or emulator error in logcat"
        grep -A40 "FATAL EXCEPTION\|Fatal signal\| E Paperboy" "$APPLOG" | head -80
        FAILED=1
    fi
    # Anything this app logged at warning level or above.
    [ -n "$PID" ] && grep " $PID .* [WEF] " "$OUT/$name-logcat.txt" | grep -v "^--" | head -30
    grep " Paperboy" "$OUT/$name-logcat.txt" | head -20
    # Small preview of the rotated screen in the log, for quick checks without the artifact.
    IM=$(command -v magick || command -v convert || true)
    if [ -n "$IM" ]; then
        echo "$name preview (base64 JPEG):"
        "$IM" "$OUT/$name-rotated.png" -resize 360x -quality 50 jpg:- | base64 -w0
        echo
    fi
}

run_case normal false
run_case eink true
adb shell settings put system user_rotation 0
exit $FAILED
