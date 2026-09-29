package locshield

import locshield.model.Decision
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.policy.ResolutionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Composition / conflict tests (Doc 13 Table 7, CMP-001..CMP-008; Doc 12 Table 10).
 * Intersection is restrictive: combining constraints never widens access.
 */
class CompositionTests {
    @Test
    fun `CMP-001 Android denied plus exact yields DENY`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id, Fixtures.request(id), Fixtures.deniedCeiling(), Fixtures.locationCtx(), 2_000L,
        )
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `CMP-002 Android approximate plus exact yields approximate ceiling`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        val out = rig.resolver.resolve(id, Fixtures.request(id), Fixtures.approximateCeiling(), 2_000L, 0L)
        assertTrue(out is ResolutionOutcome.Effective)
        val eff = (out as ResolutionOutcome.Effective).policy
        assertTrue(eff.spatial.mode != SpatialMode.EXACT)
    }

    @Test
    fun `CMP-003 CITY plus precise metadata still degrades metadata`() {
        val rig = Fixtures.rig(
            Fixtures.cityPolicy().copy(metadata = locshield.model.MetadataPolicy()),
        )
        val id = Fixtures.identity()
        val out = rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
        assertTrue(out is ResolutionOutcome.Effective)
        val eff = (out as ResolutionOutcome.Effective).policy
        val raw = Fixtures.sample(accuracy = 5f, speed = 12f, bearing = 33f)
        val transformed = when (
            val t = locshield.spatial.TransformationEngine.transform(
                raw, eff.spatial, eff.randomization, Fixtures.transformCtx(),
            )
        ) {
            is locshield.spatial.TransformOutcome.Ok -> t.location
            is locshield.spatial.TransformOutcome.Failed -> error("transform failed: $t")
        }
        val sanitized = when (
            val s = locshield.metadata.MetadataSanitizer.sanitize(transformed, eff.spatial, Fixtures.strictMetadata())
        ) {
            is locshield.metadata.MetadataSanitizer.SanitizeOutcome.Ok -> s.location
            is locshield.metadata.MetadataSanitizer.SanitizeOutcome.Failed -> error("sanitize failed")
        }
        // Even though the stored policy RETAINs speed/bearing, the strict sanitizer
        // path degrades them; accuracy must respect the CITY floor regardless.
        assertTrue(sanitized.accuracyMeters >= 5_000f)
        assertEquals(null, sanitized.speedMps)
        assertEquals(null, sanitized.bearingDeg)
    }

    @Test
    fun `CMP-004 REALTIME plus MIN_INTERVAL yields MIN_INTERVAL`() {
        val strict = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = 60_000L)
        val loose = TemporalPolicy(TemporalMode.REALTIME)
        val winner = locshield.policy.Restriction.stricterTemporal(loose, strict)
        assertEquals(TemporalMode.MIN_INTERVAL, winner.mode)
    }

    @Test
    fun `CMP-005 RADIUS plus GRID yields stricter effective restriction`() {
        val radius1k = SpatialPolicy(SpatialMode.RADIUS, radiusMeters = 1_000.0)
        val grid5k = SpatialPolicy(SpatialMode.GRID, gridMeters = 5_000.0)
        assertEquals(grid5k, locshield.policy.Restriction.stricterSpatial(radius1k, grid5k))
        assertEquals(grid5k, locshield.policy.Restriction.stricterSpatial(grid5k, radius1k))
        // Equal footing is stable and deterministic.
        assertEquals(radius1k, locshield.policy.Restriction.stricterSpatial(radius1k, radius1k))
    }

    @Test
    fun `CMP-006 background allow plus Android denied yields DENY`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id,
            Fixtures.request(id, foreground = false, bgPermission = true),
            Fixtures.preciseCeiling(backgroundAllowed = false),
            Fixtures.locationCtx(),
            2_000L,
        )
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `CMP-007 expired permissive policy uses safe fallback, never broader`() {
        val rig = Fixtures.rig(
            Fixtures.exactPolicy().copy(createdAtWallMs = 1_000L, expiresAtWallMs = 1_500L),
        )
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 9_999_999L,
        )
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `CMP-008 randomized plus exact requires randomization`() {
        val rig = Fixtures.rig(Fixtures.randomizedPolicy().copy(spatial = SpatialPolicy(SpatialMode.EXACT)))
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
        )
        assertEquals(Decision.TRANSFORM, d.decision)
    }

    @Test
    fun `restriction ordering is monotonic`() {
        // Adding a restriction can never make the outcome more permissive (P3 core).
        val exact = SpatialPolicy(SpatialMode.EXACT)
        val grid = SpatialPolicy(SpatialMode.GRID, gridMeters = 500.0)
        val deny = SpatialPolicy(SpatialMode.DENY)
        val r = locshield.policy.Restriction
        assertTrue(r.spatialScaleMeters(grid) >= r.spatialScaleMeters(exact))
        assertTrue(r.spatialScaleMeters(deny) >= r.spatialScaleMeters(grid))
    }
}
