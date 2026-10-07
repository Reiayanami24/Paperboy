#include "audio.h"

#include <string.h>

#include "minigb_apu/minigb_apu.h"

static struct minigb_apu_ctx s_apu;

void audio_init(void) { minigb_apu_audio_init(&s_apu); }

uint8_t audio_apu_read(uint16_t addr) { return minigb_apu_audio_read(&s_apu, addr); }

void audio_apu_write(uint16_t addr, uint8_t val) { minigb_apu_audio_write(&s_apu, addr, val); }

void audio_synthesize_frame(int16_t *out) { minigb_apu_audio_callback(&s_apu, out); }

size_t audio_snapshot_size(void) { return sizeof(s_apu); }

void audio_snapshot_save(uint8_t *dst) { memcpy(dst, &s_apu, sizeof(s_apu)); }

bool audio_snapshot_load(const uint8_t *src, size_t size)
{
    if (size != sizeof(s_apu)) {
        return false;
    }
    memcpy(&s_apu, src, sizeof(s_apu));
    return true;
}
