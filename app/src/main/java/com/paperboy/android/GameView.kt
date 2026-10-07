package com.paperboy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.floor

/**
 * Where the 160x144 picture sits inside a w x h window, and how big it is.
 * Shared by [GameActivity] (to size the surface) and [ControlsView] (to know how
 * much room is left for the buttons).
 */
object Viewport {
    /**
     * @param reserveSides keep side columns free for the pad in landscape (used on e-ink,
     *        where controls floating over the picture would force the panel to redraw it).
     */
    fun compute(w: Int, h: Int, insets: Rect, integerScale: Boolean, reserveSides: Boolean, out: Rect) {
        val availW = (w - insets.left - insets.right).coerceAtLeast(1)
        val availH = (h - insets.top - insets.bottom).coerceAtLeast(1)
        val portrait = h >= w
        // Portrait: the picture takes the top part and the controls get the rest.
        val maxH = if (portrait) availH * 0.60f else availH.toFloat()
        val maxW = if (!portrait && reserveSides) availW * 0.58f else availW.toFloat()
        var scale = minOf(maxW / NativeCore.SCREEN_WIDTH, maxH / NativeCore.SCREEN_HEIGHT)
        if (integerScale && scale >= 1f) scale = floor(scale)
        val gw = (NativeCore.SCREEN_WIDTH * scale).toInt()
        val gh = (NativeCore.SCREEN_HEIGHT * scale).toInt()
        val x = insets.left + (availW - gw) / 2
        val y = if (portrait) insets.top else insets.top + (availH - gh) / 2
        out.set(x, y, x + gw, y + gh)
    }
}

/**
 * Draws emulator frames. The view is sized to exactly the picture, so a frame update
 * only ever touches (and, on e-ink, only ever refreshes) that rectangle.
 *
 * [present] is called from the emulation thread, the surface callbacks arrive on the
 * UI thread, so everything that touches the surface goes through [drawLock].
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val drawLock = Any()
    private var surfaceReady = false // guarded by drawLock

    @Volatile
    private var lastFrame: Bitmap? = null

    private val src = Rect(0, 0, NativeCore.SCREEN_WIDTH, NativeCore.SCREEN_HEIGHT)
    private val dst = Rect()

    // No bitmap filtering: GB pixels must stay crisp.
    private val paint = Paint()

    init {
        holder.addCallback(this)
    }

    fun present(bitmap: Bitmap) {
        lastFrame = bitmap
        synchronized(drawLock) {
            if (!surfaceReady) return
            val canvas = lockCanvasCompat() ?: return
            try {
                dst.set(0, 0, canvas.width, canvas.height)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, src, dst, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun lockCanvasCompat(): Canvas? {
        val hw = try {
            holder.lockHardwareCanvas()
        } catch (e: Exception) {
            null
        }
        if (hw != null) return hw
        return try {
            holder.lockCanvas()
        } catch (e: Exception) {
            null
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        synchronized(drawLock) { surfaceReady = true }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(drawLock) { surfaceReady = true }
        lastFrame?.let { present(it) } // repaint at the new size even while paused
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        synchronized(drawLock) { surfaceReady = false }
    }
}
