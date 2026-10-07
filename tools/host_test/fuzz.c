/* Robustness fuzzer: random "ROMs" with plausible headers, random buttons, state round-trips.
 * Build with -fsanitize=address,undefined. Usage: fuzz [iterations] [seed] */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbemu.h"

static uint32_t rng_state = 1;
static uint32_t rnd(void) { rng_state ^= rng_state << 13; rng_state ^= rng_state >> 17; rng_state ^= rng_state << 5; return rng_state; }

int main(int argc, char **argv)
{
    const int iters = argc > 1 ? atoi(argv[1]) : 100;
    rng_state = argc > 2 ? (uint32_t)atoi(argv[2]) | 1u : 12345u;
    static const uint8_t types[] = {0,1,2,3,5,6,8,9,0xB,0xC,0xD,0xF,0x10,0x11,0x12,0x13,0x19,0x1A,0x1B,0x1C,0x1D,0x1E,0x20,0x22};
    int loaded = 0, frames = 0, rt = 0;

    for (int it = 0; it < iters; it++) {
        const unsigned bank_code = rnd() % 5;
        const size_t size = (rnd() % 4 == 0) ? (size_t)(0x150 + rnd() % 0x20000) : ((size_t)0x8000 << bank_code);
        uint8_t *rom = malloc(size);
        for (size_t i = 0; i < size; i++) rom[i] = (uint8_t)rnd();
        if (rnd() % 2) memset(rom + 0x150, 0x00, 0x40);
        rom[0x100] = 0; rom[0x101] = 0xC3; rom[0x102] = 0x50; rom[0x103] = 0x01;
        rom[0x143] = 0;
        rom[0x147] = (rnd() % 10 == 0) ? (uint8_t)rnd() : types[rnd() % sizeof types];
        rom[0x148] = (rnd() % 8 == 0) ? (uint8_t)rnd() : (uint8_t)(rnd() % 9);
        rom[0x149] = (rnd() % 8 == 0) ? (uint8_t)rnd() : (uint8_t)(rnd() % 6);

        if (!paperboy_gb_init(rom, size)) { free(rom); continue; }
        free(rom); /* the core must own its copy */
        loaded++;

        for (int f = 0; f < 40; f++) {
            if (rnd() % 3 == 0) paperboy_gb_set_buttons((uint8_t)rnd());
            if (!paperboy_gb_run_frame(rnd() % 2, rnd() % 2)) break;
            frames++;
            if (f == 20) {
                size_t n = paperboy_gb_state_size();
                uint8_t *b = malloc(n);
                if (paperboy_gb_state_export(b, n) && paperboy_gb_state_import(b, n)) rt++;
                free(b);
            }
            if (f == 30 && paperboy_gb_has_persist()) {
                size_t n = paperboy_gb_persist_size();
                uint8_t *b = malloc(n);
                if (paperboy_gb_persist_export(b, n, 5)) paperboy_gb_persist_import(b, n, 9);
                free(b);
            }
        }
        paperboy_gb_shutdown();
    }
    printf("fuzz: %d iterations, %d ROMs accepted, %d frames run, %d state round-trips OK\n", iters, loaded, frames, rt);
    return 0;
}
