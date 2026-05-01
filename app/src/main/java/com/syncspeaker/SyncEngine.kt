package com.syncspeaker

/**
 * Cristian/NTP style clock sync.
 *
 * Slave wants to know: master_nanos = local_nanos + offset
 * (So to play at master_nanos = X, schedule local_nanos = X - offset.)
 *
 * Procedure (per round):
 *   t1 = slave nanoTime when sending PING
 *   t2 = master nanoTime when receiving PING
 *   t3 = master nanoTime when sending PONG
 *   t4 = slave nanoTime when receiving PONG
 *
 *   offset (master - slave) ≈ ((t2 - t1) + (t3 - t4)) / 2
 *   rtt = (t4 - t1) - (t3 - t2)
 *
 * Run many rounds, pick the offset from the round with smallest RTT
 * (lowest network jitter). Tested to give 5-20 ms sync on typical WiFi.
 */
class SyncEngine {
    private data class Sample(val offsetNanos: Long, val rttNanos: Long)
    private val samples = mutableListOf<Sample>()

    @Volatile var bestOffsetNanos: Long = 0L
        private set
    @Volatile var bestRttNanos: Long = Long.MAX_VALUE
        private set
    @Volatile var sampleCount: Int = 0
        private set

    /** Add one round of clock samples. */
    @Synchronized
    fun addSample(t1: Long, t2: Long, t3: Long, t4: Long) {
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        val rtt = (t4 - t1) - (t3 - t2)
        samples.add(Sample(offset, rtt))
        sampleCount = samples.size
        // Keep best by RTT
        if (rtt < bestRttNanos) {
            bestRttNanos = rtt
            bestOffsetNanos = offset
        }
    }

    /** Slave-side: convert master nanoTime to local nanoTime. */
    fun localNanosForMasterNanos(masterNanos: Long): Long =
        masterNanos - bestOffsetNanos

    @Synchronized
    fun reset() {
        samples.clear()
        bestOffsetNanos = 0
        bestRttNanos = Long.MAX_VALUE
        sampleCount = 0
    }
}
