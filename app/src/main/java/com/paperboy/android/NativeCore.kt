package com.paperboy.android

import java.nio.ByteBuffer

/**
 * JNI surface of libpaperboy.so (the Peanut-GB / CrankBoy core from the original
 * ESP32 firmware).
 *
 * THREADING: the native core is not thread-safe. [Emulator] makes every call
 * from its single emulation thread; nothing else may call these functions.
 */
object NativeCore {
    // Joypad bits; a set bit means "pressed". Same layout as the firmware.
    const val BTN_A = 0x01
    const val BTN_B = 0x02
    const val BTN_SELECT = 0x04
    const val BTN_START = 0x08
    const val BTN_RIGHT = 0x10
    const val BTN_LEFT = 0x20
    const val BTN_UP = 0x40
    const val BTN_DOWN = 0x80

    const val SCREEN_WIDTH = 160
    const val SCREEN_HEIGHT = 144

    /** The APU renders [AUDIO_FRAMES] stereo S16 frames per emulated video frame. */
    const val AUDIO_RATE = 32768
    const val AUDIO_FRAMES = 548

    /** One Game Boy frame is 70224 cycles at 4194304 Hz = 16.7427 ms (59.7275 fps). */
    const val FRAME_NANOS = 16_742_706L

    init {
        System.loadLibrary("paperboy")
    }

    external fun nInit(rom: ByteArray): Boolean
    external fun nShutdown()

    /** Direct buffer, 160x144 pixels, bytes R,G,B,A. Owned by native code; valid until nShutdown. */
    external fun nVideoBuffer(): ByteBuffer

    /** Direct buffer, [AUDIO_FRAMES] interleaved stereo S16 frames. */
    external fun nAudioBuffer(): ByteBuffer

    /** Four colours, lightest shade first, as Android 0xAARRGGBB ints. */
    external fun nSetPalette(argb: IntArray)
    external fun nSetButtons(mask: Int)

    /**
     * Runs one emulated frame. With [render] the picture is converted into the video
     * buffer only if it changed; check [nFrameUpdated] afterwards.
     */
    external fun nRunFrame(render: Boolean, audio: Boolean): Boolean

    /** True if the last nRunFrame(render = true) produced a new picture. */
    external fun nFrameUpdated(): Boolean

    /** Makes the next rendered frame convert even if the picture is unchanged. */
    external fun nForceRedraw()

    external fun nHasPersist(): Boolean
    external fun nPersistDirty(): Boolean
    external fun nPersistExport(unixSeconds: Int): ByteArray?
    external fun nPersistImport(data: ByteArray, unixSeconds: Int): Boolean
    external fun nPersistMarkClean()

    external fun nStateExport(): ByteArray?
    external fun nStateImport(data: ByteArray): Boolean

    external fun nLastError(): String
    external fun nTitle(): String
}
