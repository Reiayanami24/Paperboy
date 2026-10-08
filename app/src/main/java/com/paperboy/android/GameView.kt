package com.paperboy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
 * Frames are drawn on a dedicated thread. [present] (emulation thread) only copies the
 * newest picture into a staging buffer and returns: posting to the surface can block for
 * a long time when the panel is slow (e-ink), and if that happened on the emulation
 * thread the audio would underrun and the game would stutter. If the panel falls behind,
 * older frames are simply skipped.
 *
 * Everything that touches the surface goes through [drawLock], because the surface
 * callbacks arrive on the UI thread.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val drawLock = Any()
    private var surfaceReady = false // guarded by drawLock
    private var surfaceW = 0 // guarded by drawLock: size reported by surfaceChanged
    private var surfaceH = 0
    private var logDraws = 0 // guarded by drawLock: log the first few draws after a resize

    // Hand-over from the emulation thread to the render thread; all guarded by frameLock.
    private val frameLock = ReentrantLock()
    private val frameCond = frameLock.newCondition()
    private val staging = ByteBuffer.allocateDirect(NativeCore.SCREEN_WIDTH * NativeCore.SCREEN_HEIGHT * 4)
    private var frameWaiting = false
    private var redrawWanted = false
    private var running = false
    private var renderThread: Thread? = null

    // Render thread only. Two bitmaps in turn: the hardware canvas uploads a bitmap
    // asynchronously, so the one just drawn must not be overwritten by the next frame.
    private val bitmaps = Array(2) {
        Bitmap.createBitmap(NativeCore.SCREEN_WIDTH, NativeCore.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    }
    private var shown = -1 // index of the bitmap holding the newest picture, -1 = none yet
    private var sizeRetries = 0 // redraws in a row whose buffer had the wrong size

    private val src = Rect(0, 0, NativeCore.SCREEN_WIDTH, NativeCore.SCREEN_HEIGHT)
    private val dst = Rect()

    // No bitmap filtering: GB pixels must stay crisp. (Since Android 9 a new Paint
    // filters bitmaps by default, which blurs pixel edges into grey bands; on e-ink that
    // also means many more pixels change, and get dithered, on every frame.)
    private val paint = Paint().apply { isFilterBitmap = false }

    init {
        holder.addCallback(this)
    }

    /** Emulation thread: hands over the newest picture (RGBA, 160x144). Never waits for the display. */
    fun present(video: ByteBuffer) {
        frameLock.withLock {
            video.rewind()
            staging.clear()
            staging.put(video)
            frameWaiting = true
            frameCond.signal()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frameLock.withLock { running = true }
        renderThread = Thread(Runnable { renderLoop() }, "paperboy-display").also { it.start() }
    }

    override fun onDetachedFromWindow() {
        frameLock.withLock {
            running = false
            frameCond.signal()
        }
        renderThread?.join(1000)
        renderThread = null
        super.onDetachedFromWindow()
    }

    private fun requestRedraw() {
        frameLock.withLock {
            redrawWanted = true
            frameCond.signal()
        }
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (true) {
            frameLock.withLock {
                while (running && !frameWaiting && !redrawWanted) frameCond.await()
                if (!running) return
                if (frameWaiting) {
                    shown = (shown + 1) % bitmaps.size
                    staging.rewind()
                    bitmaps[shown].copyPixelsFromBuffer(staging)
                    frameWaiting = false
                }
                redrawWanted = false
            }
            if (shown >= 0 && !draw(bitmaps[shown]) && sizeRetries < 30) {
                // Right after a resize (e.g. rotation) the surface can still hand out buffers of
                // the old size for a moment; what is drawn into one would show cropped or with
                // black bands. Try again shortly (for up to about a second) until the new size arrives.
                sizeRetries++
                Thread.sleep(30)
                frameLock.withLock { redrawWanted = true }
            }
        }
    }

    /** Returns false if the buffer didn't have the surface's current size yet. */
    private fun draw(bitmap: Bitmap): Boolean {
        synchronized(drawLock) {
            if (!surfaceReady) return true
            val canvas = lockCanvasCompat() ?: return true
            val sizeOk = surfaceW == 0 || (canvas.width == surfaceW && canvas.height == surfaceH)
            if (sizeOk) sizeRetries = 0
            if (logDraws > 0) {
                logDraws--
                Log.i(CrashLog.TAG, "Draw: canvas ${canvas.width}x${canvas.height}, surface ${surfaceW}x$surfaceH, hw=${canvas.isHardwareAccelerated}")
            }
            try {
                dst.set(0, 0, canvas.width, canvas.height)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, src, dst, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            return sizeOk
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
        Log.i(CrashLog.TAG, "Surface created")
        synchronized(drawLock) { surfaceReady = true }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(drawLock) {
            surfaceReady = true
            surfaceW = width
            surfaceH = height
            logDraws = 3
        }
        Log.i(CrashLog.TAG, "Surface changed: ${width}x$height, view ${this.width}x${this.height}")
        requestRedraw() // repaint at the new size even while paused
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        Log.i(CrashLog.TAG, "Surface destroyed")
        synchronized(drawLock) { surfaceReady = false }
    }
}
