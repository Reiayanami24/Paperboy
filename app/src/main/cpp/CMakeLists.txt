cmake_minimum_required(VERSION 3.18.1)
project(paperboy C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(paperboy SHARED
    paperboy_jni.c
    gbemu.c            # also #includes the whole Peanut-GB / CrankBoy core (single translation unit)
    audio.c
    minigb_apu/minigb_apu.c)

target_include_directories(paperboy PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# APU emits 16-bit signed samples (matches the original firmware).
target_compile_definitions(paperboy PRIVATE MINIGB_APU_AUDIO_FORMAT_S16SYS)

target_compile_options(paperboy PRIVATE
    # Always optimise, even in Gradle "debug" builds: an emulator at -O0 is unplayable.
    -O3
    # The core type-puns uint8_t buffers as uint16_t/uint32_t when remapping
    # palettes, which is UB under strict aliasing.
    -fno-strict-aliasing
    -Wno-unused-label
    -Wno-unused-function
    -Wno-unused-variable)

find_library(log-lib log)
target_link_libraries(paperboy ${log-lib})
