package locshield

import locshield.metadata.MetadataSanitizer
import locshield.model.AndroidAuthorization
import locshield.model.AppIdentity
import locshield.model.Decision
import locshield.model.LocationSample
import locshield.model.ReasonCode
import locshield.model.SpatialMode
import locshield.spatial.TransformOutcome
import locshield.spatial.TransformationContext
import locshield.spatial.TransformationEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end pipeline helper + integration tests, mirroring the future
 * Enforcement Adapter flow (Doc 08 section 6, Doc 11 section 39):
 * resolve -> evaluate -> temporal record -> transform -> sanitize -> deliver.
 */
object Pipeline {
    sealed interface Delivery {
        data class Delivered(val location: LocationSample, val generation: Long) : Delivery

        data class Suppressed(val reason: ReasonCode) : Delivery
    }

    fun runDelivery(
        rig: Fixtures.Rig,
        identity: AppIdentity,
        ceiling: AndroidAuthorization,
        raw: LocationSample,
        atNanos: Long,
        ctx: TransformationContext,
        foreground: Boolean = true,
    ): Delivery {
        // Adapter-shaped flow: single resolution, then evaluation, then enforcement
        // strictly per-decision (appliedSpatial/appliedTemporal).
        val request = Fixtures.request(identity, foreground = foreground)
        val resolved = rig.resolver.resolve(identity, request, ceiling, 2_000L, atNanos)
        if (resolved !is locshield.policy.ResolutionOutcome.Effective) {
            val reason = (resolved as locshield.policy.ResolutionOutcome.Denied).reason
            return Delivery.Suppressed(reason)
        }
        val eff = resolved.policy
        val decision = rig.engine.evaluate(
            identity, request, eff, Fixtures.locationCtx(raw, atNanos = atNanos), atNanos,
        )
        return when (decision.decision) {
            Decision.ALLOW, Decision.TRANSFORM -> {
                val transformed = when (
                    val t = TransformationEngine.transform(
                        raw, decision.appliedSpatial, eff.randomization, ctx,
                    )
                ) {
                    is TransformOutcome.Ok -> t.location
                    is TransformOutcome.Failed -> return Delivery.Suppressed(t.reason)
                }
                val sanitized = when (
                    val s = MetadataSanitizer.sanitize(transformed, decision.appliedSpatial, eff.metadata)
                ) {
                    is MetadataSanitizer.SanitizeOutcome.Ok -> s.location
                    is MetadataSanitizer.SanitizeOutcome.Failed -> return Delivery.Suppressed(s.reason)
                }
                rig.engine.temporal.recordDelivery(
                    identity, decision.appliedTemporal, decision.policyGeneration, atNanos,
                )
                Delivery.Delivered(sanitized, decision.policyGeneration)
            }
            else -> Delivery.Suppressed(decision.reasonCode)
        }
    }
}

class PipelineIntegrationTests {
    @Test
    fun `Doc12 section37 example CITY plus 15min plus speed-DENY`() {
        val policy = Fixtures.cityPolicy().copy(
            temporal = locshield.model.TemporalPolicy(
                locshield.model.TemporalMode.MIN_INTERVAL,
                minimumIntervalMs = 15L * 60L * 1_000L,
            ),
            metadata = Fixtures.strictMetadata(),
            background = locshield.model.BackgroundPolicy(locshield.model.BackgroundMode.RESTRICT),
        )
        val rig = Fixtures.rig(policy)
        val id = Fixtures.identity()
        val ctx = Fixtures.transformCtx()

        // First delivery: TRANSFORM to CITY, speed sanitized away.
        val first = Pipeline.runDelivery(rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), 0L, ctx)
        assertTrue(first is Pipeline.Delivery.Delivered, "first=$first")
        val loc = (first as Pipeline.Delivery.Delivered).location
        assertEquals("mumbai", loc.cityId)
        assertNull(loc.speedMps)
        assertTrue(loc.accuracyMeters >= 5_000f)

        // Second delivery after 3 s: THROTTLE (suppressed).
        val second = Pipeline.runDelivery(
            rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), Fixtures.msToNanos(3_000L), ctx,
        )
        assertTrue(second is Pipeline.Delivery.Suppressed, "second=$second")
        assertEquals(ReasonCode.TEMPORAL_THROTTLE, (second as Pipeline.Delivery.Suppressed).reason)

        // After 15 min: TRANSFORM again with sanitized metadata.
        val third = Pipeline.runDelivery(
            rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), Fixtures.msToNanos(15L * 60L * 1_000L), ctx,
        )
        assertTrue(third is Pipeline.Delivery.Delivered, "third=$third")
    }

    @Test
    fun `Doc11 section40 policy change during active request applies at next delivery`() {
        // t0: active request under EXACT.
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        val ctx = Fixtures.transformCtx()
        val before = Pipeline.runDelivery(rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), 0L, ctx)
        assertTrue(before is Pipeline.Delivery.Delivered)
        assertEquals(1L, (before as Pipeline.Delivery.Delivered).generation)

        // t1: user changes EXACT -> CITY; t2: generation increments.
        rig.store.put(Fixtures.cityPolicy())
        // t3-t6: next delivery resolves the CURRENT generation and transforms.
        val after = Pipeline.runDelivery(
            rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), Fixtures.msToNanos(1_000L), ctx,
        )
        assertTrue(after is Pipeline.Delivery.Delivered, "after=$after")
        assertEquals(2L, (after as Pipeline.Delivery.Delivered).generation)
        assertEquals("mumbai", after.location.cityId)
    }

    @Test
    fun `DENY policy delivers nothing through the full pipeline`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val out = Pipeline.runDelivery(
            rig, Fixtures.identity(), Fixtures.preciseCeiling(), Fixtures.sample(), 0L, Fixtures.transformCtx(),
        )
        assertTrue(out is Pipeline.Delivery.Suppressed)
    }

    @Test
    fun `denied ceiling delivers nothing even for EXACT policy`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val out = Pipeline.runDelivery(
            rig, Fixtures.identity(), Fixtures.deniedCeiling(), Fixtures.sample(), 0L, Fixtures.transformCtx(),
        )
        assertTrue(out is Pipeline.Delivery.Suppressed)
        assertEquals(
            ReasonCode.ANDROID_AUTHORIZATION_DENIED,
            (out as Pipeline.Delivery.Suppressed).reason,
        )
    }

    @Test
    fun `delivered location never equals raw input under CITY`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val raw = Fixtures.sample(lat = 19.0800, lon = 72.8800)
        val out = Pipeline.runDelivery(
            rig, Fixtures.identity(), Fixtures.preciseCeiling(), raw, 0L, Fixtures.transformCtx(),
        )
        assertTrue(out is Pipeline.Delivery.Delivered)
        val loc = (out as Pipeline.Delivery.Delivered).location
        // City center, not the raw point.
        assertEquals(19.0760, loc.latitude)
        assertEquals(72.8777, loc.longitude)
    }

    @Test
    fun `background RESTRICT degrades EXACT to coarse grid delivery`() {
        val policy = Fixtures.basePolicy().copy(
            background = locshield.model.BackgroundPolicy(locshield.model.BackgroundMode.RESTRICT),
        )
        val rig = Fixtures.rig(policy)
        val out = Pipeline.runDelivery(
            rig, Fixtures.identity(), Fixtures.preciseCeiling(backgroundAllowed = true),
            Fixtures.sample(), 0L, Fixtures.transformCtx(), foreground = false,
        )
        assertTrue(out is Pipeline.Delivery.Delivered, "out=$out")
        assertTrue((out as Pipeline.Delivery.Delivered).location.accuracyMeters >= 1_000f)
    }

    @Test
    fun `spatial mode DENY is reported consistently end to end`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
        )
        assertEquals(Decision.DENY, d.decision)
        assertEquals(SpatialMode.DENY, d.spatialMode)
    }
}
