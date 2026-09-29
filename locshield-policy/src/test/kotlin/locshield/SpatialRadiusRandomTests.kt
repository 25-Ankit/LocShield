package locshield

import locshield.model.RandomizationPolicy
import locshield.model.SeedMode
import locshield.spatial.DeterministicRandomSource
import locshield.spatial.RadiusTransformer
import locshield.spatial.RandomizationTransformer
import locshield.spatial.TransformOutcome
import locshield.spatial.TransformationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Radius + randomization tests (Doc 13 Table 9, SP-010..SP-015; Table 16 FUZZ-020..024).
 */
class SpatialRadiusRandomTests {
    private fun okRadius(meters: Double): locshield.model.LocationSample {
        val out = RadiusTransformer.transform(Fixtures.sample(), meters)
        assertTrue(out is TransformOutcome.Ok, "Expected Ok, was $out")
        return (out as TransformOutcome.Ok).location
    }

    @Test
    fun `SP-010 valid radius satisfies documented geometry`() {
        val r = 1_000.0
        val out = okRadius(r)
        val d = locshield.spatial.CityTransformer.haversineMeters(
            Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON, out.latitude, out.longitude,
        )
        assertTrue(d <= r, "Representative must be within R (d=$d)")
        assertTrue(out.accuracyMeters >= r.toFloat())
        // Raw coordinate must not be returned: representative differs from input
        // unless the input coincides with a cell center (it does not for Mumbai/1km).
        assertTrue(out.latitude != Fixtures.MUMBAI_LAT || out.longitude != Fixtures.MUMBAI_LON)
    }

    @Test
    fun `SP-011 invalid radius rejected`() {
        assertTrue(RadiusTransformer.transform(Fixtures.sample(), 0.0) is TransformOutcome.Failed)
        assertTrue(RadiusTransformer.transform(Fixtures.sample(), -1.0) is TransformOutcome.Failed)
        assertTrue(RadiusTransformer.transform(Fixtures.sample(), Double.NaN) is TransformOutcome.Failed)
    }

    @Test
    fun `SP-012 stable seed is stable according to policy`() {
        val policy = RandomizationPolicy(
            enabled = true,
            radiusMeters = 500.0,
            stableForMs = 3_600_000L,
            seedMode = SeedMode.PERIOD_STABLE,
        )
        val id = Fixtures.identity()
        val t = 10_000_000_000_000L // fixed period
        val a = RandomizationTransformer.transform(
            Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(1L), t, identity = id),
        )
        val b = RandomizationTransformer.transform(
            Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(999L), t + 1_000L, identity = id),
        )
        assertTrue(a is TransformOutcome.Ok && b is TransformOutcome.Ok)
        // Same period + same identity -> same output even with different RNG instances.
        assertEquals((a as TransformOutcome.Ok).location, (b as TransformOutcome.Ok).location)
    }

    @Test
    fun `SP-013 per-delivery randomization can differ across deliveries`() {
        val policy = RandomizationPolicy(enabled = true, radiusMeters = 500.0, seedMode = SeedMode.PER_DELIVERY)
        val seen = mutableSetOf<Pair<Double, Double>>()
        repeat(20) { i ->
            val out = RandomizationTransformer.transform(
                Fixtures.sample(),
                policy,
                TransformationContext(DeterministicRandomSource(i.toLong()), 0L),
            )
            assertTrue(out is TransformOutcome.Ok)
            val l = (out as TransformOutcome.Ok).location
            seen.add(l.latitude to l.longitude)
        }
        assertTrue(seen.size > 1, "Per-delivery mode must be able to vary")
    }

    @Test
    fun `SP-014 extreme radius causes no numeric failure`() {
        val out = RadiusTransformer.transform(Fixtures.sample(), 100_000.0)
        assertTrue(out is TransformOutcome.Ok)
        val l = (out as TransformOutcome.Ok).location
        assertTrue(l.latitude.isFinite() && l.longitude.isFinite())
    }

    @Test
    fun `SP-015 raw coordinate unavailable to caller after transform`() {
        val out = okRadius(2_000.0)
        assertTrue(out.latitude != Fixtures.MUMBAI_LAT || out.longitude != Fixtures.MUMBAI_LON)
    }

    @Test
    fun `FUZZ-020 same seed plus same input gives same output`() {
        val policy = RandomizationPolicy(enabled = true, radiusMeters = 300.0, seedMode = SeedMode.PER_DELIVERY)
        fun run() = RandomizationTransformer.transform(
            Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(1234L), 0L),
        )
        val a = run() as TransformOutcome.Ok
        val b = run() as TransformOutcome.Ok
        // Note: same seed + fresh RNG instance per call -> identical streams.
        assertEquals(a.location, b.location)
    }

    @Test
    fun `FUZZ-021 different seeds can give different compliant outputs`() {
        val policy = RandomizationPolicy(enabled = true, radiusMeters = 300.0, seedMode = SeedMode.PER_DELIVERY)
        val outs = (0L until 10L).map {
            (RandomizationTransformer.transform(
                Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(it), 0L),
            ) as TransformOutcome.Ok).location
        }
        assertTrue(outs.distinct().size > 1)
        // All within bound.
        for (l in outs) {
            val d = locshield.spatial.CityTransformer.haversineMeters(
                Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON, l.latitude, l.longitude,
            )
            assertTrue(d <= 300.0 + 1e-6, "d=$d")
        }
    }

    @Test
    fun `FUZZ-022 output bound respected across seeds`() {
        val policy = RandomizationPolicy(enabled = true, radiusMeters = 1_000.0, seedMode = SeedMode.PER_DELIVERY)
        repeat(200) { i ->
            val out = RandomizationTransformer.transform(
                Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(i.toLong()), 0L),
            )
            assertTrue(out is TransformOutcome.Ok)
            val l = (out as TransformOutcome.Ok).location
            val d = locshield.spatial.CityTransformer.haversineMeters(
                Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON, l.latitude, l.longitude,
            )
            assertTrue(d <= 1_000.0 + 1e-6, "seed=$i d=$d")
        }
    }

    @Test
    fun `FUZZ-023 seed is never exposed to the app`() {
        val policy = RandomizationPolicy(enabled = true, radiusMeters = 500.0, seedMode = SeedMode.PER_DELIVERY)
        val out = (RandomizationTransformer.transform(
            Fixtures.sample(), policy, Fixtures.transformCtx(seed = 42L),
        ) as TransformOutcome.Ok).location
        assertTrue(out.extras.isEmpty() || !out.extras.keys.any { it.contains("seed", ignoreCase = true) })
        assertEquals(null, out.provider?.let { if (it.contains("seed", ignoreCase = true)) it else null })
    }

    @Test
    fun `FUZZ-024 stable period holds output within period`() {
        val policy = RandomizationPolicy(
            enabled = true, radiusMeters = 500.0, stableForMs = 60_000L, seedMode = SeedMode.PERIOD_STABLE,
        )
        val id = Fixtures.identity()
        val periodStart = 60_000L * 1_000_000L * 100L
        val a = RandomizationTransformer.transform(
            Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(0L), periodStart, identity = id),
        ) as TransformOutcome.Ok
        val b = RandomizationTransformer.transform(
            Fixtures.sample(), policy,
            TransformationContext(DeterministicRandomSource(0L), periodStart + 59_000L * 1_000_000L, identity = id),
        ) as TransformOutcome.Ok
        assertEquals(a.location, b.location)
        // Next period may differ (only assert it stays within bound).
        val c = RandomizationTransformer.transform(
            Fixtures.sample(), policy,
            TransformationContext(DeterministicRandomSource(0L), periodStart + 61_000L * 1_000_000L, identity = id),
        ) as TransformOutcome.Ok
        val d = locshield.spatial.CityTransformer.haversineMeters(
            Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON, c.location.latitude, c.location.longitude,
        )
        assertTrue(d <= 500.0 + 1e-6)
    }

    @Test
    fun `SESSION_STABLE requires a session id, else fails closed`() {
        val policy = RandomizationPolicy(enabled = true, radiusMeters = 500.0, seedMode = SeedMode.SESSION_STABLE)
        val noSession = RandomizationTransformer.transform(
            Fixtures.sample(), policy, TransformationContext(DeterministicRandomSource(1L), 0L, identity = Fixtures.identity()),
        )
        assertTrue(noSession is TransformOutcome.Failed)
        val withSession = RandomizationTransformer.transform(
            Fixtures.sample(), policy,
            TransformationContext(DeterministicRandomSource(1L), 0L, "session-1", Fixtures.identity()),
        )
        assertTrue(withSession is TransformOutcome.Ok)
        // Same session -> same output.
        val again = RandomizationTransformer.transform(
            Fixtures.sample(), policy,
            TransformationContext(DeterministicRandomSource(777L), 999L, "session-1", Fixtures.identity()),
        )
        assertEquals(
            (withSession as TransformOutcome.Ok).location,
            (again as TransformOutcome.Ok).location,
        )
    }

    @Test
    fun `disabled randomization with RANDOMIZED spatial mode fails closed`() {
        val out = locshield.spatial.TransformationEngine.transform(
            Fixtures.sample(),
            locshield.model.SpatialPolicy(locshield.model.SpatialMode.RANDOMIZED),
            RandomizationPolicy(enabled = false),
            Fixtures.transformCtx(),
        )
        assertTrue(out is TransformOutcome.Failed)
    }

    @Test
    fun `throwing RNG fails closed as RNG_FAILURE, never raw`() {
        val throwing = object : locshield.spatial.RandomSource {
            override fun nextDouble(): Double = throw RuntimeException("rng exploded")
        }
        val out = RandomizationTransformer.transform(
            Fixtures.sample(),
            RandomizationPolicy(enabled = true, radiusMeters = 100.0),
            TransformationContext(throwing, 0L),
        )
        assertTrue(out is TransformOutcome.Failed)
        assertEquals(
            locshield.model.ReasonCode.RNG_FAILURE,
            (out as TransformOutcome.Failed).reason,
        )
    }

    @Test
    fun `out-of-range RNG output fails closed`() {
        val evil = object : locshield.spatial.RandomSource {
            override fun nextDouble(): Double = 2.0
        }
        val out = RandomizationTransformer.transform(
            Fixtures.sample(),
            RandomizationPolicy(enabled = true, radiusMeters = 100.0),
            TransformationContext(evil, 0L),
        )
        assertTrue(out is TransformOutcome.Failed)
    }

    @Test
    fun `seed derivation is stable across JVM string hashing`() {
        // FNV-1a must be deterministic (kotlin Random(seed) is specified stable).
        val s1 = RandomizationTransformer.stableSeed("com.example.app", 42L)
        val s2 = RandomizationTransformer.stableSeed("com.example.app", 42L)
        assertEquals(s1, s2)
        assertNotEquals(s1, RandomizationTransformer.stableSeed("com.other.app", 42L))
    }
}
