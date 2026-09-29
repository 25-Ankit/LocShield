package locshield

import locshield.metadata.MetadataSanitizer
import locshield.model.AndroidAuthorization
import locshield.model.AppPolicy
import locshield.model.BackgroundMode
import locshield.model.BackgroundPolicy
import locshield.model.Decision
import locshield.model.FieldHandling
import locshield.model.MetadataPolicy
import locshield.model.RandomizationPolicy
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.policy.AuthorizationIntersecter
import locshield.policy.PolicyValidator
import locshield.policy.Restriction
import locshield.policy.TransactionResult
import locshield.spatial.TransformationEngine
import locshield.spatial.TransformOutcome
import locshield.model.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Property-based invariant verification (Doc 13 section 22, P1..P9).
 * Implemented as seeded deterministic sweeps ("or equivalent", Doc 13 Table 2):
 * fixed seeds make every run reproducible and every failure re-runnable.
 */
class PropertyTests {
    private val seeds = longArrayOf(1L, 7L, 42L, 1234L, 999983L)

    private fun randomSpatial(r: Random): SpatialPolicy =
        when (r.nextInt(6)) {
            0 -> SpatialPolicy(SpatialMode.EXACT)
            1 -> SpatialPolicy(SpatialMode.DENY)
            2 -> SpatialPolicy(SpatialMode.CITY)
            3 -> SpatialPolicy(SpatialMode.GRID, gridMeters = doubleArrayOf(10.0, 500.0, 5_000.0)[r.nextInt(3)])
            4 -> SpatialPolicy(SpatialMode.RADIUS, radiusMeters = doubleArrayOf(50.0, 1_000.0, 20_000.0)[r.nextInt(3)])
            else -> SpatialPolicy(SpatialMode.ANDROID_CEILING)
        }

    private fun randomCeiling(r: Random): AndroidAuthorization =
        when (r.nextInt(4)) {
            0 -> AndroidAuthorization.denied()
            1 -> AndroidAuthorization(locationAllowed = true, approximateOnly = true, backgroundAllowed = r.nextBoolean())
            2 -> AndroidAuthorization(locationAllowed = true, approximateOnly = false, backgroundAllowed = true)
            else -> AndroidAuthorization(locationAllowed = true, approximateOnly = false, backgroundAllowed = false)
        }

    @Test
    fun `P1 effective precision never exceeds Android ceiling`() {
        for (seed in seeds) {
            val r = Random(seed)
            repeat(200) {
                val ceiling = randomCeiling(r)
                val policy = Fixtures.basePolicy(spatial = randomSpatial(r))
                val eff = AuthorizationIntersecter.intersectSpatial(policy.spatial, ceiling)
                if (ceiling.approximateOnly) {
                    assertTrue(
                        eff.mode != SpatialMode.EXACT && eff.mode != SpatialMode.ANDROID_CEILING,
                        "seed=$seed case=$it: $eff under $ceiling",
                    )
                }
            }
        }
    }

    @Test
    fun `P2 effective precision never exceeds LocShield policy`() {
        for (seed in seeds) {
            val r = Random(seed)
            repeat(200) {
                val ceiling = randomCeiling(r)
                val policy = Fixtures.basePolicy(spatial = randomSpatial(r))
                val eff = AuthorizationIntersecter.intersectSpatial(policy.spatial, ceiling)
                // Intersection only preserves or degrades: characteristic scale grows.
                assertTrue(
                    Restriction.spatialScaleMeters(eff) >= Restriction.spatialScaleMeters(policy.spatial),
                    "seed=$seed case=$it: $eff vs ${policy.spatial}",
                )
            }
        }
    }

    @Test
    fun `P3 adding a restriction cannot increase permission`() {
        val rank = mapOf(
            Decision.DENY to 0, Decision.FAIL_CLOSED to 0, Decision.THROTTLE to 1,
            Decision.TRANSFORM to 2, Decision.ALLOW to 3,
        )
        val rig = Fixtures.rig(
            Fixtures.exactPolicy(pkg = "com.loose"),
            Fixtures.cityPolicy(pkg = "com.strict"),
            Fixtures.denyPolicy(pkg = "com.deny"),
        )
        for (pkg in listOf("com.loose", "com.strict", "com.deny")) {
            val id = Fixtures.identity(pkg = pkg)
            val d = rig.engine.resolveAndEvaluate(
                id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
            )
        }
        val loose = rig.engine.resolveAndEvaluate(
            Fixtures.identity(pkg = "com.loose"), Fixtures.request(Fixtures.identity(pkg = "com.loose")),
            Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
        )
        val strict = rig.engine.resolveAndEvaluate(
            Fixtures.identity(pkg = "com.strict"), Fixtures.request(Fixtures.identity(pkg = "com.strict")),
            Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
        )
        val deny = rig.engine.resolveAndEvaluate(
            Fixtures.identity(pkg = "com.deny"), Fixtures.request(Fixtures.identity(pkg = "com.deny")),
            Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
        )
        assertTrue(rank.getValue(strict.decision) <= rank.getValue(loose.decision))
        assertTrue(rank.getValue(deny.decision) <= rank.getValue(strict.decision))
    }

    @Test
    fun `P4 invalid policy never yields ALLOW`() {
        for (seed in seeds) {
            val r = Random(seed)
            repeat(200) {
                val bad = when (r.nextInt(5)) {
                    0 -> Fixtures.radiusPolicy(-r.nextDouble() * 1000.0 - 0.5)
                    1 -> Fixtures.minIntervalPolicy(-r.nextInt(100000).toLong() - 1L)
                    2 -> Fixtures.exactPolicy().copy(schemaVersion = 100 + r.nextInt(100))
                    3 -> Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.GRID))
                    else -> Fixtures.basePolicy().copy(
                        temporal = TemporalPolicy(TemporalMode.RATE_LIMIT, maxDeliveriesPerWindow = 0, windowMs = 1_000L),
                    )
                }
                assertTrue(
                    PolicyValidator.validate(bad) is ValidationResult.Rejected,
                    "seed=$seed case=$it unexpectedly accepted: $bad",
                )
            }
        }
    }

    @Test
    fun `P5 throttled state implies no delivery and stable suppression`() {
        for (seed in seeds) {
            val intervalMs = longArrayOf(1L, 1_000L, 3_600_000L)[(seed % 3).toInt()]
            val rig = Fixtures.rig(Fixtures.minIntervalPolicy(intervalMs))
            val id = Fixtures.identity()
            val eff = (rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
                as locshield.policy.ResolutionOutcome.Effective).policy
            rig.engine.temporal.recordDelivery(id, eff.temporal, eff.policyGeneration, 0L)
            val t = rig.engine.evaluate(id, Fixtures.request(id), eff, Fixtures.locationCtx(atNanos = 1L), 1L)
            assertEquals(Decision.THROTTLE, t.decision)
            // Still suppressed without any record: no delivery happened.
            val t2 = rig.engine.evaluate(id, Fixtures.request(id), eff, Fixtures.locationCtx(atNanos = 2L), 2L)
            assertEquals(Decision.THROTTLE, t2.decision)
        }
    }

    @Test
    fun `P6 coarse spatial implies no precise metadata`() {
        for (seed in seeds) {
            val r = Random(seed)
            val spatials = listOf(
                SpatialPolicy(SpatialMode.GRID, gridMeters = 1_000.0),
                SpatialPolicy(SpatialMode.RADIUS, radiusMeters = 2_000.0),
                SpatialPolicy(SpatialMode.CITY),
            )
            repeat(100) { i ->
                val spatial = spatials[(r.nextInt(spatials.size) + i) % spatials.size]
                val raw = Fixtures.sample(
                    lat = -80.0 + r.nextDouble() * 160.0,
                    lon = -179.0 + r.nextDouble() * 358.0,
                    accuracy = r.nextFloat() * 50f,
                )
                val transformed = when (
                    val t = TransformationEngine.transform(
                        raw, spatial, RandomizationPolicy(enabled = false), Fixtures.transformCtx(),
                    )
                ) {
                    is TransformOutcome.Ok -> t.location
                    is TransformOutcome.Failed -> return@repeat // invalid sample; transform gate holds
                }
                val sanitized = when (
                    val s = MetadataSanitizer.sanitize(transformed, spatial, MetadataPolicy())
                ) {
                    is MetadataSanitizer.SanitizeOutcome.Ok -> s.location
                    is MetadataSanitizer.SanitizeOutcome.Failed -> error("sanitize failed for $spatial")
                }
                assertTrue(
                    sanitized.accuracyMeters >= MetadataSanitizer.accuracyFloorMeters(spatial),
                    "seed=$seed case=$i $spatial acc=${sanitized.accuracyMeters}",
                )
            }
        }
    }

    @Test
    fun `P7 same inputs plus same generation imply same decision`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy(), Fixtures.gridPolicy(250.0, pkg = "com.g"))
        for (pkg in listOf("com.example.app", "com.g")) {
            val id = Fixtures.identity(pkg = pkg)
            val a = rig.engine.resolveAndEvaluate(
                id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
            )
            val b = rig.engine.resolveAndEvaluate(
                id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
            )
            assertEquals(a, b, pkg)
        }
    }

    @Test
    fun `P8 successful commit increases generation`() {
        val store = locshield.policy.InMemoryPolicyStore()
        var last = store.getGeneration(0)
        val cases: List<AppPolicy> = listOf(
            Fixtures.exactPolicy(), Fixtures.cityPolicy(), Fixtures.gridPolicy(100.0),
            Fixtures.minIntervalPolicy(5_000L), Fixtures.backgroundDenyPolicy(),
        )
        for (p in cases) {
            val r = store.put(p)
            assertTrue(r is TransactionResult.Committed)
            val g = (r as TransactionResult.Committed).generation
            assertTrue(g > last, "generation must grow: $g <= $last")
            last = g
        }
    }

    @Test
    fun `P9 rejected commit leaves generation unchanged`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.exactPolicy())
        val before = store.getGeneration(0)
        val badOnes: List<AppPolicy> = listOf(
            Fixtures.radiusPolicy(-1.0),
            Fixtures.gridPolicy(Double.NaN),
            Fixtures.minIntervalPolicy(-10L),
            Fixtures.exactPolicy().copy(schemaVersion = 77),
            Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.RADIUS)),
        )
        for (bad in badOnes) {
            assertTrue(store.put(bad) is TransactionResult.Rejected)
            assertEquals(before, store.getGeneration(0))
        }
    }

    @Test
    fun `background restriction is monotone across modes`() {
        // RESTRICT/INHERIT/ALLOW never widen beyond the ceiling-checked base.
        val rig = Fixtures.rig(
            Fixtures.basePolicy(pkg = "com.i").copy(background = BackgroundPolicy(BackgroundMode.INHERIT_ANDROID)),
            Fixtures.basePolicy(pkg = "com.r").copy(background = BackgroundPolicy(BackgroundMode.RESTRICT)),
            Fixtures.basePolicy(pkg = "com.d").copy(background = BackgroundPolicy(BackgroundMode.DENY)),
        )
        fun decideBg(pkg: String): Decision {
            val id = Fixtures.identity(pkg = pkg)
            return rig.engine.resolveAndEvaluate(
                id,
                Fixtures.request(id, foreground = false, bgPermission = true),
                Fixtures.preciseCeiling(backgroundAllowed = true),
                Fixtures.locationCtx(),
                2_000L,
            ).decision
        }
        // RESTRICT on EXACT/REALTIME must degrade to TRANSFORM (GRID floor applies).
        assertEquals(Decision.TRANSFORM, decideBg("com.r"))
        assertEquals(Decision.DENY, decideBg("com.d"))
        // INHERIT with platform grant stays ALLOW.
        assertEquals(Decision.ALLOW, decideBg("com.i"))
    }

    @Test
    fun `metadata denial fields are stable across samples`() {
        val strict = Fixtures.strictMetadata()
        val r = Random(5L)
        repeat(100) {
            val raw = Fixtures.sample(
                lat = -80.0 + r.nextDouble() * 160.0,
                lon = -179.0 + r.nextDouble() * 358.0,
                speed = r.nextFloat() * 40f,
                bearing = r.nextFloat() * 360f,
            )
            val out = MetadataSanitizer.sanitize(raw, SpatialPolicy(SpatialMode.EXACT), strict)
            assertTrue(out is MetadataSanitizer.SanitizeOutcome.Ok)
            val l = (out as MetadataSanitizer.SanitizeOutcome.Ok).location
            assertEquals(null, l.speedMps)
            assertEquals(null, l.bearingDeg)
            assertEquals(null, l.altitudeMeters)
            assertTrue(l.extras.isEmpty())
        }
    }
}
