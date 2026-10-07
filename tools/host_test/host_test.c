/* Host smoke test for the native layer (no Android needed). Run via run.sh. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbemu.h"

static int failures;
#define CHECK(cond, ...) do { if (cond) { printf("  ok:   "); printf(__VA_ARGS__); printf("\n"); } \
    else { printf("  FAIL: "); printf(__VA_ARGS__); printf("\n"); failures++; } } while (0)

#define RGBA(r, g, b) ((uint32_t)(r) | ((uint32_t)(g) << 8) | ((uint32_t)(b) << 16) | 0xFF000000u)
static const uint32_t PAL[4] = { RGBA(0xF0,0xF1,0xF2), RGBA(0xA0,0xA1,0xA2), RGBA(0x50,0x51,0x52), RGBA(0x10,0x20,0x30) };

/* 32 KiB MBC1+RAM+BATTERY ROM: draws a tile row pattern 3,2,1,0 (x2), starts a square wave, writes 0x5A to cart RAM. */
static void make_rom(uint8_t *rom)
{
    static const uint8_t code[] = {
        0x21,0x00,0x80, 0x06,0x08,
        0x3E,0xAA, 0x22, 0x3E,0xCC, 0x22, 0x05, 0x20,0xF7,
        0x3E,0xE4, 0xE0,0x47,
        0x3E,0x91, 0xE0,0x40,
        0x3E,0x80, 0xE0,0x26, 0x3E,0x77, 0xE0,0x24, 0x3E,0xFF, 0xE0,0x25,
        0x3E,0x80, 0xE0,0x11, 0x3E,0xF0, 0xE0,0x12, 0x3E,0x00, 0xE0,0x13, 0x3E,0x87, 0xE0,0x14,
        0x3E,0x0A, 0xEA,0x00,0x00,
        0x3E,0x5A, 0xEA,0x00,0xA0,
        0x18,0xFE };
    memset(rom, 0, 0x8000);
    rom[0x100] = 0x00; rom[0x101] = 0xC3; rom[0x102] = 0x50; rom[0x103] = 0x01;
    rom[0x104] = 0xCE; rom[0x105] = 0xED; rom[0x106] = 0x66; rom[0x107] = 0x66;
    memcpy(rom + 0x134, "HOSTTEST", 8);
    rom[0x147] = 0x03; rom[0x148] = 0x00; rom[0x149] = 0x02;
    uint8_t x = 0;
    for (int i = 0x134; i <= 0x14C; i++) x = (uint8_t)(x - rom[i] - 1);
    rom[0x14D] = x;
    memcpy(rom + 0x150, code, sizeof code);
}

static void run(int n, bool render, bool audio) { for (int i = 0; i < n; i++) paperboy_gb_run_frame(render, audio); }
static uint32_t px(int x, int y) { return paperboy_gb_video_buffer()[y * GB_LCD_WIDTH + x]; }

int main(void)
{
    uint8_t rom[0x8000];
    make_rom(rom);

    printf("== load ==\n");
    CHECK(!paperboy_gb_init(NULL, 0), "null ROM rejected");
    CHECK(!paperboy_gb_init(rom, 16), "tiny ROM rejected");
    { uint8_t bad[0x8000]; memcpy(bad, rom, sizeof bad); bad[0x147] = 0xFC;
      CHECK(!paperboy_gb_init(bad, sizeof bad), "unsupported cartridge rejected (%s)", paperboy_gb_last_error()); }
    { uint8_t bad[0x8000]; memcpy(bad, rom, sizeof bad); bad[0x148] = 0x40;
      CHECK(!paperboy_gb_init(bad, sizeof bad), "bad ROM-size code rejected (%s)", paperboy_gb_last_error()); }
    CHECK(!paperboy_gb_run_frame(true, true), "run_frame refuses when no ROM loaded");
    CHECK(paperboy_gb_init(rom, sizeof rom), "valid ROM loads");
    CHECK(strcmp(paperboy_gb_title(), "HOSTTEST") == 0, "title read from header: '%s'", paperboy_gb_title());
    CHECK(paperboy_gb_video_buffer_bytes() == 160 * 144 * 4, "video buffer is 160x144 RGBA");
    CHECK(paperboy_gb_audio_buffer_bytes() == 548 * 4, "audio buffer is 548 stereo S16 frames");
    paperboy_gb_set_palette(PAL);

    printf("== video ==\n");
    paperboy_gb_run_frame(true, true);
    CHECK(paperboy_gb_frame_updated(), "first rendered frame reports an update");
    run(4, true, true);
    CHECK(px(0,0) == PAL[3], "pixel(0,0) = shade 3 -> palette[3] (got %08X)", px(0,0));
    CHECK(px(0,0)==PAL[3] && px(1,0)==PAL[2] && px(2,0)==PAL[1] && px(3,0)==PAL[0] &&
          px(4,0)==PAL[3] && px(5,0)==PAL[2] && px(6,0)==PAL[1] && px(7,0)==PAL[0],
          "first 8 pixels follow 3,2,1,0,3,2,1,0 (bit order + palette correct)");
    CHECK(px(8,0)==PAL[3] && px(159,143)==PAL[0], "pattern repeats across the row and fills the screen");

    printf("== e-ink: unchanged frames are not reported ==\n");
    run(3, true, false);
    CHECK(!paperboy_gb_frame_updated(), "static picture -> frame_updated stays false");
    paperboy_gb_set_palette(PAL);
    paperboy_gb_run_frame(false, false);
    CHECK(!paperboy_gb_frame_updated(), "render=false never reports an update");
    paperboy_gb_run_frame(true, false);
    CHECK(paperboy_gb_frame_updated(), "palette change forces the next rendered frame");
    paperboy_gb_run_frame(true, false);
    CHECK(!paperboy_gb_frame_updated(), "...and only that one");
    paperboy_gb_force_redraw();
    paperboy_gb_run_frame(true, false);
    CHECK(paperboy_gb_frame_updated(), "force_redraw works");

    printf("== audio ==\n");
    int16_t *a = paperboy_gb_audio_buffer();
    int peak = 0;
    run(3, true, true);
    for (int i = 0; i < 548 * 2; i++) { int v = a[i] < 0 ? -a[i] : a[i]; if (v > peak) peak = v; }
    CHECK(peak > 1000, "APU produced an audible square wave (peak %d)", peak);
    memset(a, 0x7F, 548 * 4);
    run(1, true, false);
    CHECK(((uint8_t *)a)[0] == 0x7F && ((uint8_t *)a)[1000] == 0x7F, "audio=false leaves audio buffer untouched");

    printf("== battery RAM ==\n");
    CHECK(paperboy_gb_has_persist(), "cartridge reports battery RAM");
    CHECK(paperboy_gb_persist_is_dirty(), "RAM is dirty after the program wrote to it");
    size_t psz = paperboy_gb_persist_size();
    uint8_t *pbuf = malloc(psz);
    CHECK(paperboy_gb_persist_export(pbuf, psz, 1000), "export produced %zu bytes", psz);
    CHECK(memcmp(pbuf, "PBSV", 4) == 0, "export header magic 'PBSV'");
    CHECK(pbuf[16] == 0x5A, "cart RAM[0] == 0x5A written by the ROM is in the export");
    paperboy_gb_persist_mark_clean();
    CHECK(!paperboy_gb_persist_is_dirty(), "mark_clean clears dirty flag");
    CHECK(paperboy_gb_init(rom, sizeof rom), "re-init ROM (fresh RAM)");
    CHECK(paperboy_gb_persist_import(pbuf, psz, 1100), "import save (RTC catch-up of 100 s)");
    { size_t n = paperboy_gb_persist_size(); uint8_t *b2 = malloc(n);
      paperboy_gb_persist_export(b2, n, 1100); CHECK(b2[16] == 0x5A, "imported RAM[0] == 0x5A"); free(b2); }
    { uint8_t *bad = malloc(psz); memcpy(bad, pbuf, psz); bad[0] = 'X';
      CHECK(!paperboy_gb_persist_import(bad, psz, 0), "corrupt magic rejected (%s)", paperboy_gb_last_error()); free(bad); }
    free(pbuf);

    printf("== save states ==\n");
    CHECK(paperboy_gb_init(rom, sizeof rom), "re-init ROM");
    paperboy_gb_set_palette(PAL);
    run(20, true, true);
    size_t ssz = paperboy_gb_state_size();
    uint8_t *st = malloc(ssz);
    CHECK(paperboy_gb_state_export(st, ssz), "state export: %zu bytes", ssz);
    CHECK(memcmp(st + ssz - 8, "PBAP", 4) == 0, "state ends with APU footer 'PBAP'");

    uint32_t vid_a[3][160 * 144]; int16_t aud_a[3][548 * 2];
    for (int i = 0; i < 3; i++) { paperboy_gb_force_redraw(); run(1, true, true);
        memcpy(vid_a[i], paperboy_gb_video_buffer(), sizeof vid_a[i]); memcpy(aud_a[i], paperboy_gb_audio_buffer(), sizeof aud_a[i]); }
    CHECK(paperboy_gb_state_import(st, ssz), "state import ok");
    int same = 1;
    for (int i = 0; i < 3; i++) { paperboy_gb_force_redraw(); run(1, true, true);
        if (memcmp(vid_a[i], paperboy_gb_video_buffer(), sizeof vid_a[i]) || memcmp(aud_a[i], paperboy_gb_audio_buffer(), sizeof aud_a[i])) same = 0; }
    CHECK(same, "3 frames after restore are bit-identical (video+audio) to the original run");
    { /* footer-less (original firmware) state: strip the APU snapshot + footer */
      uint32_t apu = (uint32_t)st[ssz-4] | ((uint32_t)st[ssz-3] << 8) | ((uint32_t)st[ssz-2] << 16) | ((uint32_t)st[ssz-1] << 24);
      size_t apu_sz = apu;
      CHECK(paperboy_gb_state_import(st, ssz - 8 - apu_sz), "footer-less (legacy firmware) state still loads (%zu bytes)", ssz - 8 - apu_sz); }
    { uint8_t junk[64] = {1,2,3};
      CHECK(!paperboy_gb_state_import(junk, sizeof junk), "garbage state rejected (%s)", paperboy_gb_last_error()); }
    free(st);

    printf("== lifecycle ==\n");
    paperboy_gb_shutdown();
    CHECK(!paperboy_gb_run_frame(true, true), "run_frame refuses after shutdown");
    CHECK(paperboy_gb_state_size() == 0 && paperboy_gb_persist_size() == 0, "state/persist report size 0 after shutdown");
    paperboy_gb_shutdown();
    printf("  ok:   shutdown is idempotent\n");

    printf("\n%s (%d failures)\n", failures ? "FAILURES" : "ALL PASSED", failures);
    return failures ? 1 : 0;
}
