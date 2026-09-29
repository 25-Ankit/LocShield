package locshield

import locshield.model.TemporalAction
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.temporal.FakeClock
import locshield.temporal.TemporalController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Temporal tests (Doc 13 Table 11, TMP-001..TMP-010; Doc 12 section 17).
 * All time is monotonic elapsed nanos via [FakeClock]; wall-clock games cannot
 * affect these tests by construction (TMP-006).
 */
class TemporalTests {
    private val id = Fixtures.identity()
    private val gen = 7L

    private fun nanos(ms: Long): Long = Fixtures.msToNanos(ms)

    @Test
    fun `TMP-001 first delivery allowed`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, 0L).action)
    }

    @Test
    fun `TMP-002 immediate second delivery throttled`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(1_000L)).action)
    }

    @Test
    fun `TMP-003 exactly at minimum interval allows (inclusive boundary)`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, nanos(60_000L)).action)
    }

    @Test
    fun `TMP-004 just before interval throttles`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(60_000L) - 1L).action)
    }

    @Test
    fun `TMP-005 just after interval allows`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, nanos(60_000L) + 1L).action)
    }

    @Test
    fun `TMP-006 wall clock moves do not affect monotonic intervals`() {
        // The controller only ever sees elapsed nanos; a FakeClock advancing
        // monotonically models "wall clock jumped backwards" as no-op.
        val clock = FakeClock(0L)
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, clock.nowElapsedNanos())
        clock.advanceBy(nanos(30_000L))
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, clock.nowElapsedNanos()).action)
        clock.advanceBy(nanos(30_000L))
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, clock.nowElapsedNanos()).action)
    }

    @Test
    fun `TMP-007 large elapsed time causes no overflow`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        // Far-future delivery: must allow, never corrupt state.
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, Long.MAX_VALUE - 1L).action)
        // And the gate still works afterwards.
        c.recordDelivery(id, p, gen, Long.MAX_VALUE - 1L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, Long.MAX_VALUE - 1L).action)
    }

    @Test
    fun `TMP-008 rate limit reached throttles`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.RATE_LIMIT, maxDeliveriesPerWindow = 2, windowMs = 60_000L)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, 0L).action)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, nanos(1_000L)).action)
        c.recordDelivery(id, p, gen, nanos(1_000L))
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(2_000L)).action)
    }

    @Test
    fun `TMP-009 window expiry allows delivery again`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.RATE_LIMIT, maxDeliveriesPerWindow = 1, windowMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(59_999L)).action)
        // At t=60s the t=0 event falls out of the (t-60s, t] window.
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, nanos(60_001L)).action)
    }

    @Test
    fun `TMP-010 policy generation change applies new intervals`() {
        val c = TemporalController()
        val strict = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 3_600_000L)
        c.recordDelivery(id, strict, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, strict, gen, nanos(1_000L)).action)
        // New generation with REALTIME: no stale suppression may survive.
        assertEquals(
            TemporalAction.PROCEED,
            c.evaluate(id, TemporalPolicy(TemporalMode.REALTIME), gen + 1, nanos(1_000L)).action,
        )
    }

    @Test
    fun `PERIODIC delivers on schedule only`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.PERIODIC, periodicIntervalMs = 60_000L)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, 0L).action)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(30_000L)).action)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, nanos(60_000L)).action)
    }

    @Test
    fun `ONE_SHOT allows exactly one delivery per generation`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.ONE_SHOT)
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, 0L).action)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(1_000_000L)).action)
        // New generation resets the one-shot.
        assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen + 1, nanos(1_000_000L)).action)
    }

    @Test
    fun `REALTIME always proceeds`() {
        val c = TemporalController()
        val p = TemporalPolicy(TemporalMode.REALTIME)
        repeat(5) {
            assertEquals(TemporalAction.PROCEED, c.evaluate(id, p, gen, it.toLong()).action)
        }
    }

    @Test
    fun `malformed temporal policy fails closed, never allows`() {
        val c = TemporalController()
        // Missing required params (would never pass validation; defense in depth).
        assertEquals(
            TemporalAction.SUPPRESS,
            c.evaluate(id, TemporalPolicy(TemporalMode.MIN_INTERVAL), gen, 0L).action,
        )
        assertEquals(
            TemporalAction.SUPPRESS,
            c.evaluate(id, TemporalPolicy(TemporalMode.RATE_LIMIT), gen, 0L).action,
        )
    }

    @Test
    fun `state is isolated per identity`() {
        val c = TemporalController()
        val other = Fixtures.identity(pkg = "com.other.app", uid = 20202)
        val p = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        c.recordDelivery(id, p, gen, 0L)
        assertEquals(TemporalAction.SUPPRESS, c.evaluate(id, p, gen, nanos(1_000L)).action)
        assertEquals(TemporalAction.PROCEED, c.evaluate(other, p, gen, nanos(1_000L)).action)
    }

    @Test
    fun `FakeClock is monotonic`() {
        val clock = FakeClock(100L)
        clock.advanceBy(50L)
        assertEquals(150L, clock.nowElapsedNanos())
        try {
            clock.advanceBy(-1L)
            error("expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
