package com.paperboy.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer

/**
 * Streams the APU's output to an [AudioTrack].
 *
 * Blocking writes double as the emulator's clock: the emulation thread can only
 * write the next frame once the hardware has consumed enough of the previous ones,
 * so game speed stays locked to the audio device with no separate timer.
 */
class AudioOutput private constructor(private val track: AudioTrack) {
    private val silence = ByteBuffer.allocateDirect(FRAME_BYTES) // zero-filled

    /** Starts playback with two frames of silence queued so the first real frame doesn't underrun. */
    fun start() {
        track.play()
        writeFrame(silence)
        writeFrame(silence)
    }

    fun pause() {
        try {
            track.pause()
            track.flush()
        } catch (e: IllegalStateException) {
            // already released
        }
    }

    /** Blocks until there is room. Returns false if the audio device failed. */
    fun writeFrame(buf: ByteBuffer): Boolean {
        buf.clear() // AudioTrack.write(ByteBuffer) advances position; always start from 0
        return track.write(buf, FRAME_BYTES, AudioTrack.WRITE_BLOCKING) >= 0
    }

    fun release() {
        try {
            track.release()
        } catch (e: IllegalStateException) {
            // ignore
        }
    }

    companion object {
        /** Stereo, 16-bit. */
        const val FRAME_BYTES = NativeCore.AUDIO_FRAMES * 4

        fun create(): AudioOutput? = try {
            val format = AudioFormat.Builder()
                .setSampleRate(NativeCore.AUDIO_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                NativeCore.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            // ~4 frames (~67 ms): tight sync, but enough to ride out scheduler jitter and
            // the occasional slow e-ink present.
            val bufferBytes = maxOf(minBytes, FRAME_BYTES * 4)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                AudioOutput(track)
            } else {
                track.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
