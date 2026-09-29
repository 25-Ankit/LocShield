package locshield

import locshield.model.BackgroundMode
import locshield.model.BackgroundPolicy
import locshield.model.Decision
import locshield.model.ReasonCode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.policy.PolicyResolver
import locshield.policy.ResolutionOutcome
import locshield.policy.TransactionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Effective-policy resolution tests (Doc 13 Table 5, RES-001..RES-007) plus
 * generation tests (Doc 13 Table 17, RES-020..RES-024).
 */
class ResolutionTests {
    private fun resolve(
        rig: Fixtures.Rig,
        pkg: String = "com.example.app",
        ceiling: locshield.model.AndroidAuthorization = Fixtures.preciseCeiling(),
        nowWall: Long = 2_000L,
    ): ResolutionOutcome =
        rig.resolver.resolve(Fixtures.identity(pkg = pkg), Fixtures.request(Fixtures.identity(pkg = pkg)), ceiling, nowWall, 0L)

    @Test
    fun `RES-001 denied ceiling plus EXACT yields DENY`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val out = resolve(rig, ceiling = Fixtures.deniedCeiling())
        assertTrue(out is ResolutionOutcome.Denied)
        assertEquals(ReasonCode.ANDROID_AUTHORIZATION_DENIED, (out as ResolutionOutcome.Denied).reason)
    }

    @Test
    fun `RES-002 approximate ceiling plus EXACT never exceeds approximate`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val out = resolve(rig, ceiling = Fixtures.approximateCeiling())
        assertTrue(out is ResolutionOutcome.Effective)
        val eff = (out as ResolutionOutcome.Effective).policy
        // Must be degraded to a coarse grid, never EXACT/ALLOW-capable.
        assertTrue(eff.spatial.mode != SpatialMode.EXACT && eff.spatial.mode != SpatialMode.ANDROID_CEILING)
        assertEquals(SpatialMode.GRID, eff.spatial.mode)
    }

    @Test
    fun `RES-003 approximate ceiling plus CITY stays CITY`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val out = resolve(rig, ceiling = Fixtures.approximateCeiling())
        assertTrue(out is ResolutionOutcome.Effective)
        assertEquals(SpatialMode.CITY, (out as ResolutionOutcome.Effective).policy.spatial.mode)
    }

    @Test
    fun `RES-004 precise ceiling plus CITY stays CITY`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val out = resolve(rig, ceiling = Fixtures.preciseCeiling())
        assertTrue(out is ResolutionOutcome.Effective)
        assertEquals(SpatialMode.CITY, (out as ResolutionOutcome.Effective).policy.spatial.mode)
    }

    @Test
    fun `RES-005 precise ceiling plus EXACT stays EXACT-capable`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val out = resolve(rig, ceiling = Fixtures.preciseCeiling())
        assertTrue(out is ResolutionOutcome.Effective)
        assertEquals(SpatialMode.EXACT, (out as ResolutionOutcome.Effective).policy.spatial.mode)
    }

    @Test
    fun `RES-006 background denied by Android resolves but engine denies in background`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val ceiling = Fixtures.preciseCeiling(backgroundAllowed = false)
        val id = Fixtures.identity()
        val out = rig.resolver.resolve(id, Fixtures.request(id), ceiling, 2_000L, 0L)
        assertTrue(out is ResolutionOutcome.Effective)
        val decision = rig.engine.evaluate(
            id,
            Fixtures.request(id, foreground = false, bgPermission = true),
            (out as ResolutionOutcome.Effective).policy,
            Fixtures.locationCtx(),
            0L,
        )
        assertEquals(Decision.DENY, decision.decision)
        assertEquals(ReasonCode.BACKGROUND_DENIED, decision.reasonCode)
    }

    @Test
    fun `RES-007 background denied by policy denies in background`() {
        val rig = Fixtures.rig(Fixtures.backgroundDenyPolicy())
        val id = Fixtures.identity()
        val out = rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
        assertTrue(out is ResolutionOutcome.Effective)
        val decision = rig.engine.evaluate(
            id,
            Fixtures.request(id, foreground = false, bgPermission = true),
            (out as ResolutionOutcome.Effective).policy,
            Fixtures.locationCtx(),
            0L,
        )
        assertEquals(Decision.DENY, decision.decision)
    }

    @Test
    fun `missing policy resolves to explicit versioned default, never unlimited`() {
        val rig = Fixtures.rig()
        val out = resolve(rig, pkg = "com.unknown.app")
        assertTrue(out is ResolutionOutcome.Effective)
        // Default preserves the ceiling; it must not be DENY and must not invent data.
        val eff = (out as ResolutionOutcome.Effective).policy
        assertEquals(SpatialMode.ANDROID_CEILING, eff.spatial.mode)
    }

    @Test
    fun `disabled policy resolves to DENY`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy().copy(enabled = false))
        val out = resolve(rig)
        assertTrue(out is ResolutionOutcome.Denied)
        assertEquals(ReasonCode.POLICY_DISABLED, (out as ResolutionOutcome.Denied).reason)
    }

    @Test
    fun `expired policy resolves to safe fallback DENY`() {
        val rig = Fixtures.rig(
            Fixtures.exactPolicy().copy(createdAtWallMs = 1_000L, expiresAtWallMs = 1_500L),
        )
        val out = resolve(rig, nowWall = 2_000L)
        assertTrue(out is ResolutionOutcome.Denied)
        assertEquals(ReasonCode.POLICY_EXPIRED, (out as ResolutionOutcome.Denied).reason)
    }

    @Test
    fun `unexpired policy still effective`() {
        val rig = Fixtures.rig(
            Fixtures.exactPolicy().copy(createdAtWallMs = 1_000L, expiresAtWallMs = 9_999_999L),
        )
        assertTrue(resolve(rig, nowWall = 2_000L) is ResolutionOutcome.Effective)
    }

    @Test
    fun `RES-020 initial generation is defined`() {
        val store = locshield.policy.InMemoryPolicyStore()
        assertEquals(0L, store.getGeneration(0))
    }

    @Test
    fun `RES-021 successful update increments generation`() {
        val store = locshield.policy.InMemoryPolicyStore()
        val r1 = store.put(Fixtures.exactPolicy())
        assertTrue(r1 is TransactionResult.Committed && (r1 as TransactionResult.Committed).generation == 1L)
        val r2 = store.put(Fixtures.cityPolicy())
        assertTrue(r2 is TransactionResult.Committed && (r2 as TransactionResult.Committed).generation == 2L)
    }

    @Test
    fun `RES-022 rejected update leaves generation unchanged`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.exactPolicy())
        val before = store.getGeneration(0)
        val bad = store.put(Fixtures.radiusPolicy(-5.0))
        assertTrue(bad is TransactionResult.Rejected)
        assertEquals(before, store.getGeneration(0))
    }

    @Test
    fun `RES-023 concurrent readers observe complete snapshots only`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.exactPolicy())
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val readers = (0 until 8).map {
            Thread {
                while (!stop.get()) {
                    try {
                        val g = store.getGeneration(0)
                        val p = store.get(
                            locshield.model.ApplicationSelector("com.example.app", 0),
                        )
                        // Generation > 0 implies the policy must be visible atomically.
                        if (g > 0 && p == null) errors.incrementAndGet()
                    } catch (_: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
        }
        readers.forEach { it.start() }
        repeat(200) {
            store.put(if (it % 2 == 0) Fixtures.cityPolicy() else Fixtures.exactPolicy())
        }
        stop.set(true)
        readers.forEach { it.join(5_000) }
        assertEquals(0, errors.get())
    }

    @Test
    fun `RES-024 post-commit delivery uses the new generation`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        val first = rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
        assertTrue(first is ResolutionOutcome.Effective)
        assertEquals(1L, (first as ResolutionOutcome.Effective).generation)
        rig.store.put(Fixtures.cityPolicy())
        val second = rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
        assertTrue(second is ResolutionOutcome.Effective)
        assertEquals(2L, (second as ResolutionOutcome.Effective).generation)
        assertEquals(SpatialMode.CITY, (second as ResolutionOutcome.Effective).policy.spatial.mode)
    }

    @Test
    fun `user default applies when no explicit policy exists`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.putDefault(0, Fixtures.cityPolicy(pkg = "default", user = 0))
        val resolver = PolicyResolver(store)
        val out = resolver.resolve(
            Fixtures.identity(pkg = "com.other.app"),
            Fixtures.request(Fixtures.identity(pkg = "com.other.app")),
            Fixtures.preciseCeiling(),
            2_000L,
            0L,
        )
        assertTrue(out is ResolutionOutcome.Effective)
        assertEquals(SpatialMode.CITY, (out as ResolutionOutcome.Effective).policy.spatial.mode)
    }

    @Test
    fun `explicit policy wins over user default`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.putDefault(0, Fixtures.cityPolicy(pkg = "default", user = 0))
        store.put(Fixtures.exactPolicy())
        val resolver = PolicyResolver(store)
        val out = resolver.resolve(Fixtures.identity(), Fixtures.request(), Fixtures.preciseCeiling(), 2_000L, 0L)
        assertTrue(out is ResolutionOutcome.Effective)
        assertEquals(SpatialMode.EXACT, (out as ResolutionOutcome.Effective).policy.spatial.mode)
    }

    @Test
    fun `background RESTRICT degrades to at least the prototype floor`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(
            Fixtures.basePolicy().copy(background = BackgroundPolicy(BackgroundMode.RESTRICT)),
        )
        val resolver = PolicyResolver(store)
        val id = Fixtures.identity()
        val out = resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
        assertTrue(out is ResolutionOutcome.Effective)
        val eff = (out as ResolutionOutcome.Effective).policy
        val (rs, rt) = resolver.applyBackgroundRestriction(eff.spatial, eff.temporal, eff.background)
        // EXACT must be pulled down to GRID >= 1000 m; REALTIME pulled to MIN_INTERVAL.
        assertEquals(SpatialMode.GRID, rs.mode)
        assertTrue((rs.gridMeters ?: 0.0) >= 1_000.0)
        assertTrue(
            rt.mode == locshield.model.TemporalMode.MIN_INTERVAL &&
                (rt.minimumIntervalMs ?: 0L) >= 15L * 60L * 1_000L,
        )
    }
}
