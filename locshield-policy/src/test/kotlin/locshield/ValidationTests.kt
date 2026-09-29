package locshield

import locshield.model.AppPolicy
import locshield.model.BackgroundMode
import locshield.model.BackgroundPolicy
import locshield.model.MetadataPolicy
import locshield.model.RandomizationPolicy
import locshield.model.ReasonCode
import locshield.model.SeedMode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.model.ValidationResult
import locshield.policy.PolicyValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Policy validation tests (Doc 13 Table 4, VAL-001..VAL-014).
 * Every rejection is explicit; nothing invalid can become permissive.
 */
class ValidationTests {
    private fun assertAccepted(policy: AppPolicy) {
        assertTrue(PolicyValidator.validate(policy) is ValidationResult.Accepted, "Expected Accepted")
    }

    private fun assertRejected(policy: AppPolicy, id: String) {
        val r = PolicyValidator.validate(policy)
        assertTrue(r is ValidationResult.Rejected, "$id: expected Rejected but was $r")
    }

    @Test
    fun `VAL-001 valid schema v1 accepted`() {
        assertAccepted(Fixtures.exactPolicy())
    }

    @Test
    fun `VAL-002 unknown schema version rejected`() {
        assertRejected(Fixtures.exactPolicy().copy(schemaVersion = 999), "VAL-002")
        assertRejected(Fixtures.exactPolicy().copy(schemaVersion = 0), "VAL-002-zero")
        assertRejected(Fixtures.exactPolicy().copy(schemaVersion = -1), "VAL-002-neg")
    }

    @Test
    fun `VAL-003 missing required field rejected`() {
        // RADIUS without its bound.
        assertRejected(Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.RADIUS)), "VAL-003-radius")
        // GRID without its bound.
        assertRejected(Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.GRID)), "VAL-003-grid")
        // MIN_INTERVAL without interval.
        assertRejected(
            Fixtures.basePolicy().copy(temporal = TemporalPolicy(TemporalMode.MIN_INTERVAL)),
            "VAL-003-interval",
        )
        // RATE_LIMIT without window params.
        assertRejected(
            Fixtures.basePolicy().copy(temporal = TemporalPolicy(TemporalMode.RATE_LIMIT)),
            "VAL-003-ratelimit",
        )
        // Enabled randomization without bound.
        assertRejected(
            Fixtures.basePolicy().copy(randomization = RandomizationPolicy(enabled = true)),
            "VAL-003-random",
        )
    }

    @Test
    fun `VAL-004 negative radius rejected`() {
        assertRejected(Fixtures.radiusPolicy(-50.0), "VAL-004")
        assertRejected(
            Fixtures.basePolicy().copy(
                randomization = RandomizationPolicy(enabled = true, radiusMeters = -1.0),
            ),
            "VAL-004-random",
        )
    }

    @Test
    fun `VAL-005 invalid zero radius rejected`() {
        assertRejected(Fixtures.radiusPolicy(0.0), "VAL-005")
        assertRejected(Fixtures.gridPolicy(0.0), "VAL-005-grid")
    }

    @Test
    fun `VAL-006 negative interval rejected`() {
        assertRejected(Fixtures.minIntervalPolicy(-1L), "VAL-006")
        assertRejected(
            Fixtures.basePolicy().copy(
                temporal = TemporalPolicy(TemporalMode.PERIODIC, periodicIntervalMs = -5L),
            ),
            "VAL-006-periodic",
        )
    }

    @Test
    fun `VAL-007 overflowing interval rejected`() {
        assertRejected(Fixtures.minIntervalPolicy(Long.MAX_VALUE), "VAL-007")
        assertRejected(
            Fixtures.basePolicy().copy(
                temporal = TemporalPolicy(
                    TemporalMode.RATE_LIMIT,
                    maxDeliveriesPerWindow = 5,
                    windowMs = Long.MAX_VALUE,
                ),
            ),
            "VAL-007-window",
        )
    }

    @Test
    fun `VAL-008 unknown enum rejected at strict parsers`() {
        assertEquals(null, locshield.model.spatialModeOf("APPROXIMATE"))
        assertEquals(null, locshield.model.spatialModeOf("REGION"))
        assertEquals(null, locshield.model.temporalModeOf("HOURLY"))
        assertEquals(null, locshield.model.backgroundModeOf("ALLOW"))
        assertEquals(null, locshield.model.seedModeOf("RANDOM"))
        assertEquals(null, locshield.model.fieldHandlingOf("HIDE"))
        // Known values still parse.
        assertEquals(SpatialMode.CITY, locshield.model.spatialModeOf("CITY"))
    }

    @Test
    fun `VAL-009 invalid package user pair rejected`() {
        assertRejected(Fixtures.exactPolicy(pkg = "  "), "VAL-009-blank")
        assertRejected(Fixtures.exactPolicy(pkg = ""), "VAL-009-empty")
        assertRejected(Fixtures.basePolicy(pkg = "com.x", user = -1), "VAL-009-user")
    }

    @Test
    fun `VAL-010 expiration before creation rejected`() {
        assertRejected(
            Fixtures.exactPolicy().copy(createdAtWallMs = 5_000L, expiresAtWallMs = 5_000L),
            "VAL-010-equal",
        )
        assertRejected(
            Fixtures.exactPolicy().copy(createdAtWallMs = 5_000L, expiresAtWallMs = 1_000L),
            "VAL-010-before",
        )
        // Valid future expiration accepted.
        assertAccepted(Fixtures.exactPolicy().copy(expiresAtWallMs = 9_999_999L))
    }

    @Test
    fun `VAL-011 contradictory fields rejected`() {
        // EXACT carrying a radius.
        assertRejected(
            Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.EXACT, radiusMeters = 100.0)),
            "VAL-011-exact-radius",
        )
        // DENY carrying a grid.
        assertRejected(
            Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.DENY, gridMeters = 100.0)),
            "VAL-011-deny-grid",
        )
        // RADIUS carrying a grid.
        assertRejected(
            Fixtures.basePolicy(
                spatial = SpatialPolicy(SpatialMode.RADIUS, radiusMeters = 100.0, gridMeters = 100.0),
            ),
            "VAL-011-radius-grid",
        )
        // REALTIME carrying an interval.
        assertRejected(
            Fixtures.basePolicy().copy(
                temporal = TemporalPolicy(TemporalMode.REALTIME, minimumIntervalMs = 60_000L),
            ),
            "VAL-011-realtime-interval",
        )
        // Disabled randomization carrying parameters.
        assertRejected(
            Fixtures.basePolicy().copy(
                randomization = RandomizationPolicy(enabled = false, radiusMeters = 100.0),
            ),
            "VAL-011-disabled-random",
        )
    }

    @Test
    fun `VAL-012 valid DENY policy accepted`() {
        assertAccepted(Fixtures.denyPolicy())
    }

    @Test
    fun `VAL-013 valid CITY policy accepted`() {
        assertAccepted(Fixtures.cityPolicy())
        assertAccepted(Fixtures.cityPolicy().copy(spatial = SpatialPolicy(SpatialMode.CITY, cityId = "mumbai")))
    }

    @Test
    fun `VAL-014 valid randomized policy accepted`() {
        assertAccepted(Fixtures.randomizedPolicy())
        assertAccepted(
            Fixtures.basePolicy(spatial = SpatialPolicy(SpatialMode.EXACT)).copy(
                randomization = RandomizationPolicy(
                    enabled = true,
                    radiusMeters = 250.0,
                    stableForMs = 3_600_000L,
                    seedMode = SeedMode.PERIOD_STABLE,
                ),
            ),
        )
    }

    @Test
    fun `NaN and infinite bounds rejected, never accepted`() {
        assertRejected(Fixtures.radiusPolicy(Double.NaN), "nan-radius")
        assertRejected(Fixtures.radiusPolicy(Double.POSITIVE_INFINITY), "inf-radius")
        assertRejected(Fixtures.gridPolicy(Double.NaN), "nan-grid")
    }

    @Test
    fun `rejection carries a stable reason code`() {
        val r = PolicyValidator.validate(Fixtures.radiusPolicy(-1.0))
        assertTrue(r is ValidationResult.Rejected)
        assertEquals(ReasonCode.VALUE_OUT_OF_BOUNDS, (r as ValidationResult.Rejected).reason)
    }

    @Test
    fun `background modes are closed vocabulary`() {
        for (mode in BackgroundMode.values()) {
            assertTrue(PolicyValidator.validateBackgroundMode(mode) is ValidationResult.Accepted)
        }
        // Default-constructed metadata policy is structurally valid.
        assertAccepted(Fixtures.basePolicy().copy(metadata = MetadataPolicy()))
    }
}
