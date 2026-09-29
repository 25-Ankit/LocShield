package locshield.temporal

/**
 * Time abstraction (Doc 12 section 18, Doc 13 sections 3-4).
 *
 * Interval enforcement MUST use monotonic elapsed realtime so wall-clock changes
 * can never bypass frequency controls (TMP-006). Wall-clock time is used only for
 * policy expiration, through a separate explicit parameter, never through this
 * interface.
 */
interface Clock {
    /** Monotonic nanoseconds, e.g. `SystemClock.elapsedRealtimeNanos()`. */
    fun nowElapsedNanos(): Long
}

/** Production clock backed by a monotonic source (no wall clock). */
class SystemClock : Clock {
    override fun nowElapsedNanos(): Long = System.nanoTime()
}

/** Deterministic test clock (Doc 13 section 4: "injectable monotonic test clock"). */
class FakeClock(var nowNanos: Long = 0L) : Clock {
    override fun nowElapsedNanos(): Long = nowNanos

    fun advanceBy(deltaNanos: Long) {
        require(deltaNanos >= 0) { "Clock must be monotonic" }
        nowNanos = saturatingAdd(nowNanos, deltaNanos)
    }

    companion object {
        fun saturatingAdd(a: Long, b: Long): Long {
            if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE
            if (b < 0 && a < Long.MIN_VALUE - b) return Long.MIN_VALUE
            return a + b
        }
    }
}
