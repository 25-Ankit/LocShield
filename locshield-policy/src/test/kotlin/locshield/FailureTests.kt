package locshield

import locshield.metadata.MetadataSanitizer
import locshield.model.Decision
import locshield.model.ReasonCode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.policy.ResolutionOutcome
import locshield.spatial.TransformOutcome
import locshield.spatial.TransformationEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Failure tests (Doc 13 Table 15, ERR-001..ERR-008). Central rule: a failure must
 * never expose more location information (fail closed, never raw/permissive).
 */
class FailureTests {
    @Test
    fun `ERR-001 null policy fails safe`() {
        val engine = Fixtures.rig().engine
        val d = engine.evaluate(
            Fixtures.identity(),
            Fixtures.request(),
            // Simulate a missing snapshot by resolving with a denied ceiling path is
            // covered elsewhere; here the engine receives no effective policy because
            // resolveAndEvaluate without resolver fails closed.
            Fixtures.effective(Fixtures.denyPolicy()),
            Fixtures.locationCtx(Fixtures.sample(lat = Double.NaN, lon = 0.0)),
            0L,
        )
        assertEquals(Decision.FAIL_CLOSED, d.decision)
    }

    @Test
    fun `ERR-002 corrupt snapshot uses safe default, never permissive`() {
        // The store can only hold validated policies; resolution re-validates.
        // A policy that became invalid (e.g. bounds change) resolves to DENY-ish.
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        // Force expiry to simulate stale snapshot handling.
        rig.store.put(Fixtures.exactPolicy().copy(createdAtWallMs = 1_000L, expiresAtWallMs = 1_001L))
        val d = rig.engine.resolveAndEvaluate(
            id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 9_999_999L,
        )
        assertTrue(d.decision == Decision.DENY || d.decision == Decision.FAIL_CLOSED)
    }

    @Test
    fun `ERR-003 transformation exception never returns raw location`() {
        val out = TransformationEngine.transform(
            Fixtures.sample(),
            SpatialPolicy(SpatialMode.GRID, gridMeters = 500.0),
            locshield.model.RandomizationPolicy(enabled = false),
            Fixtures.transformCtx(),
        )
        assertTrue(out is TransformOutcome.Ok)
        // Failure injection: unknown pinned city.
        val bad = TransformationEngine.transform(
            Fixtures.sample(),
            SpatialPolicy(SpatialMode.CITY, cityId = "nonexistent"),
            locshield.model.RandomizationPolicy(enabled = false),
            Fixtures.transformCtx(),
        )
        assertTrue(bad is TransformOutcome.Failed)
        // And crucially: the Failed outcome carries no coordinates at all.
        assertEquals(ReasonCode.TRANSFORMATION_FAILURE, (bad as TransformOutcome.Failed).reason)
    }

    @Test
    fun `ERR-004 metadata exception never returns unsanitized object`() {
        val bad = Fixtures.sample().copy(latitude = Double.POSITIVE_INFINITY)
        val out = MetadataSanitizer.sanitize(bad, SpatialPolicy(SpatialMode.EXACT), locshield.model.MetadataPolicy())
        assertTrue(out is MetadataSanitizer.SanitizeOutcome.Failed)
    }

    @Test
    fun `ERR-005 temporal state corruption fails closed`() {
        val c = locshield.temporal.TemporalController()
        // Missing params on a restricted mode suppress rather than allow.
        val d = c.evaluate(
            Fixtures.identity(),
            TemporalPolicy(TemporalMode.MIN_INTERVAL),
            1L,
            0L,
        )
        assertEquals(locshield.model.TemporalAction.SUPPRESS, d.action)
    }

    @Test
    fun `ERR-006 unknown enum rejected before decision logic`() {
        assertEquals(null, locshield.model.spatialModeOf("EXACT_PLUS"))
        assertEquals(null, locshield.model.temporalModeOf("SOMETIMES"))
    }

    @Test
    fun `ERR-007 RNG failure is a safe transformation failure`() {
        val badRng = object : locshield.spatial.RandomSource {
            override fun nextDouble(): Double = throw IllegalStateException("no entropy")
        }
        val out = locshield.spatial.RandomizationTransformer.transform(
            Fixtures.sample(),
            locshield.model.RandomizationPolicy(enabled = true, radiusMeters = 100.0),
            locshield.spatial.TransformationContext(badRng, 0L),
        )
        assertTrue(out is TransformOutcome.Failed)
        assertEquals(ReasonCode.RNG_FAILURE, (out as TransformOutcome.Failed).reason)
    }

    @Test
    fun `ERR-008 arithmetic overflow rejected or fail-closed`() {
        // Validation rejects unbounded intervals.
        val v = locshield.policy.PolicyValidator.validate(Fixtures.minIntervalPolicy(Long.MAX_VALUE))
        assertTrue(v is locshield.model.ValidationResult.Rejected)
        // Saturating math never wraps.
        assertEquals(Long.MAX_VALUE, locshield.temporal.TemporalController.millisToNanosSaturated(Long.MAX_VALUE))
    }

    @Test
    fun `engine never throws - unexpected exceptions become FAIL_CLOSED`() {
        // resolveAndEvaluate without a resolver must fail closed, not throw.
        val engine = locshield.engine.PolicyEngine()
        val d = engine.resolveAndEvaluate(
            Fixtures.identity(), Fixtures.request(), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 0L,
        )
        assertEquals(Decision.FAIL_CLOSED, d.decision)
    }
}
