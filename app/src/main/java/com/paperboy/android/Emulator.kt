package com.paperboy.android

import android.graphics.Bitmap
import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs one game on a dedicated thread.
 *
 * Every call into [NativeCore] happens on that thread (the core is not thread-safe).
 * Other threads talk to it through [buttons], [soundEnabled], [displayDivisor] and a
 * small command queue.
 *
 * Pacing: with sound on, the blocking [AudioOutput] write is the clock. With sound
 * off (or if audio is unavailable) a timer paces to 59.73 fps.
 *
 * E-ink: the game always emulates every frame at full speed, but the picture is only
 * handed to the display every [displayDivisor]-th frame, and only if it actually
 * changed (the native layer compares frames). A static title screen therefore costs
 * the panel nothing.
 */
class Emulator(
    private val rom: ByteArray,
    private val sramFile: File,
    private val stateFile: File,
    private val view: GameView,
    private val listener: Listener,
    private val initialPalette: IntArray,
    initialSound: Boolean,
    initialDivisor: Int,
) {
    /** Called from the emulation thread. */
    interface Listener {
        fun onMessage(text: String)
        fun onFatal(text: String)
    }

    /** Current joypad state (NativeCore.BTN_* bits). Safe to set from any thread. */
    @Volatile
    var buttons: Int = 0

    @Volatile
    var soundEnabled: Boolean = initialSound

    /** Present at most every Nth emulated frame (1 = 60 fps, 2 = 30 fps, ...). */
    @Volatile
    var displayDivisor: Int = initialDivisor.coerceAtLeast(1)

    @Volatile
    private var paused = true // starts paused; the activity calls resume()

    @Volatile
    private var stopRequested = false

    private val commands = ConcurrentLinkedQueue<Runnable>()
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private var thread: Thread? = null

    fun start() {
        val t = Thread(Runnable { threadMain() }, "paperboy-emulator")
        thread = t
        t.start()
    }

    /** Resumes emulation and repaints the current picture (a dialog or ghost-clear may have covered it). */
    fun resume() {
        post { NativeCore.nForceRedraw() }
        paused = false
        signal()
    }

    /**
     * Pauses emulation and waits (briefly) until battery RAM has been written to
     * disk, so nothing is lost if Android kills the process afterwards.
     */
    fun pause() {
        paused = true
        val t = thread
        if (t == null || !t.isAlive) return
        val done = CountDownLatch(1)
        commands.add(Runnable {
            try {
                flushSram()
            } finally {
                done.countDown()
            }
        })
        signal()
        done.await(1500, TimeUnit.MILLISECONDS)
    }

    fun setPalette(argb: IntArray) = post { NativeCore.nSetPalette(argb) }

    fun saveState() = post {
        val state = NativeCore.nStateExport()
        val ok = state != null && GameStore.writeAtomic(stateFile, state)
        flushSram()
        listener.onMessage(if (ok) "State saved" else "Couldn't save state")
    }

    fun loadState() = post {
        val data = GameStore.readFile(stateFile)
        when {
            data == null -> listener.onMessage("No saved state yet")
            NativeCore.nStateImport(data) -> listener.onMessage("State loaded")
            else -> listener.onMessage("Couldn't load state: ${NativeCore.nLastError()}")
        }
    }

    /** Stops the thread and waits for it, which also flushes battery RAM. */
    fun shutdown() {
        stopRequested = true
        signal()
        val t = thread ?: return
        try {
            t.join(3000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- internals -----------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        commands.add(Runnable { block() })
        signal()
    }

    private fun signal() {
        lock.withLock { wake.signalAll() }
    }

    private fun runCommands() {
        while (true) {
            val c = commands.poll() ?: break
            try {
                c.run()
            } catch (e: Exception) {
                listener.onMessage("Internal error: ${e.message}")
            }
        }
    }

    private fun nowSeconds(): Int = (System.currentTimeMillis() / 1000).toInt()

    private fun loadSram() {
        if (!NativeCore.nHasPersist()) return
        val data = GameStore.readFile(sramFile) ?: return
        if (!NativeCore.nPersistImport(data, nowSeconds())) {
            // Keep the unreadable file instead of silently overwriting it with a fresh, empty save.
            sramFile.renameTo(File(sramFile.path + ".bad"))
            listener.onMessage("Couldn't read the old save; kept a copy as .bad")
        }
    }

    private fun flushSram() {
        if (!NativeCore.nHasPersist() || !NativeCore.nPersistDirty()) return
        val data = NativeCore.nPersistExport(nowSeconds()) ?: return
        if (GameStore.writeAtomic(sramFile, data)) NativeCore.nPersistMarkClean()
    }

    private fun threadMain() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var audio: AudioOutput? = null
        try {
            if (!NativeCore.nInit(rom)) {
                listener.onFatal("Couldn't start this game: ${NativeCore.nLastError()}")
                return
            }
            loadSram()
            NativeCore.nSetPalette(initialPalette)

            val video = NativeCore.nVideoBuffer()
            val audioBuf = NativeCore.nAudioBuffer()
            // Three bitmaps in rotation: the hardware canvas uploads a bitmap asynchronously,
            // so the one just handed over must not be overwritten by the next frame.
            val bitmaps = Array(3) {
                Bitmap.createBitmap(NativeCore.SCREEN_WIDTH, NativeCore.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
            }
            var bitmapIndex = 0

            var audioActive = false
            var audioFailed = false
            var nextDeadline = System.nanoTime()
            var frameCounter = 0
            var framesSinceFlush = 0

            while (!stopRequested) {
                runCommands()

                if (paused) {
                    if (audioActive) {
                        audio?.pause()
                        audioActive = false
                    }
                    lock.withLock {
                        while (paused && !stopRequested && commands.isEmpty()) wake.await()
                    }
                    nextDeadline = System.nanoTime()
                    continue
                }

                // ---- audio device lifecycle ----
                val wantAudio = soundEnabled && !audioFailed
                if (wantAudio && audio == null) {
                    audio = AudioOutput.create()
                    if (audio == null) {
                        audioFailed = true
                        listener.onMessage("Audio unavailable; playing without sound")
                    }
                }
                val a = audio
                if (wantAudio && a != null && !audioActive) {
                    a.start()
                    audioActive = true
                } else if (!wantAudio && audioActive) {
                    a?.pause()
                    audioActive = false
                }
                val useAudio = wantAudio && a != null && audioActive

                // ---- one emulated frame ----
                NativeCore.nSetButtons(buttons)
                frameCounter++
                val tryPresent = frameCounter % displayDivisor == 0
                if (!NativeCore.nRunFrame(tryPresent, useAudio)) {
                    listener.onFatal("Emulation stopped: ${NativeCore.nLastError()}")
                    break
                }
                if (tryPresent && NativeCore.nFrameUpdated()) {
                    video.rewind() // copyPixelsFromBuffer advances the position
                    val bmp = bitmaps[bitmapIndex]
                    bitmapIndex = (bitmapIndex + 1) % bitmaps.size
                    bmp.copyPixelsFromBuffer(video)
                    view.present(bmp)
                }

                // ---- pacing ----
                if (a != null && useAudio) {
                    if (!a.writeFrame(audioBuf)) {
                        a.release()
                        audio = null
                        audioActive = false
                        audioFailed = true
                        listener.onMessage("Audio output failed; continuing without sound")
                    }
                    nextDeadline = System.nanoTime()
                } else {
                    nextDeadline += NativeCore.FRAME_NANOS
                    while (true) {
                        val wait = nextDeadline - System.nanoTime()
                        if (wait <= 0) {
                            if (wait < -4 * NativeCore.FRAME_NANOS) nextDeadline = System.nanoTime()
                            break
                        }
                        LockSupport.parkNanos(wait)
                    }
                }

                // ---- autosave battery RAM about every 2 s, only when it changed ----
                if (++framesSinceFlush >= 120) {
                    framesSinceFlush = 0
                    flushSram()
                }
            }
        } catch (t: Throwable) {
            listener.onFatal("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try {
                runCommands()
                flushSram()
            } catch (e: Exception) {
                // best effort
            }
            NativeCore.nShutdown()
            audio?.release()
        }
    }
}
