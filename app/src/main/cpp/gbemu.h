#ifndef PAPERBOY_GBEMU_H
#define PAPERBOY_GBEMU_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GB_LCD_WIDTH    160
#define GB_LCD_HEIGHT   144

/* Joypad bits (same layout as the ESP32 firmware). A set bit means "pressed". */
#define GB_BTN_A        0x01
#define GB_BTN_B        0x02
#define GB_BTN_SELECT   0x04
#define GB_BTN_START    0x08
#define GB_BTN_RIGHT    0x10
#define GB_BTN_LEFT     0x20
#define GB_BTN_UP       0x40
#define GB_BTN_DOWN     0x80

/* One video frame: 160x144 pixels, 4 bytes each, byte order R,G,B,A (what
 * Bitmap.copyPixelsFromBuffer() expects for ARGB_8888). */
#define PAPERBOY_VIDEO_PIXELS (GB_LCD_WIDTH * GB_LCD_HEIGHT)

/* One frame of audio: stereo S16 at 32768 Hz. 32768 / 59.7275 fps = 548.6. */
#define PAPERBOY_AUDIO_RATE   32768
#define PAPERBOY_AUDIO_FRAMES 548

/* Takes a private copy of `rom`, so the caller's buffer may be freed. */
bool paperboy_gb_init(const uint8_t *rom, size_t rom_size);
void paperboy_gb_shutdown(void);

/* 4 entries, shade 0 (lightest) .. 3 (darkest), packed R,G,B,A in memory order. */
void paperboy_gb_set_palette(const uint32_t rgba[4]);

void paperboy_gb_set_buttons(uint8_t pressed_mask);

/* Runs one Game Boy frame (~16.7 ms of emulated time).
 *   render : if the picture changed since the last conversion, convert it into
 *            paperboy_gb_video_buffer() and make paperboy_gb_frame_updated()
 *            return true. On an e-ink panel every presented frame costs a
 *            refresh, so unchanged frames are deliberately NOT reported.
 *   audio  : synthesize PAPERBOY_AUDIO_FRAMES stereo frames. */
bool paperboy_gb_run_frame(bool render, bool audio);

/* True if the last run_frame(render=true) produced a new picture. */
bool paperboy_gb_frame_updated(void);

/* Force the next rendered frame to be converted (palette change, state load...). */
void paperboy_gb_force_redraw(void);

uint32_t *paperboy_gb_video_buffer(void);
size_t    paperboy_gb_video_buffer_bytes(void);
int16_t  *paperboy_gb_audio_buffer(void);
size_t    paperboy_gb_audio_buffer_bytes(void);

/* Battery-backed cartridge RAM (+ RTC) */
bool   paperboy_gb_has_persist(void);
size_t paperboy_gb_persist_size(void);
bool   paperboy_gb_persist_is_dirty(void);
bool   paperboy_gb_persist_export(uint8_t *dst, size_t dst_size, uint32_t unix_seconds);
bool   paperboy_gb_persist_import(const uint8_t *src, size_t src_size, uint32_t unix_seconds);
void   paperboy_gb_persist_mark_clean(void);

/* Full save states (CPU/PPU/RAM + APU registers) */
size_t paperboy_gb_state_size(void);
bool   paperboy_gb_state_export(uint8_t *dst, size_t dst_size);
bool   paperboy_gb_state_import(const uint8_t *src, size_t src_size);

bool        paperboy_gb_is_ready(void);
const char *paperboy_gb_last_error(void);
const char *paperboy_gb_title(void);

#endif
