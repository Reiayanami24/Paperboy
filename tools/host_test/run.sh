#!/bin/sh
# Builds the native layer for the host (gcc/clang) and runs the smoke test. No Android SDK/NDK/JDK needed.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../../app/src/main/cpp"
OUT="${TMPDIR:-/tmp}/paperboy_host_test"
mkdir -p "$OUT"
${CC:-cc} -std=gnu11 -O2 -g -fno-strict-aliasing -w -DMINIGB_APU_AUDIO_FORMAT_S16SYS -I"$CPP" \
  "$HERE/host_test.c" "$CPP/gbemu.c" "$CPP/audio.c" "$CPP/minigb_apu/minigb_apu.c" -o "$OUT/host_test"
"$OUT/host_test"
