package com.vougadev.shell.tracking

/**
 * Which position fix is worth sending. Times are `elapsedRealtimeNanos`, the monotonic clock
 * a `Location` is stamped with, so a wall-clock change never makes an old fix look new.
 */
object FixPolicy {

    /** A fix older than this is not the person's position any more, and is not sent. */
    const val MAX_AGE_NANOS = 2L * 60 * 1_000_000_000

    /** Within this window a more accurate fix beats a newer, vaguer one. */
    private const val ACCURACY_WINDOW_NANOS = 30L * 1_000_000_000

    fun isFresh(fixNanos: Long, nowNanos: Long): Boolean = nowNanos - fixNanos in 0..MAX_AGE_NANOS

    /**
     * Whether [candidate] replaces [current]. A newer fix wins, except that a network fix
     * arriving seconds after a GPS fix must not throw away the GPS fix's accuracy.
     * Accuracy is the radius in metres; null means the provider did not say.
     */
    fun isBetter(candidateNanos: Long, candidateAccuracy: Float?, currentNanos: Long?, currentAccuracy: Float?): Boolean {
        if (currentNanos == null) return true
        if (candidateNanos <= currentNanos) return false
        if (candidateNanos - currentNanos > ACCURACY_WINDOW_NANOS) return true
        val candidate = candidateAccuracy ?: Float.MAX_VALUE
        val current = currentAccuracy ?: Float.MAX_VALUE
        return candidate <= current
    }
}
