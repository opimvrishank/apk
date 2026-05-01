package com.syncspeaker

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps MediaPlayer and starts it at a precise nanoTime target.
 *
 * Strategy: prepare ahead of time, post a Handler 5ms before target, then
 * busy-wait the final few hundred microseconds for accuracy.
 */
class SyncedPlayer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val current = AtomicReference<MediaPlayer?>(null)

    fun stop() {
        current.getAndSet(null)?.runCatching {
            if (isPlaying) stop()
            release()
        }
    }

    /**
     * Prepare and schedule.
     * @param file       audio file to play
     * @param targetLocalNanos local nanoTime at which playback should start
     * @param onError    called if anything goes wrong
     */
    fun playAt(file: File, targetLocalNanos: Long, onError: (Throwable) -> Unit = {}) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnErrorListener { _, what, extra ->
                onError(RuntimeException("MediaPlayer error $what/$extra"))
                true
            }
            mp.prepare()  // synchronous; OK off-main, but quick for local files
            current.set(mp)

            val now = System.nanoTime()
            val waitNanos = targetLocalNanos - now
            val waitMs = waitNanos / 1_000_000

            if (waitMs <= 5) {
                // Already there or close, start immediately
                tryStart(mp)
            } else {
                // Schedule 5ms early, then busy-wait the last bit for precision
                mainHandler.postDelayed({
                    val mpRef = current.get() ?: return@postDelayed
                    val target = targetLocalNanos
                    while (System.nanoTime() < target) {
                        // tight wait, max ~5ms — negligible
                    }
                    tryStart(mpRef)
                }, waitMs - 5)
            }
        } catch (t: Throwable) {
            onError(t)
            mp.runCatching { release() }
        }
    }

    private fun tryStart(mp: MediaPlayer) {
        try { mp.start() } catch (_: IllegalStateException) { /* released */ }
    }
}
