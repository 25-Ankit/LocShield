package locshield

import locshield.metadata.MetadataSanitizer
import locshield.model.AppPolicy
import locshield.model.FieldHandling
import locshield.model.MetadataPolicy
import locshield.model.RandomizationPolicy
import locshield.model.SeedMode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.policy.PolicyValidator
import locshield.spatial.TransformOutcome
import locshield.spatial.TransformationEngine
import locshield.model.ValidationResult
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Fuzzing (Doc 13 sections 23-24): malformed policies, enum values, bounds,
 * expirations, selectors, nested metadata, randomization params, request
 * combinations, hostile coordinates, temporal sequences.
 *
 * Oracles: no uncaught crash; no NaN/Infinity in accepted outputs; no invalid
 * enum reaches decision logic; no ALLOW from invalid policy; no raw return after
 * failure; no overflow effects; no temporal state-machine violation.
 */
class FuzzTests {
    private fun edgeDouble(r: Random): Double =
        when (r.nextInt(12)) {
            0 -> Double.NaN
            1 -> Double.POSITIVE_INFINITY
            2 -> Double.NEGATIVE_INFINITY
            3 -> -r.nextDouble() * 1e10
            4 -> r.nextDouble() * 1e10
            5 -> -91.0 - r.nextDouble()
            6 -> 91.0 + r.nextDouble()
            7 -> -180.0 - r.nextDouble() * 10.0
            8 -> 180.0 + r.nextDouble() * 10.0
            9 -> 0.0
            10 -> -0.0
            else -> -90.0 + r.nextDouble() * 180.0
        }

    private fun edgeLong(r: Random): Long =
        when (r.nextInt(8)) {
            0 -> Long.MIN_VALUE
            1 -> Long.MAX_VALUE
            2 -> -1L
            3 -> 0L
            4 -> r.nextLong()
            5 -> Int.MIN_VALUE.toLong()
            6 -> Int.MAX_VALUE.toLong()
            else -> (r.nextDouble() * 1e15).toLong()
        }

    @Test
    fun `fuzz serialized policy shapes never crash validation`() {
        val r = Random(20260928L)
        repeat(5_000) { i ->
            val modes = SpatialMode.values()
            val tmodes = TemporalMode.values()
            val policy = AppPolicy(
                schemaVersion = if (r.nextInt(10) == 0) -1 else r.nextInt(4),
                packageName = if (r.nextBoolean()) "com.fuzz.$i" else "",
                userId = if (r.nextBoolean()) r.nextInt(20) else -r.nextInt(5),
                enabled = r.nextBoolean(),
                spatial = SpatialPolicy(
                    mode = modes[r.nextInt(modes.size)],
                    radiusMeters = if (r.nextBoolean()) edgeDouble(r) else null,
                    gridMeters = if (r.nextBoolean()) edgeDouble(r) else null,
                    cityId = if (r.nextInt(10) == 0) "" else null,
                ),
                temporal = TemporalPolicy(
                    mode = tmodes[r.nextInt(tmodes.size)],
                    minimumIntervalMs = if (r.nextBoolean()) edgeLong(r) else null,
                    periodicIntervalMs = if (r.nextBoolean()) edgeLong(r) else null,
                    maxDeliveriesPerWindow = if (r.nextBoolean()) r.nextInt() else null,
                    windowMs = if (r.nextBoolean()) edgeLong(r) else null,
                ),
                metadata = MetadataPolicy(
                    accuracyMode = FieldHandling.values()[r.nextInt(3)],
                    timestampMode = FieldHandling.values()[r.nextInt(3)],
                    elapsedRealtimeMode = FieldHandling.values()[r.nextInt(3)],
                    altitudeMode = FieldHandling.values()[r.nextInt(3)],
                    speedMode = FieldHandling.values()[r.nextInt(3)],
                    bearingMode = FieldHandling.values()[r.nextInt(3)],
                    providerMode = FieldHandling.values()[r.nextInt(3)],
                    extrasMode = FieldHandling.values()[r.nextInt(3)],
                ),
                randomization = RandomizationPolicy(
                    enabled = r.nextBoolean(),
                    radiusMeters = if (r.nextBoolean()) edgeDouble(r) else null,
                    stableForMs = if (r.nextBoolean()) edgeLong(r) else null,
                    seedMode = SeedMode.values()[r.nextInt(SeedMode.values().size)],
                ),
                createdAtWallMs = edgeLong(r),
                updatedAtWallMs = edgeLong(r),
                expiresAtWallMs = if (r.nextBoolean()) edgeLong(r) else null,
            )
            // Oracle: validation is total (never throws).
            val result = PolicyValidator.validate(policy)
            assertTrue(result is ValidationResult.Accepted || result is ValidationResult.Rejected, "case=$i")
        }
    }

    @Test
    fun `fuzz hostile coordinates never escape as accepted outputs`() {
        val r = Random(77L)
        val spatials = listOf(
            SpatialPolicy(SpatialMode.GRID, gridMeters = 500.0),
            SpatialPolicy(SpatialMode.RADIUS, radiusMeters = 500.0),
            SpatialPolicy(SpatialMode.CITY),
            SpatialPolicy(SpatialMode.EXACT),
        )
        repeat(3_000) {
            val raw = Fixtures.sample(
                lat = edgeDouble(r),
                lon = edgeDouble(r),
                accuracy = when (r.nextInt(5)) {
                    0 -> Float.NaN
                    1 -> Float.POSITIVE_INFINITY
                    2 -> -5f
                    else -> r.nextFloat() * 100f
                },
            )
            for (spatial in spatials) {
                val t = TransformationEngine.transform(
                    raw, spatial, RandomizationPolicy(enabled = false), Fixtures.transformCtx(),
                )
                when (t) {
                    is TransformOutcome.Ok -> {
                        // Oracle: accepted outputs are finite and in range.
                        val l = t.location
                        assertTrue(l.latitude.isFinite() && l.longitude.isFinite(), "non-finite accepted")
                        assertTrue(l.latitude in -90.0..90.0 && l.longitude in -180.0..180.0, "range")
                        assertTrue(l.accuracyMeters.isFinite() && l.accuracyMeters >= 0f, "accuracy")
                        // Oracle: sanitization of an accepted transform is total.
                        val s = MetadataSanitizer.sanitize(l, spatial, MetadataPolicy())
                        assertTrue(
                            s is MetadataSanitizer.SanitizeOutcome.Ok ||
                                s is MetadataSanitizer.SanitizeOutcome.Failed,
                        )
                    }
                    is TransformOutcome.Failed -> { /* fail-closed path; carries no coordinates */ }
                }
            }
        }
    }

    @Test
    fun `fuzz temporal event sequences never violate the state machine`() {
        val r = Random(31337L)
        val controller = locshield.temporal.TemporalController()
        val id = Fixtures.identity(pkg = "com.fuzz.temporal")
        val policies = listOf(
            TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 1_000L),
            TemporalPolicy(TemporalMode.PERIODIC, periodicIntervalMs = 5_000L),
            TemporalPolicy(TemporalMode.RATE_LIMIT, maxDeliveriesPerWindow = 3, windowMs = 10_000L),
            TemporalPolicy(TemporalMode.ONE_SHOT),
            TemporalPolicy(TemporalMode.REALTIME),
        )
        var now = 0L
        var deliveries = 0
        repeat(2_000) { i ->
            // Monotonic but hostile jumps, including saturation edge.
            now = if (r.nextInt(50) == 0) Long.MAX_VALUE - r.nextInt(1000) else now + r.nextInt(20_000)
            if (now < 0) now = Long.MAX_VALUE - 1 // keep monotonic domain
            val p = policies[r.nextInt(policies.size)]
            val d = controller.evaluate(id, p, 1L, now)
            assertTrue(
                d.action == locshield.model.TemporalAction.PROCEED ||
                    d.action == locshield.model.TemporalAction.SUPPRESS,
                "case=$i",
            )
            if (d.action == locshield.model.TemporalAction.PROCEED && r.nextBoolean()) {
                controller.recordDelivery(id, p, 1L, now)
                deliveries++
            }
        }
        assertTrue(deliveries > 0, "Fuzz must exercise deliveries too")
    }

    @Test
    fun `fuzz randomization streams stay bounded and total`() {
        val r = Random(555L)
        repeat(1_000) {
            val radius = doubleArrayOf(1.0, 100.0, 5_000.0, 100_000.0)[r.nextInt(4)]
            val policy = RandomizationPolicy(
                enabled = true,
                radiusMeters = radius,
                stableForMs = if (r.nextBoolean()) 60_000L else null,
                seedMode = SeedMode.values()[r.nextInt(SeedMode.values().size)],
            )
            val ctx = locshield.spatial.TransformationContext(
                locshield.spatial.DeterministicRandomSource(r.nextLong()),
                r.nextLong().let { if (it < 0) 0L else it },
                sessionId = if (r.nextBoolean()) "s-$it" else null,
                identity = Fixtures.identity(pkg = "com.fuzz.$it"),
            )
            val out = locshield.spatial.RandomizationTransformer.transform(Fixtures.sample(), policy, ctx)
            when (out) {
                is TransformOutcome.Ok -> {
                    val l = out.location
                    assertTrue(l.latitude.isFinite() && l.longitude.isFinite())
                    val d = locshield.spatial.CityTransformer.haversineMeters(
                        Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON, l.latitude, l.longitude,
                    )
                    assertTrue(d <= radius + 1e-6, "bound violated: $d > $radius")
                }
                is TransformOutcome.Failed -> { /* closed */ }
            }
        }
    }

    @Test
    fun `fuzz engine evaluation is total over hostile contexts`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val r = Random(808L)
        repeat(2_000) { i ->
            val id = Fixtures.identity(pkg = "com.fuzz.$i", uid = r.nextInt())
            val req = Fixtures.request(id).copy(
                isForeground = r.nextBoolean(),
                hasBackgroundPermission = r.nextBoolean(),
                requestType = locshield.model.RequestType.values()[r.nextInt(locshield.model.RequestType.values().size)],
                providerType = locshield.model.SourceType.values()[r.nextInt(locshield.model.SourceType.values().size)],
            )
            val loc = Fixtures.locationCtx(
                sample = Fixtures.sample(lat = edgeDouble(r), lon = edgeDouble(r)),
                atNanos = r.nextLong().let { if (it < 0) 0L else it },
                cached = r.nextBoolean(),
                passive = r.nextBoolean(),
                geofence = r.nextBoolean(),
            )
            val ceiling = when (r.nextInt(3)) {
                0 -> Fixtures.deniedCeiling()
                1 -> Fixtures.approximateCeiling()
                else -> Fixtures.preciseCeiling(r.nextBoolean())
            }
            // Oracle: evaluation never throws; result is always a valid decision.
            val d = rig.engine.resolveAndEvaluate(id, req, ceiling, loc, 2_000L)
            assertTrue(d.decision in locshield.model.Decision.values(), "case=$i")
        }
    }
}
