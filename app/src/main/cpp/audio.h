#ifndef PAPERBOY_AUDIO_H
#define PAPERBOY_AUDIO_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* The emulated APU. The ESP32 firmware fed an I2S/PWM buzzer through several
 * backends; on Android there is one job: keep the APU state and synthesize one
 * frame of PCM on request. Kotlin hands it to android.media.AudioTrack. */

void audio_init(void);

/* Called by the CPU core for reads/writes of 0xFF10..0xFF3F. */
uint8_t audio_apu_read(uint16_t addr);
void    audio_apu_write(uint16_t addr, uint8_t val);

/* Synthesize one frame: AUDIO_SAMPLES stereo frames (interleaved int16) into
 * `out`, which must hold AUDIO_SAMPLES_TOTAL samples. */
void audio_synthesize_frame(int16_t *out);

/* APU snapshot, appended to save states so sound survives a load. */
size_t audio_snapshot_size(void);
void   audio_snapshot_save(uint8_t *dst);
bool   audio_snapshot_load(const uint8_t *src, size_t size);

#endif
