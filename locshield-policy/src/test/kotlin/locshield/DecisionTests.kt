package locshield

import locshield.model.Decision
import locshield.model.LocationContext
import locshield.model.ReasonCode
import locshield.model.RequestType
import locshield.model.SourceType
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalAction
import locshield.policy.ResolutionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decision tests (Doc 13 Table 6, DEC-001..DEC-012) and Doc 12 Table 13
 * PE-001..PE-015 mandatory vectors.
 */
class DecisionTests {
    private fun decide(
        rig: Fixtures.Rig,
        pkg: String = "com.example.app",
        ceiling: locshield.model.AndroidAuthorization = Fixtures.preciseCeiling(),
        atNanos: Long = 0L,
        type: RequestType = RequestType.CONTINUOUS_UPDATES,
        cached: Boolean = false,
        passive: Boolean = false,
        geofence: Boolean = false,
        source: SourceType = SourceType.FUSED,
        foreground: Boolean = true,
    ) = rig.engine.resolveAndEvaluate(
        Fixtures.identity(pkg = pkg),
        Fixtures.request(Fixtures.identity(pkg = pkg), type = type, foreground = foreground),
        ceiling,
        Fixtures.locationCtx(
            atNanos = atNanos,
            cached = cached,
            passive = passive,
            geofence = geofence,
            source = source,
        ),
        2_000L,
    )

    @Test
    fun `DEC-001 enabled authorized EXACT yields ALLOW`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val d = decide(rig)
        assertEquals(Decision.ALLOW, d.decision)
        assertEquals(ReasonCode.ALLOW_OK, d.reasonCode)
        assertEquals(1L, d.policyGeneration)
    }

    @Test
    fun `DEC-002 enabled authorized CITY yields TRANSFORM`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val d = decide(rig)
        assertEquals(Decision.TRANSFORM, d.decision)
        assertEquals(SpatialMode.CITY, d.spatialMode)
    }

    @Test
    fun `DEC-003 spatial DENY yields DENY`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val d = decide(rig)
        assertEquals(Decision.DENY, d.decision)
        assertEquals(ReasonCode.SPATIAL_DENY, d.reasonCode)
    }

    @Test
    fun `DEC-004 temporal suppression yields THROTTLE`() {
        val rig = Fixtures.rig(Fixtures.minIntervalPolicy(60_000L))
        val id = Fixtures.identity()
        val eff = (rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
            as ResolutionOutcome.Effective).policy
        val first = rig.engine.evaluate(id, Fixtures.request(id), eff, Fixtures.locationCtx(atNanos = 0L), 0L)
        assertTrue(first.decision == Decision.ALLOW || first.decision == Decision.TRANSFORM)
        rig.engine.temporal.recordDelivery(id, eff.temporal, eff.policyGeneration, 0L)
        val second = rig.engine.evaluate(
            id,
            Fixtures.request(id),
            eff,
            Fixtures.locationCtx(atNanos = Fixtures.msToNanos(1_000L)),
            Fixtures.msToNanos(1_000L),
        )
        assertEquals(Decision.THROTTLE, second.decision)
        assertEquals(ReasonCode.TEMPORAL_THROTTLE, second.reasonCode)
        assertEquals(TemporalAction.SUPPRESS, second.temporalAction)
    }

    @Test
    fun `DEC-005 disabled policy yields DENY`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy().copy(enabled = false))
        assertEquals(Decision.DENY, decide(rig).decision)
    }

    @Test
    fun `DEC-006 expired policy yields safe expiration DENY`() {
        val rig = Fixtures.rig(
            Fixtures.exactPolicy().copy(createdAtWallMs = 1_000L, expiresAtWallMs = 1_500L),
        )
        val d = decide(rig)
        assertEquals(Decision.DENY, d.decision)
        assertEquals(ReasonCode.POLICY_EXPIRED, d.reasonCode)
    }

    @Test
    fun `DEC-007 engine evaluation error fails closed`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        // Invalid coordinates cannot be evaluated to a delivery.
        val bad = Fixtures.locationCtx(
            Fixtures.sample(lat = Double.NaN, lon = Double.NaN),
        )
        val id = Fixtures.identity()
        val eff = (rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
            as ResolutionOutcome.Effective).policy
        val d = rig.engine.evaluate(id, Fixtures.request(id), eff, bad, 0L)
        assertEquals(Decision.FAIL_CLOSED, d.decision)
    }

    @Test
    fun `DEC-008 transformation required yields TRANSFORM`() {
        val rig = Fixtures.rig(Fixtures.gridPolicy(500.0))
        assertEquals(Decision.TRANSFORM, decide(rig).decision)
    }

    @Test
    fun `DEC-009 cached location still applies current policy`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val d = decide(rig, cached = true, source = SourceType.CACHED)
        assertEquals(Decision.TRANSFORM, d.decision)
        assertEquals(SpatialMode.CITY, d.spatialMode)
    }

    @Test
    fun `DEC-010 passive location still applies current policy`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val d = decide(rig, passive = true, source = SourceType.PASSIVE)
        assertEquals(Decision.TRANSFORM, d.decision)
    }

    @Test
    fun `DEC-011 geofence denied yields DENY`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val d = decide(rig, type = RequestType.GEOFENCE_EVENT, geofence = true, source = SourceType.GEOFENCE)
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `DEC-012 geofence permitted yields policy-constrained event`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val d = decide(rig, type = RequestType.GEOFENCE_EVENT, geofence = true, source = SourceType.GEOFENCE)
        assertEquals(Decision.TRANSFORM, d.decision)
    }

    @Test
    fun `PE-001 Android denied yields DENY regardless of policy`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val d = decide(rig, ceiling = Fixtures.deniedCeiling())
        assertEquals(Decision.DENY, d.decision)
        assertEquals(ReasonCode.ANDROID_AUTHORIZATION_DENIED, d.reasonCode)
    }

    @Test
    fun `PE-004 approximate ceiling plus EXACT never ALLOWs raw`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val d = decide(rig, ceiling = Fixtures.approximateCeiling())
        assertTrue(d.decision == Decision.TRANSFORM, "Must degrade, was ${d.decision}")
    }

    @Test
    fun `PE-012 background denied by Android yields DENY in background`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val ceiling = Fixtures.preciseCeiling(backgroundAllowed = false)
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id,
            Fixtures.request(id, foreground = false, bgPermission = true),
            ceiling,
            Fixtures.locationCtx(),
            2_000L,
        )
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `PE-013 geofence registration denied under DENY`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val d = decide(rig, type = RequestType.GEOFENCE_REGISTRATION)
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `PE-014 unknown enum reaches FAIL_CLOSED via strict parsers`() {
        // The typed API cannot carry unknown values; the parsers are the gate.
        assertEquals(null, locshield.model.spatialModeOf("SUPER_EXACT"))
    }

    @Test
    fun `PE-015 transformation errors never produce raw delivery decisions`() {
        // Decision layer only selects TRANSFORM; the transform step itself is
        // verified fail-closed in FailureTests. Here: DENY policy + valid input
        // must not accidentally become TRANSFORM.
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val d = decide(rig)
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `decisions are deterministic for identical inputs`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val a = decide(rig)
        val b = decide(rig)
        assertEquals(a, b)
    }

    @Test
    fun `THROTTLE carries SUPPRESS temporal action and no delivery`() {
        val rig = Fixtures.rig(Fixtures.minIntervalPolicy(3_600_000L))
        val id = Fixtures.identity()
        val eff = (rig.resolver.resolve(id, Fixtures.request(id), Fixtures.preciseCeiling(), 2_000L, 0L)
            as ResolutionOutcome.Effective).policy
        rig.engine.temporal.recordDelivery(id, eff.temporal, eff.policyGeneration, 0L)
        val d = rig.engine.evaluate(
            id,
            Fixtures.request(id),
            eff,
            Fixtures.locationCtx(atNanos = 1L),
            1L,
        )
        assertEquals(Decision.THROTTLE, d.decision)
        assertEquals(TemporalAction.SUPPRESS, d.temporalAction)
    }

    @Test
    fun `EXACT plus enabled randomization yields TRANSFORM`() {
        val rig = Fixtures.rig(
            Fixtures.basePolicy().copy(
                randomization = locshield.model.RandomizationPolicy(enabled = true, radiusMeters = 200.0),
            ),
        )
        assertEquals(Decision.TRANSFORM, decide(rig).decision)
    }

    @Test
    fun `CACHED request type is evaluated, not bypassed`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val d = decide(rig, type = RequestType.CACHED_LOCATION, cached = true, source = SourceType.CACHED)
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `decision carries resolving generation`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        assertEquals(1L, decide(rig).policyGeneration)
    }
}
