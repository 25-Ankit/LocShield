package locshield

import locshield.metadata.MetadataSanitizer
import locshield.model.FieldHandling
import locshield.model.MetadataPolicy
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Metadata tests (Doc 13 Table 12, MET-001..MET-009; Doc 12 Table 8).
 */
class MetadataTests {
    private fun sanitize(
        policy: MetadataPolicy = MetadataPolicy(),
        spatial: SpatialPolicy = SpatialPolicy(SpatialMode.EXACT),
        sample: locshield.model.LocationSample = Fixtures.sample(),
    ): locshield.model.LocationSample {
        val out = MetadataSanitizer.sanitize(sample, spatial, policy)
        assertTrue(out is MetadataSanitizer.SanitizeOutcome.Ok, "Expected Ok, was $out")
        return (out as MetadataSanitizer.SanitizeOutcome.Ok).location
    }

    @Test
    fun `MET-001 accuracy never more precise than spatial policy`() {
        val coarse = sanitize(
            spatial = SpatialPolicy(SpatialMode.GRID, gridMeters = 2_000.0),
            sample = Fixtures.sample(accuracy = 5f),
        )
        assertTrue(coarse.accuracyMeters >= 2_000f)
        val city = sanitize(
            spatial = SpatialPolicy(SpatialMode.CITY),
            sample = Fixtures.sample(accuracy = 5f),
        )
        assertTrue(city.accuracyMeters >= 5_000f)
    }

    @Test
    fun `accuracy floor overrides RETAIN metadata setting (P6)`() {
        val retainAll = MetadataPolicy(
            accuracyMode = FieldHandling.RETAIN,
            timestampMode = FieldHandling.RETAIN,
            elapsedRealtimeMode = FieldHandling.RETAIN,
            altitudeMode = FieldHandling.RETAIN,
            speedMode = FieldHandling.RETAIN,
            bearingMode = FieldHandling.RETAIN,
            providerMode = FieldHandling.RETAIN,
            extrasMode = FieldHandling.RETAIN,
        )
        val out = sanitize(
            policy = retainAll,
            spatial = SpatialPolicy(SpatialMode.GRID, gridMeters = 1_000.0),
            sample = Fixtures.sample(accuracy = 4f),
        )
        assertTrue(out.accuracyMeters >= 1_000f, "Invariant beats RETAIN")
    }

    @Test
    fun `MET-002 timestamp coarsened when configured`() {
        val out = sanitize(
            policy = MetadataPolicy(timestampMode = FieldHandling.COARSE),
            sample = Fixtures.sample().copy(timestampWallMs = 1_700_000_000_123L),
        )
        assertEquals(1_700_000_000_123L / 60_000L * 60_000L, out.timestampWallMs)
    }

    @Test
    fun `MET-003 elapsed realtime restricted when configured`() {
        val out = sanitize(
            policy = MetadataPolicy(elapsedRealtimeMode = FieldHandling.DENY),
        )
        assertEquals(0L, out.elapsedRealtimeNanos)
        val coarse = sanitize(
            policy = MetadataPolicy(elapsedRealtimeMode = FieldHandling.COARSE),
            sample = Fixtures.sample().copy(elapsedRealtimeNanos = 61_500_000_000L),
        )
        assertEquals(60_000_000_000L, coarse.elapsedRealtimeNanos)
    }

    @Test
    fun `MET-004 altitude denied or coarsened when required`() {
        assertNull(sanitize(policy = MetadataPolicy(altitudeMode = FieldHandling.DENY)).altitudeMeters)
        val coarse = sanitize(
            policy = MetadataPolicy(altitudeMode = FieldHandling.COARSE),
            sample = Fixtures.sample().copy(altitudeMeters = 123.0),
        )
        assertEquals(100.0, coarse.altitudeMeters)
    }

    @Test
    fun `MET-005 speed denied when not permitted`() {
        assertNull(sanitize(policy = MetadataPolicy(speedMode = FieldHandling.DENY)).speedMps)
        val coarse = sanitize(
            policy = MetadataPolicy(speedMode = FieldHandling.COARSE),
            sample = Fixtures.sample().copy(speedMps = 12.0f),
        )
        assertEquals(10.0f, coarse.speedMps)
    }

    @Test
    fun `MET-006 bearing denied when not permitted`() {
        assertNull(sanitize(policy = MetadataPolicy(bearingMode = FieldHandling.DENY)).bearingDeg)
        val coarse = sanitize(
            policy = MetadataPolicy(bearingMode = FieldHandling.COARSE),
            sample = Fixtures.sample().copy(bearingDeg = 100.0f),
        )
        assertEquals(90.0f, coarse.bearingDeg)
    }

    @Test
    fun `MET-007 provider filtered when restricted`() {
        assertNull(sanitize(policy = MetadataPolicy(providerMode = FieldHandling.DENY)).provider)
        assertEquals(
            "coarse",
            sanitize(policy = MetadataPolicy(providerMode = FieldHandling.COARSE)).provider,
        )
    }

    @Test
    fun `MET-008 unknown sensitive extras stripped`() {
        val out = sanitize(policy = MetadataPolicy(extrasMode = FieldHandling.DENY))
        assertTrue(out.extras.isEmpty())
        val coarse = sanitize(policy = MetadataPolicy(extrasMode = FieldHandling.COARSE))
        assertTrue(coarse.extras.isEmpty(), "v0.1 allowlist is empty: COARSE strips")
    }

    @Test
    fun `MET-009 combined metadata never contradicts policy`() {
        val out = sanitize(
            policy = Fixtures.strictMetadata(),
            spatial = SpatialPolicy(SpatialMode.CITY),
        )
        assertTrue(out.accuracyMeters >= 5_000f)
        assertNull(out.speedMps)
        assertNull(out.bearingDeg)
        assertNull(out.altitudeMeters)
        assertTrue(out.extras.isEmpty())
    }

    @Test
    fun `RETAIN preserves fields under EXACT spatial policy`() {
        val retainAll = MetadataPolicy(
            accuracyMode = FieldHandling.RETAIN,
            timestampMode = FieldHandling.RETAIN,
            elapsedRealtimeMode = FieldHandling.RETAIN,
            altitudeMode = FieldHandling.RETAIN,
            speedMode = FieldHandling.RETAIN,
            bearingMode = FieldHandling.RETAIN,
            providerMode = FieldHandling.RETAIN,
            extrasMode = FieldHandling.RETAIN,
        )
        val input = Fixtures.sample()
        val out = sanitize(policy = retainAll, spatial = SpatialPolicy(SpatialMode.EXACT), sample = input)
        assertEquals(input, out)
    }

    @Test
    fun `corrupt input fails sanitization closed`() {
        val bad = Fixtures.sample().copy(latitude = Double.NaN)
        val out = MetadataSanitizer.sanitize(bad, SpatialPolicy(SpatialMode.CITY), MetadataPolicy())
        assertTrue(out is MetadataSanitizer.SanitizeOutcome.Failed)
    }
}
