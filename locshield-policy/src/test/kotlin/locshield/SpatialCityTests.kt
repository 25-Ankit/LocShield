package locshield

import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.spatial.CityTransformer
import locshield.spatial.TransformOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CITY transformation tests (Doc 13 Table 10, SP-020..SP-024; Doc 12 section 15).
 */
class SpatialCityTests {
    private fun ok(lat: Double, lon: Double, cityId: String? = null): locshield.model.LocationSample {
        val out = CityTransformer.transform(Fixtures.sample(lat = lat, lon = lon), cityId)
        assertTrue(out is TransformOutcome.Ok, "Expected Ok, was $out")
        return (out as TransformOutcome.Ok).location
    }

    @Test
    fun `SP-020 known city fixture yields deterministic city representation`() {
        val a = ok(Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON)
        assertEquals("mumbai", a.cityId)
        assertEquals(19.0760, a.latitude)
        assertEquals(72.8777, a.longitude)
        assertTrue(a.accuracyMeters >= 5_000f)
        val b = ok(Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON)
        assertEquals(a, b)
    }

    @Test
    fun `SP-021 unknown region uses defined fallback`() {
        // Mid-Pacific: no fixture city within 50 km -> deterministic coarse fallback.
        val a = ok(0.0, -150.0)
        assertEquals(null, a.cityId)
        assertTrue(a.accuracyMeters >= 20_000f)
        val b = ok(0.0, -150.0)
        assertEquals(a, b)
    }

    @Test
    fun `SP-022 city boundary rule is deterministic`() {
        // A point far from every fixture city deterministically takes the fallback…
        val mid = ok(23.8, 74.9)
        val again = ok(23.8, 74.9)
        assertEquals(mid, again)
        // …while a point near Mumbai (but not at its center) deterministically
        // resolves to Mumbai.
        val near = ok(19.2000, 72.9500)
        assertEquals("mumbai", near.cityId)
        assertEquals(near, ok(19.2000, 72.9500))
    }

    @Test
    fun `SP-023 offline fixture works with no network`() {
        // No network access exists in this core by construction; the table is in-memory.
        assertTrue(CityTransformer.table.isNotEmpty())
        val out = ok(51.5074, -0.1278)
        assertEquals("london", out.cityId)
    }

    @Test
    fun `SP-024 no precise coordinate metadata leaks from CITY`() {
        val out = ok(Fixtures.MUMBAI_LAT, Fixtures.MUMBAI_LON)
        // City center is returned, not the raw input (unless input == center).
        assertTrue(out.accuracyMeters >= 5_000f)
        assertEquals(null, out.extras["raw_lat"])
    }

    @Test
    fun `CITY never truncates decimals - nearby points share one representation`() {
        // Two distinct nearby points in Mumbai map to the SAME city representation,
        // which decimal truncation would not guarantee.
        val a = ok(19.0800, 72.8800)
        val b = ok(19.0700, 72.8700)
        assertEquals(a, b)
        assertEquals("mumbai", a.cityId)
    }

    @Test
    fun `pinned cityId resolves pinned city`() {
        val out = ok(0.0, 0.0, cityId = "tokyo")
        assertEquals("tokyo", out.cityId)
        assertEquals(35.6762, out.latitude)
    }

    @Test
    fun `unknown pinned cityId fails closed`() {
        val out = CityTransformer.transform(Fixtures.sample(), "atlantis")
        assertTrue(out is TransformOutcome.Failed)
    }

    @Test
    fun `invalid coordinates fail closed`() {
        assertTrue(
            CityTransformer.transform(Fixtures.sample(lat = Double.NaN, lon = 0.0)) is TransformOutcome.Failed,
        )
    }

    @Test
    fun `engine dispatches CITY through the facade`() {
        val out = locshield.spatial.TransformationEngine.transform(
            Fixtures.sample(),
            SpatialPolicy(SpatialMode.CITY),
            locshield.model.RandomizationPolicy(enabled = false),
            Fixtures.transformCtx(),
        )
        assertTrue(out is TransformOutcome.Ok)
        assertEquals("mumbai", (out as TransformOutcome.Ok).location.cityId)
    }
}
