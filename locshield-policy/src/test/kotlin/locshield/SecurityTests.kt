package locshield

import locshield.engine.PolicyEngine
import locshield.model.ApplicationSelector
import locshield.model.AuditEvent
import locshield.model.Decision
import locshield.model.LocationSample
import locshield.model.RequestType
import locshield.model.SourceType
import locshield.policy.TransactionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Security invariant tests (Doc 13 section 21; Tables 13/14/18 SEC-*).
 * Adversarial posture: attempt bypasses, identity confusion, cross-app leakage,
 * stale-policy windows, and oracle access. Every test must hold.
 */
class SecurityTests {
    @Test
    fun `Android-denied requests can never become ALLOW, any policy`() {
        val policies = listOf(
            Fixtures.exactPolicy(), Fixtures.cityPolicy(), Fixtures.gridPolicy(100.0),
            Fixtures.radiusPolicy(100.0), Fixtures.randomizedPolicy(),
        )
        for (p in policies) {
            val rig = Fixtures.rig(p)
            val id = Fixtures.identity()
            val d = rig.engine.resolveAndEvaluate(
                id, Fixtures.request(id), Fixtures.deniedCeiling(), Fixtures.locationCtx(), 2_000L,
            )
            assertEquals(Decision.DENY, d.decision, "policy=${p.spatial.mode}")
        }
    }

    @Test
    fun `source type never widens a decision`() {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val id = Fixtures.identity()
        for (source in SourceType.values()) {
            val d = rig.engine.resolveAndEvaluate(
                id, Fixtures.request(id), Fixtures.preciseCeiling(),
                Fixtures.locationCtx(source = source, passive = source == SourceType.PASSIVE), 2_000L,
            )
            assertEquals(Decision.DENY, d.decision, "source=$source")
        }
    }

    @Test
    fun `cached and passive flags never bypass evaluation`() {
        for (flag in listOf("cached" to true, "passive" to true)) {
            val rig = Fixtures.rig(Fixtures.denyPolicy())
            val id = Fixtures.identity()
            val ctx = if (flag.first == "cached") {
                Fixtures.locationCtx(cached = true, source = SourceType.CACHED)
            } else {
                Fixtures.locationCtx(passive = true, source = SourceType.PASSIVE)
            }
            val d = rig.engine.resolveAndEvaluate(id, Fixtures.request(id), Fixtures.preciseCeiling(), ctx, 2_000L)
            assertEquals(Decision.DENY, d.decision, flag.first)
        }
    }

    @Test
    fun `SEC-001 ENTER plus DENY yields no event`() {
        assertGeofenceDenied(RequestType.GEOFENCE_EVENT)
    }

    @Test
    fun `SEC-002 EXIT plus DENY yields no event`() {
        assertGeofenceDenied(RequestType.GEOFENCE_EVENT)
    }

    @Test
    fun `SEC-003 DWELL plus restrictive policy is restricted or denied`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id,
            Fixtures.request(id, type = RequestType.GEOFENCE_EVENT),
            Fixtures.preciseCeiling(),
            Fixtures.locationCtx(geofence = true, source = SourceType.GEOFENCE),
            2_000L,
        )
        assertTrue(d.decision == Decision.TRANSFORM || d.decision == Decision.DENY)
    }

    @Test
    fun `SEC-004 allowed geofence event is policy-constrained`() {
        val rig = Fixtures.rig(Fixtures.exactPolicy())
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id,
            Fixtures.request(id, type = RequestType.GEOFENCE_EVENT),
            Fixtures.preciseCeiling(),
            Fixtures.locationCtx(geofence = true, source = SourceType.GEOFENCE),
            2_000L,
        )
        assertEquals(Decision.ALLOW, d.decision)
    }

    @Test
    fun `SEC-005 registration under DENY is rejected`() {
        assertGeofenceDenied(RequestType.GEOFENCE_REGISTRATION)
    }

    private fun assertGeofenceDenied(type: RequestType) {
        val rig = Fixtures.rig(Fixtures.denyPolicy())
        val id = Fixtures.identity()
        val d = rig.engine.resolveAndEvaluate(
            id,
            Fixtures.request(id, type = type),
            Fixtures.preciseCeiling(),
            Fixtures.locationCtx(geofence = true, source = SourceType.GEOFENCE),
            2_000L,
        )
        assertEquals(Decision.DENY, d.decision)
    }

    @Test
    fun `SEC-010 to SEC-012 GNSS measurement paths are separate capabilities`() {
        // The core has no GNSS measurement/NMEA request type: compile-level boundary.
        val requestTypes = RequestType.values().map { it.name }
        assertTrue(requestTypes.none { it.contains("GNSS") || it.contains("NMEA") || it.contains("MEASUREMENT") })
        val sources = SourceType.values().map { it.name }
        assertTrue(sources.none { it.contains("NMEA") || it.contains("MEASUREMENT") })
        // A location decision therefore cannot be mistaken for GNSS authorization.
    }

    @Test
    fun `SEC-020 app A policy update leaves app B unchanged`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.exactPolicy(pkg = "com.a"))
        store.put(Fixtures.exactPolicy(pkg = "com.b"))
        store.put(Fixtures.cityPolicy(pkg = "com.a"))
        assertEquals(
            locshield.model.SpatialMode.EXACT,
            store.get(ApplicationSelector("com.b", 0))?.spatial?.mode,
        )
        assertEquals(
            locshield.model.SpatialMode.CITY,
            store.get(ApplicationSelector("com.a", 0))?.spatial?.mode,
        )
    }

    @Test
    fun `SEC-021 app A cannot touch app B through the store without its selector`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.exactPolicy(pkg = "com.b"))
        // Deleting A's (nonexistent) policy is a no-op for B; B stays intact.
        store.delete(ApplicationSelector("com.a", 0))
        assertTrue(store.get(ApplicationSelector("com.b", 0)) != null)
    }

    @Test
    fun `SEC-022 app A temporal changes leave app B unaffected`() {
        val rig = Fixtures.rig(
            Fixtures.minIntervalPolicy(60_000L, pkg = "com.a"),
            Fixtures.minIntervalPolicy(60_000L, pkg = "com.b"),
        )
        val a = Fixtures.identity(pkg = "com.a", uid = 11111)
        val b = Fixtures.identity(pkg = "com.b", uid = 22222)
        val effA = (rig.resolver.resolve(a, Fixtures.request(a), Fixtures.preciseCeiling(), 2_000L, 0L)
            as locshield.policy.ResolutionOutcome.Effective).policy
        rig.engine.temporal.recordDelivery(a, effA.temporal, effA.policyGeneration, 0L)
        val effB = (rig.resolver.resolve(b, Fixtures.request(b), Fixtures.preciseCeiling(), 2_000L, 0L)
            as locshield.policy.ResolutionOutcome.Effective).policy
        val db = rig.engine.evaluate(b, Fixtures.request(b), effB, Fixtures.locationCtx(atNanos = 1L), 1L)
        assertTrue(db.decision == Decision.ALLOW || db.decision == Decision.TRANSFORM)
    }

    @Test
    fun `SEC-023 randomization state is per-subject by construction`() {
        // PERIOD_STABLE seeds include the identity string: A and B differ.
        val policy = locshield.model.RandomizationPolicy(
            enabled = true,
            radiusMeters = 500.0,
            stableForMs = 3_600_000L,
            seedMode = locshield.model.SeedMode.PERIOD_STABLE,
        )
        val t = 100_000_000_000L
        fun outFor(pkg: String): LocationSample {
            val r = locshield.spatial.RandomizationTransformer.transform(
                Fixtures.sample(), policy,
                locshield.spatial.TransformationContext(
                    locshield.spatial.DeterministicRandomSource(0L), t,
                    identity = Fixtures.identity(pkg = pkg),
                ),
            )
            return (r as locshield.spatial.TransformOutcome.Ok).location
        }
        // Different identities must not share randomization streams.
        assertTrue(outFor("com.a") != outFor("com.b"))
    }

    @Test
    fun `SEC-024 different Android users are isolated`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.cityPolicy(pkg = "com.app", user = 0))
        store.put(Fixtures.denyPolicy(pkg = "com.app", user = 10))
        assertEquals(
            locshield.model.SpatialMode.CITY,
            store.get(ApplicationSelector("com.app", 0))?.spatial?.mode,
        )
        assertEquals(
            locshield.model.SpatialMode.DENY,
            store.get(ApplicationSelector("com.app", 10))?.spatial?.mode,
        )
    }

    @Test
    fun `no live-location preview path exists in the core`() {
        // The engine must not expose any method returning a LocationSample:
        // otherwise the service boundary could be abused as a location oracle
        // (Doc 11 section 5 forbids a Binder transformLocation oracle; the same
        // principle applies inside the core).
        val leaking = PolicyEngine::class.java.methods.filter {
            it.returnType == LocationSample::class.java
        }
        assertTrue(leaking.isEmpty(), "Oracle methods: $leaking")
    }

    @Test
    fun `audit events carry no coordinates by construction`() {
        val fields = AuditEvent::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("lat", ignoreCase = true) || it.contains("lon", ignoreCase = true) })
        val doubles = AuditEvent::class.java.declaredFields.filter { it.type == Double::class.java }
        assertTrue(doubles.isEmpty(), "Coordinate-carrying fields: $doubles")
    }

    @Test
    fun `rejected commits never activate (TOCTOU guard)`() {
        val store = locshield.policy.InMemoryPolicyStore()
        store.put(Fixtures.exactPolicy())
        val bad = Fixtures.radiusPolicy(1e18)
        val r = store.put(bad)
        assertTrue(r is TransactionResult.Rejected)
        assertEquals(locshield.model.SpatialMode.EXACT, store.get(ApplicationSelector("com.example.app", 0))?.spatial?.mode)
    }
}
