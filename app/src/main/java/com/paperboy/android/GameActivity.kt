package com.paperboy.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Full-screen game screen: picture, on-screen controls, pause menu.
 *
 * Input sources are merged: touch (virtual pad), keyboard and gamepad. Back (or the
 * Menu button) pauses the game and opens the menu.
 */
class GameActivity : Activity(), Emulator.Listener {
    companion object {
        const val EXTRA_ID = "rom_id"
        const val EXTRA_NAME = "rom_name"
        private val FPS_FOR_DIVISOR = intArrayOf(0, 60, 30, 20, 15)
    }

    private lateinit var settings: Settings
    private lateinit var root: FrameLayout
    private lateinit var gameView: GameView
    private lateinit var controls: ControlsView
    private var emulator: Emulator? = null
    private val handler = Handler(Looper.getMainLooper())

    private var menuDialog: AlertDialog? = null
    private var reopenMenu = false
    private var pendingGhostClear = false
    private var ghostClearing = false
    private var quitting = false
    private var resumed = false
    private var gameName = ""

    private var touchMask = 0
    private var keyMask = 0
    private var padMask = 0

    private val insets = Rect()
    private val viewport = Rect()
    private var lastViewport = Rect()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID)
        gameName = intent.getStringExtra(EXTRA_NAME) ?: "Game"
        val store = GameStore(this)
        val rom = id?.let { store.readRom(it) }
        if (id == null || rom == null) {
            Toast.makeText(this, "Couldn't load this game", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        settings = Settings(this)

        // Edge-to-edge, including under a display cutout; insets are applied by hand in updateLayout().
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp

        gameView = GameView(this)
        controls = ControlsView(this)
        controls.einkStyle = settings.einkMode
        controls.onButtons = { mask ->
            touchMask = mask
            updateButtons()
        }
        controls.onMenu = { showMenu() }

        root = FrameLayout(this)
        root.setBackgroundColor(backgroundColor())
        root.addView(gameView, FrameLayout.LayoutParams(1, 1))
        root.addView(
            controls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, wi ->
            val bars = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insets.set(bars.left, bars.top, bars.right, bars.bottom)
            updateLayout()
            wi
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLayout() }

        emulator = Emulator(
            rom,
            store.sramFile(id),
            store.stateFile(id),
            gameView,
            this,
            Palettes.ALL[settings.paletteIndex].argb,
            settings.sound,
            settings.displayDivisor,
        ).also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        if (menuDialog?.isShowing != true && !ghostClearing) emulator?.resume()
    }

    override fun onPause() {
        resumed = false
        releaseAllInputs()
        emulator?.pause() // also flushes battery RAM to disk
        super.onPause()
    }

    override fun onDestroy() {
        quitting = true
        handler.removeCallbacksAndMessages(null)
        menuDialog?.dismiss()
        emulator?.shutdown()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0) // no transition animation: it would just ghost on e-ink
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun backgroundColor() = if (settings.einkMode) Color.WHITE else Color.BLACK

    private fun hideSystemBars() {
        window.insetsController?.let { c ->
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateLayout() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        Viewport.compute(w, h, insets, settings.integerScale, settings.einkMode, viewport)
        if (viewport != lastViewport) {
            lastViewport = Rect(viewport)
            // The surface is exactly the picture, so presenting a frame never touches the rest of the screen.
            val lp = FrameLayout.LayoutParams(viewport.width(), viewport.height())
            lp.leftMargin = viewport.left
            lp.topMargin = viewport.top
            gameView.layoutParams = lp
        }
        controls.update(viewport, insets)
    }

    // ---- emulator callbacks (emulation thread) ----------------------------------------------

    override fun onMessage(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onFatal(text: String) {
        runOnUiThread {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            quitting = true
            finish()
        }
    }

    // ---- menu ----------------------------------------------------------------------------

    override fun onBackPressed() {
        showMenu()
    }

    private fun showMenu() {
        val emu = emulator ?: return
        if (menuDialog?.isShowing == true || ghostClearing) return
        releaseAllInputs()
        emu.pause()

        val items = arrayOf(
            "Resume",
            "Save state",
            "Load state",
            "Palette: ${Palettes.ALL[settings.paletteIndex].name}",
            "Sound: ${if (settings.sound) "on" else "off"}",
            "Display rate: ${FPS_FOR_DIVISOR[settings.displayDivisor]} fps",
            "Integer scaling: ${if (settings.integerScale) "on" else "off"}",
            "E-ink mode: ${if (settings.einkMode) "on" else "off"}",
            "Clear screen ghosting",
            "Quit game",
        )
        reopenMenu = false
        menuDialog = AlertDialog.Builder(this)
            .setTitle(gameName)
            .setItems(items) { _, which -> onMenuItem(which) }
            .setOnDismissListener { onMenuDismissed() }
            .create()
            .also { it.show() }
    }

    private fun onMenuItem(which: Int) {
        val emu = emulator ?: return
        when (which) {
            1 -> emu.saveState()
            2 -> emu.loadState()
            3 -> {
                settings.paletteIndex = (settings.paletteIndex + 1) % Palettes.ALL.size
                emu.setPalette(Palettes.ALL[settings.paletteIndex].argb)
                reopenMenu = true
            }
            4 -> {
                settings.sound = !settings.sound
                emu.soundEnabled = settings.sound
                reopenMenu = true
            }
            5 -> {
                settings.displayDivisor = settings.displayDivisor % 4 + 1
                emu.displayDivisor = settings.displayDivisor
                reopenMenu = true
            }
            6 -> {
                settings.integerScale = !settings.integerScale
                lastViewport = Rect() // force the surface to be resized
                updateLayout()
                reopenMenu = true
            }
            7 -> {
                settings.einkMode = !settings.einkMode
                root.setBackgroundColor(backgroundColor())
                controls.einkStyle = settings.einkMode
                lastViewport = Rect()
                updateLayout()
                reopenMenu = true
            }
            8 -> pendingGhostClear = true
            9 -> {
                quitting = true
                finish()
            }
            else -> Unit // 0 = Resume
        }
    }

    private fun onMenuDismissed() {
        if (quitting || isFinishing) return
        when {
            pendingGhostClear -> {
                pendingGhostClear = false
                startGhostClear()
            }
            reopenMenu -> {
                reopenMenu = false
                showMenu()
            }
            else -> emulator?.resume()
        }
    }

    /**
     * E-ink panels keep faint traces of earlier frames. Flashing the whole screen
     * black, white, black, white (as the original firmware's "clear screen" did)
     * resets the pigment. Done with ordinary views, so it covers the entire screen.
     */
    private fun startGhostClear() {
        ghostClearing = true
        gameView.visibility = View.INVISIBLE
        controls.visibility = View.INVISIBLE
        val colors = intArrayOf(Color.BLACK, Color.WHITE, Color.BLACK, Color.WHITE)
        var step = 0
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (step < colors.size) {
                    root.setBackgroundColor(colors[step++])
                    handler.postDelayed(this, 550)
                } else {
                    root.setBackgroundColor(backgroundColor())
                    gameView.visibility = View.VISIBLE
                    controls.visibility = View.VISIBLE
                    ghostClearing = false
                    if (resumed && menuDialog?.isShowing != true) emulator?.resume()
                }
            }
        }
        handler.post(tick)
    }

    // ---- input ---------------------------------------------------------------------------

    private fun updateButtons() {
        emulator?.buttons = touchMask or keyMask or padMask
    }

    private fun releaseAllInputs() {
        touchMask = 0
        keyMask = 0
        padMask = 0
        if (::controls.isInitialized) controls.releaseAll()
        updateButtons()
    }

    private fun keyToButton(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> NativeCore.BTN_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> NativeCore.BTN_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NativeCore.BTN_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> NativeCore.BTN_RIGHT
        // Gamepad: keep physical positions. Game Boy A is the right-hand button (east),
        // which Android reports as BUTTON_B on Xbox-style pads.
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> NativeCore.BTN_A
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z -> NativeCore.BTN_B
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> NativeCore.BTN_START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DEL -> NativeCore.BTN_SELECT
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showMenu()
            return true
        }
        val bit = keyToButton(event.keyCode)
        if (bit != 0 && menuDialog?.isShowing != true && !ghostClearing) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> keyMask = keyMask or bit
                KeyEvent.ACTION_UP -> keyMask = keyMask and bit.inv()
            }
            updateButtons()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isStick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isStick && event.action == MotionEvent.ACTION_MOVE && menuDialog?.isShowing != true) {
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f) m = m or NativeCore.BTN_LEFT
            if (x > 0.5f) m = m or NativeCore.BTN_RIGHT
            if (y < -0.5f) m = m or NativeCore.BTN_UP
            if (y > 0.5f) m = m or NativeCore.BTN_DOWN
            padMask = m
            updateButtons()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
