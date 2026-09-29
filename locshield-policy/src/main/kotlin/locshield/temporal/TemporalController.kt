package locshield.temporal

import locshield.model.AppIdentity
import locshield.model.ReasonCode
import locshield.model.TemporalAction
import locshield.model.TemporalDecision
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.temporal.FakeClock.Companion.saturatingAdd
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporal delivery gate (Doc 07 C06, Doc 11 section 20, Doc 12 section 17).
 *
 * Pure delivery-time control: `evaluate` is side-effect free; the adapter records
 * an actual delivery via [recordDelivery]. State is strictly partitioned per
 * [AppIdentity] AND policy generation, so a policy change (TMP-010) or another
 * application's traffic (SEC-022) can never leak into this subject's gate.
 *
 * All interval math uses monotonic elapsed nanos with saturating arithmetic;
 * overflow resolves to SUPPRESS (fail closed, ERR-008), never to a delivery.
 */
class TemporalController {
    private data class SubjectState(
        val generation: Long,
        val lastDeliveryNanos: Long? = null,
        val nextAllowedNanos: Long? = null,
        val window: ArrayDeque<Long> = ArrayDeque(),
        val oneShotConsumed: Boolean = false,
    )

    private val states = ConcurrentHashMap<AppIdentity, SubjectState>()
    /** Stable per-subject monitors. Lock objects are never replaced (unlike state). */
    private val locks = ConcurrentHashMap<AppIdentity, Any>()

    fun evaluate(
        identity: AppIdentity,
        policy: TemporalPolicy,
        generation: Long,
        nowNanos: Long,
    ): TemporalDecision {
        // State read and window counting happen under the subject lock so concurrent
        // deliveries for one identity observe a consistent view.
        return synchronizedLock(identity) {
            val state = currentState(identity, generation)
            evaluateLocked(policy, state, nowNanos)
        }
    }

    private fun evaluateLocked(
        policy: TemporalPolicy,
        state: SubjectState,
        nowNanos: Long,
    ): TemporalDecision {
        return when (policy.mode) {
            TemporalMode.REALTIME -> TemporalDecision(TemporalAction.PROCEED, null)
            TemporalMode.MIN_INTERVAL -> {
                val intervalMs = policy.minimumIntervalMs
                    ?: return closed("MIN_INTERVAL without minimumIntervalMs")
                val intervalNanos = millisToNanosSaturated(intervalMs)
                val last = state.lastDeliveryNanos
                if (last == null || elapsedSince(last, nowNanos) >= intervalNanos) {
                    TemporalDecision(TemporalAction.PROCEED, null)
                } else {
                    TemporalDecision(TemporalAction.SUPPRESS, saturatingAdd(last, intervalNanos))
                }
            }
            TemporalMode.PERIODIC -> {
                val intervalMs = policy.periodicIntervalMs
                    ?: return closed("PERIODIC without periodicIntervalMs")
                val next = state.nextAllowedNanos
                if (next == null || nowNanos >= next) {
                    TemporalDecision(TemporalAction.PROCEED, null)
                } else {
                    TemporalDecision(TemporalAction.SUPPRESS, next)
                }
            }
            TemporalMode.RATE_LIMIT -> {
                val max = policy.maxDeliveriesPerWindow
                val windowMs = policy.windowMs
                if (max == null || max <= 0 || windowMs == null || windowMs <= 0) {
                    return closed("RATE_LIMIT without valid window parameters")
                }
                val windowNanos = millisToNanosSaturated(windowMs)
                val cutoff = if (windowNanos == Long.MAX_VALUE || nowNanos < windowNanos) {
                    // Window covers all history ever recorded.
                    Long.MIN_VALUE
                } else {
                    nowNanos - windowNanos
                }
                val count = state.window.count { it > cutoff }
                if (count >= max) {
                    TemporalDecision(TemporalAction.SUPPRESS, null)
                } else {
                    TemporalDecision(TemporalAction.PROCEED, null)
                }
            }
            TemporalMode.ONE_SHOT -> {
                if (state.oneShotConsumed) {
                    TemporalDecision(TemporalAction.SUPPRESS, null)
                } else {
                    TemporalDecision(TemporalAction.PROCEED, null)
                }
            }
        }
    }

    /**
     * Records an actual delivery. Must be called ONLY when the final decision for
     * this delivery was ALLOW/TRANSFORM; THROTTLE/DENY/FAIL_CLOSED never record.
     */
    fun recordDelivery(identity: AppIdentity, policy: TemporalPolicy, generation: Long, nowNanos: Long) {
        synchronizedLock(identity, generation) { state ->
            var next = state
            when (policy.mode) {
                TemporalMode.REALTIME -> next = next.copy(lastDeliveryNanos = nowNanos)
                TemporalMode.MIN_INTERVAL -> next = next.copy(lastDeliveryNanos = nowNanos)
                TemporalMode.PERIODIC -> {
                    val intervalNanos = millisToNanosSaturated(policy.periodicIntervalMs ?: 0L)
                    next = next.copy(
                        lastDeliveryNanos = nowNanos,
                        nextAllowedNanos = saturatingAdd(nowNanos, intervalNanos),
                    )
                }
                TemporalMode.RATE_LIMIT -> {
                    val windowNanos = millisToNanosSaturated(policy.windowMs ?: 0L)
                    val window = ArrayDeque(next.window)
                    window.addLast(nowNanos)
                    val cutoff = if (windowNanos == Long.MAX_VALUE) Long.MIN_VALUE else nowNanos - windowNanos
                    while (window.isNotEmpty() && window.first() <= cutoff) {
                        window.removeFirst()
                    }
                    next = next.copy(lastDeliveryNanos = nowNanos, window = window)
                }
                TemporalMode.ONE_SHOT -> next = next.copy(lastDeliveryNanos = nowNanos, oneShotConsumed = true)
            }
            next
        }
    }

    /** Test/adapter hook: forgets one subject's state (never another subject's). */
    fun resetSubject(identity: AppIdentity) {
        states.remove(identity)
    }

    // -- internals ----------------------------------------------------------

    private fun currentState(identity: AppIdentity, generation: Long): SubjectState {
        val existing = states[identity]
        if (existing != null && existing.generation == generation) {
            return existing
        }
        // New generation (or first use): start clean so a policy change can never
        // grant a previously restricted delivery, nor inherit stale suppression.
        val fresh = SubjectState(generation = generation)
        states[identity] = fresh
        return fresh
    }

    private inline fun <T> synchronizedLock(identity: AppIdentity, crossinline block: () -> T): T {
        val lock = locks.computeIfAbsent(identity) { Any() }
        synchronized(lock) {
            return block()
        }
    }

    private inline fun synchronizedLock(
        identity: AppIdentity,
        generation: Long,
        crossinline update: (SubjectState) -> SubjectState,
    ) {
        val lock = locks.computeIfAbsent(identity) { Any() }
        synchronized(lock) {
            val fresh = currentState(identity, generation)
            states[identity] = update(fresh)
        }
    }

    private fun closed(@Suppress("UNUSED_PARAMETER") why: String): TemporalDecision =
        TemporalDecision(TemporalAction.SUPPRESS, null)

    companion object {
        /** Fuzz/overflow oracle: temporal math must never corrupt state (ERR-008). */
        fun millisToNanosSaturated(ms: Long): Long {
            if (ms >= Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE
            if (ms <= Long.MIN_VALUE / 1_000_000L) return Long.MIN_VALUE
            return ms * 1_000_000L
        }

        private fun elapsedSince(earlier: Long, now: Long): Long =
            if (now >= earlier) {
                now - earlier
            } else {
                // Monotonic clock observed backwards (should not happen with Clock
                // implementations; fail closed by treating as zero elapsed).
                0L
            }

        /** Reason mapping helper for engine diagnostics. */
        fun failureReason(): ReasonCode = ReasonCode.TEMPORAL_STATE_INVALID
    }
}
