# Paperboy for Android (SDK 30), tuned for the BOOX Tab Ultra C

Android port of the ESP32 / M5PaperS3 Game Boy emulator "paperboy". The emulator core
(Peanut-GB / CrankBoy) and the APU (minigb_apu) are the same C code as the firmware,
built with the NDK and called from a Kotlin app. Game Boy (DMG) games only, as in the
original; Color-only cartridges are flagged in the library and may not run.

`minSdk 30`, `targetSdk 30`, `compileSdk 30`, no third-party dependencies.

## Get an APK without installing anything (GitHub Actions)
1. Create a new **private** repository on github.com and upload this folder's contents (keep the
   `.github` folder; on the web use *Add file > Upload files*, or use git).
2. Open the repo's **Actions** tab. The *Build APK* workflow runs on every push (or press *Run workflow*).
3. When it finishes (about 5-10 minutes), open the run and download the **paperboy-debug-apk** artifact (a zip).
4. Unzip it, copy `app-debug.apk` to the tablet, open it from the file manager, and allow
   "install unknown apps" for that app when asked. It is signed with the debug key, so it installs directly.

## Build locally instead
Open this folder in Android Studio (Hedgehog or newer, JDK 17). It will download Gradle 8.2.1,
the NDK and CMake 3.22.1 when asked. Then Run, or `./gradlew assembleDebug` after Android
Studio has generated the wrapper. Install on the tablet with USB debugging / `adb install`.

## Using it
* **Add games...** opens the system file picker; pick `.gb`, `.gbc` or `.zip` files. ROMs are
  copied into app-private storage (Android 11 scoped storage: no permissions needed).
* Tap a game to play; long-press to delete it. **Back** or the on-screen **MENU** opens the pause menu.
* Menu: save/load state, palette, sound, display rate, integer scaling, e-ink mode, clear ghosting.
* Battery saves are written automatically (about every 2 s when changed, and on pause/exit).
* Keyboard (Z = B, X = A, Enter = Start, Backspace = Select, arrows) and Bluetooth gamepads work too.
* **Save logs…** (bottom of the game list) saves a text report to a place you pick: device info,
  settings, recent crashes (including native-code crashes, via Android 11's exit records) and the
  app's logcat. After a crash, the app offers this on its next start.

## E-ink tuning (BOOX Tab Ultra C: 10.3", 2480x1860 mono / 1240x930 colour, Android 11)
Detected automatically (`Build.MANUFACTURER == ONYX`); can be switched in the game menu on any device.
* **Frame-change detection in native code**: a frame identical to the previous one is never sent
  to the display, so a static screen costs the panel nothing.
* **Display-rate cap** (default 30 fps on BOOX; menu: 60/30/20/15). The game always emulates at full
  speed with sound; only panel refreshes are limited.
* **Surface sized exactly to the picture**, so a frame update never invalidates the whole screen.
* **Integer scaling** and **side-column controls** in landscape (nothing floats over the picture).
* **High-contrast grey palette** by default: Kaleido's colour filter washes out tinted palettes.
* **Outline controls** (black outline, filled when pressed), light UI, **no window/dialog animations**.
* **Clear screen ghosting**: full-screen black/white/black/white flashes (like the firmware's clear-screen).

Not done: the app does not call Onyx's private EPD SDK, so it cannot force a panel refresh mode by itself.
Set this app to a fast refresh mode in BOOX's own per-app refresh settings for gameplay. Display-rate
and refresh-mode defaults are reasoned, not measured on hardware: tune the display rate to taste.

## Tests (no Android needed)
`sh tools/host_test/run.sh` builds the native layer for the desktop and runs a smoke test with a
hand-assembled ROM (video, audio, battery RAM, save states, change detection).
`tools/host_test/fuzz.c` is a robustness fuzzer; build it with `-fsanitize=address,undefined`.
The *Emulator smoke test* workflow boots an Android 11 emulator, runs a test ROM in normal and e-ink
mode, rotates the screen, and fails on any crash; logcat and screenshots are uploaded as `emulator-logs`.

## Changes from the firmware code (found by sanitizer fuzzing)
* Cart RAM is allocated for every address the core can reach (it reads past a header-sized buffer, or
  through NULL for cartridges that declare no RAM).
* Unaligned 16-bit accesses in the core replaced by `memcpy` (undefined behaviour; faults on some ARM).
* Window-drawing 32-bit read-modify-write replaced by two in-bounds byte writes (overran the LCD buffer).
* RTC register bank index bounds-checked.
* ROM header size/RAM codes validated and truncated dumps padded before the core sees them.
