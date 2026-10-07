/*
 * gbemu.c - Android port of the Paperboy emulator wrapper.
 *
 * The ESP32 / M5PaperS3 version dithered the 2-bit LCD into a 1-bit rotated
 * e-paper framebuffer and only touched lines that changed. Here the 2-bit LCD
 * is expanded to RGBA through a 4-entry palette, and the same idea is kept at
 * frame level: a frame identical to the last one converted is not reported, so
 * the Kotlin side never asks an e-ink panel to refresh for nothing.
 * Cart RAM / RTC persistence and save states behave like the firmware's.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "paperboy", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "paperboy", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "paperboy", __VA_ARGS__)
#else
#define LOG_HOST(lvl, ...) (fprintf(stderr, lvl "/paperboy: "), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGI(...) LOG_HOST("I", __VA_ARGS__)
#define LOGW(...) LOG_HOST("W", __VA_ARGS__)
#define LOGE(...) LOG_HOST("E", __VA_ARGS__)
#endif

#include "gbemu.h"
#include "audio.h"

#define PGB_CGB                 0   /* DMG only, as in the original firmware */
#define ENABLE_SOUND            1
#define ENABLE_LCD              1
#define PGB_IMPL

/* Hooks the core calls for APU register access (see peanut_gb.h). */
uint8_t audio_read(void *audio, const uint16_t addr);
void audio_write(void *audio, const uint16_t addr, const uint8_t val);

#include "crankboy_core/peanut_gb.h"

_Static_assert(PAPERBOY_AUDIO_FRAMES == AUDIO_SAMPLES, "APU frame size mismatch");
_Static_assert(AUDIO_SAMPLE_RATE == PAPERBOY_AUDIO_RATE, "APU sample rate mismatch");
_Static_assert(LCD_WIDTH == GB_LCD_WIDTH && LCD_HEIGHT == GB_LCD_HEIGHT, "LCD size mismatch");

/* Globals the CrankBoy compat header declares `extern`. */
int preferences_cgb_speed;
int preferences_ppu_timing;
int audio_enabled;

static gb_s s_gb;
static uint8_t *s_rom;
static size_t s_rom_size;
static uint8_t *s_cart_ram;
static size_t s_cart_ram_size;
static uint8_t s_lcd[LCD_BUFFER_BYTES] __attribute__((aligned(32)));
static uint8_t s_prev_lcd[LCD_BUFFER_BYTES] __attribute__((aligned(32)));
static uint8_t s_wram[WRAM_SIZE_CGB] __attribute__((aligned(32)));
static uint8_t s_vram[VRAM_SIZE_CGB] __attribute__((aligned(32)));
static bool s_ready;
static bool s_pending_dirty;   /* LCD differs from what was last converted */
static bool s_frame_updated;
static char s_last_error[128];
static char s_title[17];

/* Output buffers are owned here and handed to Kotlin as direct ByteBuffers. */
static uint32_t s_video[PAPERBOY_VIDEO_PIXELS] __attribute__((aligned(16)));
static int16_t  s_audio[AUDIO_SAMPLES_TOTAL]   __attribute__((aligned(16)));

#define RGBA(r, g, b) ((uint32_t)(r) | ((uint32_t)(g) << 8) | ((uint32_t)(b) << 16) | 0xFF000000u)
static uint32_t s_palette[4] = {
    RGBA(0xFF, 0xFF, 0xFF), RGBA(0xAA, 0xAA, 0xAA), RGBA(0x55, 0x55, 0x55), RGBA(0x00, 0x00, 0x00),
};

struct persist_header {
    uint8_t magic[4];
    uint8_t version;
    uint8_t has_cart_ram;
    uint8_t has_rtc;
    uint8_t reserved;
    uint32_t cart_ram_size;
    uint32_t timestamp;
};

#define PERSIST_VERSION 1u
static const uint8_t PERSIST_MAGIC[4] = {'P', 'B', 'S', 'V'};

/* Save-state footer: [APU snapshot][magic "PBAP"][u32 little-endian apu size] */
static const uint8_t STATE_APU_MAGIC[4] = {'P', 'B', 'A', 'P'};
#define STATE_FOOTER_BYTES 8u

/* ---- core callbacks ------------------------------------------------------ */

uint8_t audio_read(void *audio, const uint16_t addr) { (void)audio; return audio_apu_read(addr); }

void audio_write(void *audio, const uint16_t addr, const uint8_t val) { (void)audio; audio_apu_write(addr, val); }

void __gb_on_breakpoint(gb_s *gb, int breakpoint_number) { (void)gb; (void)breakpoint_number; }

static void set_last_error(const char *msg) { strlcpy(s_last_error, msg, sizeof(s_last_error)); }

static void gb_error_cb(gb_s *gb, const enum gb_error_e err, const uint16_t addr)
{
    (void)gb;
    LOGE("Emulator error=%d at addr=0x%04x", (int)err, addr);
    set_last_error("runtime core error");
}

static bool gb_has_persist_data(void) { return s_cart_ram_size > 0 || s_gb.cart_battery; }

static size_t gb_persist_rtc_size(void) { return s_gb.cart_battery ? sizeof(s_gb.cart_rtc) : 0u; }

/* ---- video --------------------------------------------------------------- */

/* Expand the packed 2-bit LCD (4 px/byte, LSB first) through the palette. */
static void convert_lcd(void)
{
    uint32_t pal[4];
    memcpy(pal, s_palette, sizeof(pal));
    for (unsigned y = 0; y < GB_LCD_HEIGHT; y++) {
        const uint8_t *src = &s_lcd[y * LCD_WIDTH_PACKED];
        uint32_t *dst = &s_video[y * GB_LCD_WIDTH];
        for (unsigned xb = 0; xb < LCD_WIDTH_PACKED; xb++) {
            const uint8_t p = src[xb];
            dst[0] = pal[p & 3u];
            dst[1] = pal[(p >> 2) & 3u];
            dst[2] = pal[(p >> 4) & 3u];
            dst[3] = pal[(p >> 6) & 3u];
            dst += 4;
        }
    }
}

void paperboy_gb_set_palette(const uint32_t rgba[4])
{
    memcpy(s_palette, rgba, sizeof(s_palette));
    s_pending_dirty = true;
}

void paperboy_gb_force_redraw(void) { s_pending_dirty = true; }

/* ---- lifecycle ----------------------------------------------------------- */

void paperboy_gb_shutdown(void)
{
    s_ready = false;
    free(s_cart_ram);
    s_cart_ram = NULL;
    s_cart_ram_size = 0;
    free(s_rom);
    s_rom = NULL;
    s_rom_size = 0;
}

bool paperboy_gb_init(const uint8_t *rom, size_t rom_size)
{
    enum gb_init_error_e init_err;
    size_t save_size;
    size_t declared;
    size_t alloc;

    paperboy_gb_shutdown();
    set_last_error("not initialized");

    if (rom == NULL || rom_size < 0x150) {
        set_last_error("rom buffer missing or too small");
        return false;
    }

    memset(&s_gb, 0, sizeof(s_gb));
    memset(s_lcd, 0, sizeof(s_lcd));
    memset(s_prev_lcd, 0, sizeof(s_prev_lcd));
    memset(s_wram, 0, sizeof(s_wram));
    memset(s_vram, 0, sizeof(s_vram));
    memset(s_video, 0, sizeof(s_video));
    memset(s_audio, 0, sizeof(s_audio));
    s_title[0] = '\0';
    s_frame_updated = false;
    s_pending_dirty = true;

    /* The core indexes fixed-size tables with these header bytes without
     * checking them, so reject values it cannot handle instead of reading out
     * of bounds. (0x148: 0..8 = 32 KiB..8 MiB; 0x149: 0..5 = none..128 KiB) */
    if (rom[0x148] > 8) {
        set_last_error("unsupported ROM size code in header");
        return false;
    }
    if (rom[0x149] > 5) {
        set_last_error("unsupported RAM size code in header");
        return false;
    }

    /* Truncated dumps are padded to the size the header declares, so bank
     * switching can never read past the end of the buffer. */
    declared = (size_t)0x8000 << rom[0x148];
    alloc = rom_size > declared ? rom_size : declared;
    s_rom = (uint8_t *)calloc(1, alloc);
    if (s_rom == NULL) {
        set_last_error("rom alloc failed");
        return false;
    }
    memcpy(s_rom, rom, rom_size);
    s_rom_size = alloc;
    if (alloc != rom_size) {
        LOGW("ROM is %zu bytes but header declares %zu; padded", rom_size, declared);
    }

    init_err = gb_init(&s_gb, s_wram, s_vram, s_lcd, s_rom, s_rom_size, gb_error_cb, NULL, false);
    if (init_err != GB_INIT_NO_ERROR && init_err != GB_INIT_NO_ERROR_BUT_REQUIRES_CGB) {
        LOGE("gb_init failed: %d", (int)init_err);
        set_last_error("gb_init failed (not a valid Game Boy ROM?)");
        paperboy_gb_shutdown();
        return false;
    }

    audio_init();
    gb_reset(&s_gb, false);

    save_size = gb_get_save_size(&s_gb);
    {
        /* The core addresses cart RAM in whole 8 KiB banks (num_ram_banks * 0x2000)
         * and reads 0xA000.. through the pointer even for cartridges that declare
         * no RAM. The firmware sized the buffer exactly to the header (2 KiB, or
         * NULL), so a ROM that enabled RAM and touched it read out of bounds.
         * Allocate everything the core can address; only `save_size` is persisted. */
        size_t addressable = (size_t)s_gb.num_ram_banks * CRAM_BANK_SIZE;
        size_t ram_alloc = save_size;
        if (ram_alloc < addressable) ram_alloc = addressable;
        if (ram_alloc < CRAM_BANK_SIZE) ram_alloc = CRAM_BANK_SIZE;
        s_cart_ram = (uint8_t *)calloc(1, ram_alloc);
        if (s_cart_ram == NULL) {
            set_last_error("cart ram alloc failed");
            paperboy_gb_shutdown();
            return false;
        }
        s_cart_ram_size = save_size;
        s_gb.gb_cart_ram = s_cart_ram;
        s_gb.gb_cart_ram_size = save_size;
    }

    gb_init_lcd(&s_gb);
    s_gb.direct.joypad = 0xFF;
    audio_enabled = 1;
    s_gb.direct.sram_updated = 0;
    s_gb.direct.sram_dirty = 0;

    gb_get_rom_name(s_rom, s_title);
    s_title[16] = '\0';
    LOGI("Core initialized. ROM title: '%s' (%zu bytes, save %zu bytes)", s_title, s_rom_size, s_cart_ram_size);

    s_ready = true;
    set_last_error("ok");
    return true;
}

void paperboy_gb_set_buttons(uint8_t pressed_mask)
{
    if (!s_ready) {
        return;
    }
    s_gb.direct.joypad = (uint8_t)(~pressed_mask);
}

bool paperboy_gb_run_frame(bool render, bool audio)
{
    if (!s_ready) {
        set_last_error("no ROM loaded");
        return false;
    }

    /* The core always draws (cheap on a phone/tablet CPU); only the RGBA
     * conversion and the Kotlin-side present are conditional. */
    s_gb.direct.frame_skip = false;
    gb_run_frame__dmg(&s_gb);

    if (memcmp(s_lcd, s_prev_lcd, sizeof(s_lcd)) != 0) {
        memcpy(s_prev_lcd, s_lcd, sizeof(s_lcd));
        s_pending_dirty = true;
    }

    s_frame_updated = false;
    if (render && s_pending_dirty) {
        convert_lcd();
        s_pending_dirty = false;
        s_frame_updated = true;
    }

    if (audio) {
        audio_synthesize_frame(s_audio);
    }
    return true;
}

bool paperboy_gb_frame_updated(void) { return s_frame_updated; }

uint32_t *paperboy_gb_video_buffer(void) { return s_video; }
size_t paperboy_gb_video_buffer_bytes(void) { return sizeof(s_video); }
int16_t *paperboy_gb_audio_buffer(void) { return s_audio; }
size_t paperboy_gb_audio_buffer_bytes(void) { return sizeof(s_audio); }

/* ---- battery RAM + RTC --------------------------------------------------- */

bool paperboy_gb_has_persist(void) { return s_ready && gb_has_persist_data(); }

size_t paperboy_gb_persist_size(void)
{
    if (!s_ready || !gb_has_persist_data()) {
        return 0;
    }
    return sizeof(struct persist_header) + s_cart_ram_size + gb_persist_rtc_size();
}

bool paperboy_gb_persist_is_dirty(void)
{
    if (!s_ready || !gb_has_persist_data()) {
        return false;
    }
    return s_gb.direct.sram_updated != 0;
}

bool paperboy_gb_persist_export(uint8_t *dst, size_t dst_size, uint32_t timestamp)
{
    struct persist_header header;
    uint8_t *cursor;

    if (!s_ready || !gb_has_persist_data() || dst == NULL) {
        set_last_error("persist export unavailable");
        return false;
    }
    if (dst_size < paperboy_gb_persist_size()) {
        set_last_error("persist export buffer too small");
        return false;
    }

    memset(&header, 0, sizeof(header));
    memcpy(header.magic, PERSIST_MAGIC, sizeof(header.magic));
    header.version = PERSIST_VERSION;
    header.has_cart_ram = (uint8_t)(s_cart_ram_size > 0);
    header.has_rtc = (uint8_t)(s_gb.cart_battery != 0);
    header.cart_ram_size = (uint32_t)s_cart_ram_size;
    header.timestamp = timestamp;

    memcpy(dst, &header, sizeof(header));
    cursor = dst + sizeof(header);
    if (s_cart_ram_size > 0) {
        memcpy(cursor, s_cart_ram, s_cart_ram_size);
        cursor += s_cart_ram_size;
    }
    if (s_gb.cart_battery) {
        memcpy(cursor, s_gb.cart_rtc, sizeof(s_gb.cart_rtc));
    }
    set_last_error("ok");
    return true;
}

bool paperboy_gb_persist_import(const uint8_t *src, size_t src_size, uint32_t current_timestamp)
{
    struct persist_header header;
    const uint8_t *cursor;

    if (!s_ready || !gb_has_persist_data() || src == NULL) {
        set_last_error("persist import unavailable");
        return false;
    }
    if (src_size < sizeof(header)) {
        set_last_error("persist blob too small");
        return false;
    }

    memcpy(&header, src, sizeof(header));
    if (memcmp(header.magic, PERSIST_MAGIC, sizeof(header.magic)) != 0 || header.version != PERSIST_VERSION) {
        set_last_error("persist blob format mismatch");
        return false;
    }
    if ((header.has_cart_ram != 0) != (s_cart_ram_size > 0) || header.cart_ram_size != s_cart_ram_size) {
        set_last_error("persist cart ram mismatch");
        return false;
    }
    if ((header.has_rtc != 0) != (s_gb.cart_battery != 0)) {
        set_last_error("persist rtc mismatch");
        return false;
    }
    if (src_size != sizeof(header) + header.cart_ram_size + (header.has_rtc ? sizeof(s_gb.cart_rtc) : 0u)) {
        set_last_error("persist blob size mismatch");
        return false;
    }

    cursor = src + sizeof(header);
    if (header.cart_ram_size > 0) {
        memcpy(s_cart_ram, cursor, header.cart_ram_size);
        cursor += header.cart_ram_size;
    }
    if (header.has_rtc) {
        memcpy(s_gb.cart_rtc, cursor, sizeof(s_gb.cart_rtc));
        if (header.timestamp > 0 && current_timestamp >= header.timestamp) {
            gb_catch_up_rtc_direct(&s_gb, current_timestamp - header.timestamp);
        }
        memcpy(s_gb.latched_rtc, s_gb.cart_rtc, sizeof(s_gb.latched_rtc));
    }

    s_gb.direct.sram_updated = 0;
    s_gb.direct.sram_dirty = 0;
    set_last_error("ok");
    return true;
}

void paperboy_gb_persist_mark_clean(void)
{
    if (!s_ready) {
        return;
    }
    s_gb.direct.sram_updated = 0;
    s_gb.direct.sram_dirty = 0;
}

/* ---- save states --------------------------------------------------------- */

size_t paperboy_gb_state_size(void)
{
    if (!s_ready) {
        return 0;
    }
    return gb_get_state_size(&s_gb) + audio_snapshot_size() + STATE_FOOTER_BYTES;
}

bool paperboy_gb_state_export(uint8_t *dst, size_t dst_size)
{
    const size_t total = paperboy_gb_state_size();
    const size_t core = total ? total - audio_snapshot_size() - STATE_FOOTER_BYTES : 0;
    const uint32_t apu = (uint32_t)audio_snapshot_size();
    uint8_t *p;

    if (!s_ready || dst == NULL) {
        set_last_error("state export unavailable");
        return false;
    }
    if (dst_size < total) {
        set_last_error("state export buffer too small");
        return false;
    }

    gb_state_save(&s_gb, (char *)dst);
    p = dst + core;
    audio_snapshot_save(p);
    p += apu;
    memcpy(p, STATE_APU_MAGIC, 4);
    p[4] = (uint8_t)(apu & 0xFF);
    p[5] = (uint8_t)((apu >> 8) & 0xFF);
    p[6] = (uint8_t)((apu >> 16) & 0xFF);
    p[7] = (uint8_t)((apu >> 24) & 0xFF);
    set_last_error("ok");
    return true;
}

bool paperboy_gb_state_import(const uint8_t *src, size_t src_size)
{
    const char *result;
    size_t core_size = src_size;
    const uint8_t *apu_blob = NULL;
    const size_t apu = audio_snapshot_size();

    if (!s_ready || src == NULL) {
        set_last_error("state import unavailable");
        return false;
    }

    /* States written by the original firmware have no APU footer; accept them too. */
    if (src_size >= STATE_FOOTER_BYTES + apu) {
        const uint8_t *f = src + src_size - STATE_FOOTER_BYTES;
        const uint32_t recorded = (uint32_t)f[4] | ((uint32_t)f[5] << 8) | ((uint32_t)f[6] << 16) | ((uint32_t)f[7] << 24);
        if (memcmp(f, STATE_APU_MAGIC, 4) == 0 && recorded == apu) {
            core_size = src_size - STATE_FOOTER_BYTES - apu;
            apu_blob = src + core_size;
        }
    }

    result = gb_state_load(&s_gb, (const char *)src, core_size);
    if (result != NULL) {
        set_last_error(result);
        return false;
    }
    if (apu_blob != NULL) {
        audio_snapshot_load(apu_blob, apu);
    }
    memset(s_prev_lcd, 0xFF, sizeof(s_prev_lcd)); /* different from any real frame */
    s_pending_dirty = true;
    set_last_error("ok");
    return true;
}

bool paperboy_gb_is_ready(void) { return s_ready; }
const char *paperboy_gb_last_error(void) { return s_last_error; }
const char *paperboy_gb_title(void) { return s_title; }
