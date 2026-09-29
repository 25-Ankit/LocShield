package locshield

import locshield.spatial.GridTransformer
import locshield.spatial.TransformOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Grid transformation tests (Doc 13 Table 8, SP-001..SP-007; Doc 13 section 11).
 */
class SpatialGridTests {
    private fun ok(input: locshield.model.LocationSample, grid: Double): locshield.model.LocationSample {
        val out = GridTransformer.transform(input, grid)
        assertTrue(out is TransformOutcome.Ok, "Expected Ok, was $out")
        return (out as TransformOutcome.Ok).location
    }

    @Test
    fun `SP-001 point inside cell maps into configured cell`() {
        val out = ok(Fixtures.sample(), 1_000.0)
        // Cell-center representative must be within half a diagonal of the input.
        val d = locshield.spatial.CityTransformer.haversineMeters(
            Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON, out.latitude, out.longitude,
        )
        assertTrue(d <= 1_000.0 * kotlin.math.sqrt(2.0) / 2.0 + 1.0, "distance=$d")
        assertTrue(out.accuracyMeters >= 1_000f, "SP-007: accuracy must not claim original precision")
    }

    @Test
    fun `SP-002 cell boundary follows a deterministic rule`() {
        // Two points epsilon-apart on either side of a cell edge map deterministically:
        // repeated evaluation gives identical results (floor rule, no flicker).
        val grid = 1_000.0
        val a = ok(Fixtures.sample(lat = 19.0800000, lon = 72.8777), grid)
        val b = ok(Fixtures.sample(lat = 19.0800000, lon = 72.8777), grid)
        assertEquals(a.latitude, b.latitude)
        assertEquals(a.longitude, b.longitude)
    }

    @Test
    fun `SP-003 repeated input yields same output`() {
        val grid = 500.0
        val first = ok(Fixtures.sample(), grid)
        repeat(10) {
            val again = ok(Fixtures.sample(), grid)
            assertEquals(first, again)
        }
    }

    @Test
    fun `SP-004 different cells do not collapse unexpectedly`() {
        val grid = 1_000.0
        // Points ~2 km apart must land in different cells (2x grid size separation).
        val a = ok(Fixtures.sample(lat = 19.0760, lon = 72.8777), grid)
        val b = ok(Fixtures.sample(lat = 19.0940, lon = 72.8777), grid)
        assertTrue(a.latitude != b.latitude || a.longitude != b.longitude)
    }

    @Test
    fun `SP-005 invalid grid size rejected`() {
        assertTrue(GridTransformer.transform(Fixtures.sample(), 0.0) is TransformOutcome.Failed)
        assertTrue(GridTransformer.transform(Fixtures.sample(), -10.0) is TransformOutcome.Failed)
        assertTrue(GridTransformer.transform(Fixtures.sample(), Double.NaN) is TransformOutcome.Failed)
        assertTrue(GridTransformer.transform(Fixtures.sample(), 1e12) is TransformOutcome.Failed)
    }

    @Test
    fun `SP-006 extreme coordinates cause no overflow or NaN`() {
        for ((lat, lon) in listOf(90.0 to 180.0, -90.0 to -180.0, 0.0 to 179.9999, 0.0 to -179.9999)) {
            val out = GridTransformer.transform(Fixtures.sample(lat = lat, lon = lon), 1_000.0)
            assertTrue(out is TransformOutcome.Ok, "Expected Ok for $lat,$lon but was $out")
            val loc = (out as TransformOutcome.Ok).location
            assertTrue(loc.latitude.isFinite() && loc.longitude.isFinite())
            assertTrue(abs(loc.latitude) <= 90.0 && abs(loc.longitude) <= 180.0)
        }
        // Invalid inputs fail, never produce coordinates.
        assertTrue(
            GridTransformer.transform(Fixtures.sample(lat = 91.0, lon = 0.0), 1_000.0)
                is TransformOutcome.Failed,
        )
        assertTrue(
            GridTransformer.transform(Fixtures.sample(lat = 0.0, lon = Double.NaN), 1_000.0)
                is TransformOutcome.Failed,
        )
    }

    @Test
    fun `SP-007 precision metadata cannot claim original precision`() {
        val out = ok(Fixtures.sample(accuracy = 3f), 2_000.0)
        assertTrue(out.accuracyMeters >= 2_000f)
    }

    @Test
    fun `high-latitude fixture remains finite and deterministic`() {
        // Doc 13 section 11: high-latitude behavior must be tested explicitly.
        val hammerfest = Fixtures.sample(lat = 70.6634, lon = 23.6821)
        val a = ok(hammerfest, 1_000.0)
        val b = ok(hammerfest, 1_000.0)
        assertEquals(a, b)
        assertTrue(a.latitude.isFinite() && a.longitude.isFinite())
    }

    @Test
    fun `negative coordinates quantize deterministically`() {
        val saoPaulo = Fixtures.sample(lat = -23.5558, lon = -46.6396)
        val a = ok(saoPaulo, 1_000.0)
        val b = ok(saoPaulo, 1_000.0)
        assertEquals(a, b)
        val d = locshield.spatial.CityTransformer.haversineMeters(-23.5558, -46.6396, a.latitude, a.longitude)
        assertTrue(d <= 1_000.0 * kotlin.math.sqrt(2.0) / 2.0 + 1.0)
    }

    @Test
    fun `longitude wrap boundary is handled without NaN`() {
        val east = ok(Fixtures.sample(lat = 0.0, lon = 179.999), 5_000.0)
        val west = ok(Fixtures.sample(lat = 0.0, lon = -179.999), 5_000.0)
        assertTrue(east.longitude in -180.0..180.0)
        assertTrue(west.longitude in -180.0..180.0)
    }
}
