package com.paperboy.android

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Detection of BOOX (Onyx) e-ink devices such as the Tab Ultra C. */
object Eink {
    fun isOnyx(): Boolean =
        Build.MANUFACTURER.equals("onyx", ignoreCase = true) ||
            Build.BRAND.equals("onyx", ignoreCase = true) ||
            Build.BRAND.equals("boox", ignoreCase = true)
}

object Palettes {
    class Palette(val name: String, val argb: IntArray)

    /** Four shades each, lightest first. Index 0 is tuned for e-ink. */
    val ALL = listOf(
        // Neutral, evenly spaced grays. On a Kaleido colour panel any tint is washed out
        // by the colour filter, and a gray ramp stays on the sharp 300 ppi monochrome layer.
        Palette("E-ink contrast", intArrayOf(
            Color.rgb(0xFF, 0xFF, 0xFF), Color.rgb(0xB4, 0xB4, 0xB4),
            Color.rgb(0x5A, 0x5A, 0x5A), Color.rgb(0x00, 0x00, 0x00))),
        Palette("Classic green", intArrayOf(
            Color.rgb(0xE0, 0xF8, 0xD0), Color.rgb(0x88, 0xC0, 0x70),
            Color.rgb(0x34, 0x68, 0x56), Color.rgb(0x08, 0x18, 0x20))),
        Palette("Pocket", intArrayOf(
            Color.rgb(0xC4, 0xCF, 0xA1), Color.rgb(0x8B, 0x95, 0x6D),
            Color.rgb(0x4D, 0x53, 0x3C), Color.rgb(0x1F, 0x1F, 0x1F))),
        Palette("Amber", intArrayOf(
            Color.rgb(0xFF, 0xE9, 0xA8), Color.rgb(0xE8, 0xA8, 0x3C),
            Color.rgb(0x8A, 0x4B, 0x12), Color.rgb(0x2A, 0x14, 0x05))),
    )
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val onyx = Eink.isOnyx()

    /** E-ink presentation: light UI, outline controls, frame-rate cap. Defaults on for BOOX devices. */
    var einkMode: Boolean
        get() = prefs.getBoolean("eink_mode", onyx)
        set(value) = prefs.edit().putBoolean("eink_mode", value).apply()

    var paletteIndex: Int
        get() {
            val stored = prefs.getInt("palette", -1)
            val fallback = if (einkMode) 0 else 1
            return (if (stored < 0) fallback else stored).coerceIn(0, Palettes.ALL.size - 1)
        }
        set(value) = prefs.edit().putInt("palette", value).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = prefs.edit().putBoolean("sound", value).apply()

    /** Scale the picture by whole numbers only, so every pixel is the same size. */
    var integerScale: Boolean
        get() = prefs.getBoolean("integer_scale", true)
        set(value) = prefs.edit().putBoolean("integer_scale", value).apply()

    /**
     * The picture is presented at most every Nth emulated frame: 1 = 60 fps, 2 = 30, 3 = 20, 4 = 15.
     * The game itself always runs at full speed; this only limits how often the panel is asked to
     * refresh. Unchanged frames are never presented regardless of this setting.
     */
    var displayDivisor: Int
        get() = prefs.getInt("display_divisor", if (einkMode) 2 else 1).coerceIn(1, 4)
        set(value) = prefs.edit().putInt("display_divisor", value.coerceIn(1, 4)).apply()
}
