package org.futo.inputmethod.latin.dictation.audio

/**
 * Bounded byte ring buffer with absolute offsets. Audio written here can be replayed after a
 * reconnect from any absolute offset still inside the window. Memory is fixed at [capacity].
 */
class PcmRingBuffer(val capacity: Int) {
    private val buf = ByteArray(capacity)
    /** Total bytes ever written; the next write lands at this absolute offset. */
    @Volatile var totalWritten: Long = 0
        private set

    /** Oldest absolute offset still available. */
    val oldestAvailable: Long get() = maxOf(0L, totalWritten - capacity)

    @Synchronized
    fun write(src: ByteArray, len: Int) {
        var off = 0
        var remaining = len
        if (remaining > capacity) { off = remaining - capacity; remaining = capacity }
        while (remaining > 0) {
            val pos = (totalWritten % capacity).toInt()
            val n = minOf(remaining, capacity - pos)
            System.arraycopy(src, off, buf, pos, n)
            totalWritten += n; off += n; remaining -= n
        }
    }

    /**
     * Copies up to [maxLen] bytes starting at absolute [from]. If [from] is older than the
     * window, reading starts at [oldestAvailable]. Returns (startOffsetActuallyUsed, bytes).
     */
    @Synchronized
    fun read(from: Long, maxLen: Int): Pair<Long, ByteArray> {
        val start = from.coerceIn(oldestAvailable, totalWritten)
        val n = minOf(maxLen.toLong(), totalWritten - start).toInt()
        val out = ByteArray(n)
        var copied = 0
        while (copied < n) {
            val pos = ((start + copied) % capacity).toInt()
            val chunk = minOf(n - copied, capacity - pos)
            System.arraycopy(buf, pos, out, copied, chunk)
            copied += chunk
        }
        return start to out
    }
}
