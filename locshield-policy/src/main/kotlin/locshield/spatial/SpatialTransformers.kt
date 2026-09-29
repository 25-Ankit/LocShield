package locshield.spatial

import locshield.model.AppIdentity
import locshield.model.LocationSample
import locshield.model.PrototypeConstants
import locshield.model.RandomizationPolicy
import locshield.model.ReasonCode
import locshield.model.SeedMode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Injectable randomness (Doc 12 section 16, Doc 13 Table 2: "Randomness:
 * injectable deterministic RNG"). The core never touches global RNG state, so
 * tests are reproducible and production can supply a secure source.
 */
interface RandomSource {
    /** Uniform double in [0, 1). */
    fun nextDouble(): Double
}

/** Production source backed by `java.security.SecureRandom`. */
class SecureRandomSource : RandomSource {
    private val secure = java.security.SecureRandom()

    override fun nextDouble(): Double = secure.nextDouble()
}

/** Deterministic source for tests and reproducible experiments (Doc 05 FR-104). */
class DeterministicRandomSource(seed: Long) : RandomSource {
    private val random = kotlin.random.Random(seed)

    override fun nextDouble(): Double = random.nextDouble()
}

/** Context for a single transformation call. */
data class TransformationContext(
    val random: RandomSource,
    val nowElapsedNanos: Long,
    /** Required for [SeedMode.SESSION_STABLE]; supplied by the adapter. */
    val sessionId: String? = null,
    /** Used only for stable-seed derivation (never raw coordinates). */
    val identity: AppIdentity? = null,
)

/** Transformation result. Failures carry a reason and NEVER the raw input. */
sealed interface TransformOutcome {
    data class Ok(val location: LocationSample) : TransformOutcome

    data class Failed(val reason: ReasonCode) : TransformOutcome
}

/**
 * Shared metric projection helpers.
 *
 * The grid is computed in a local equirectangular meter space anchored at each
 * point's own latitude (x = R*lon*cos(lat), y = R*lat). This is deterministic,
 * globally stable (same input always maps to the same cell), and approximately
 * metric - unlike naive degree rounding, which the spec forbids as a
 * uniform-meter privacy guarantee (Doc 12 section 13, Doc 13 section 11).
 * Near the poles cos(lat) is floored at cos(89.9deg) to avoid division blowup;
 * cells there are documented as coarse (see high-latitude tests).
 */
internal object MetricGrid {
    private const val MIN_COS_LAT = 0.00174532925199433 // cos(89.9deg)

    fun cosLat(latDeg: Double): Double = maxOf(cos(latDeg * PI / 180.0), MIN_COS_LAT)

    fun toMeters(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val latRad = latDeg * PI / 180.0
        val lonRad = lonDeg * PI / 180.0
        return Pair(
            PrototypeConstants.EARTH_RADIUS_METERS * lonRad * cosLat(latDeg),
            PrototypeConstants.EARTH_RADIUS_METERS * latRad,
        )
    }

    fun toDegrees(x: Double, y: Double, refLatDeg: Double): Pair<Double, Double> {
        val lat = (y / PrototypeConstants.EARTH_RADIUS_METERS) * 180.0 / PI
        val lon = (x / (PrototypeConstants.EARTH_RADIUS_METERS * cosLat(refLatDeg))) * 180.0 / PI
        return Pair(lat.coerceIn(-90.0, 90.0), wrapLongitude(lon))
    }

    fun wrapLongitude(lon: Double): Double {
        var v = lon % 360.0
        if (v > 180.0) v -= 360.0
        if (v <= -180.0) v += 360.0
        return v
    }

    /** Deterministic cell-center snap with cell size [cellMeters]. */
    fun snapToCellCenter(latDeg: Double, lonDeg: Double, cellMeters: Double): Pair<Double, Double> {
        val (x, y) = toMeters(latDeg, lonDeg)
        val cx = (floor(x / cellMeters) + 0.5) * cellMeters
        val cy = (floor(y / cellMeters) + 0.5) * cellMeters
        return toDegrees(cx, cy, latDeg)
    }
}

/** GRID transformer (Doc 12 section 13). Deterministic cell quantization. */
object GridTransformer {
    fun transform(input: LocationSample, gridMeters: Double): TransformOutcome {
        if (!input.hasValidCoordinate()) {
            return TransformOutcome.Failed(ReasonCode.INVALID_VALUE)
        }
        if (!gridMeters.isFinite() || gridMeters < PrototypeConstants.MIN_GRID_METERS ||
            gridMeters > PrototypeConstants.MAX_GRID_METERS
        ) {
            return TransformOutcome.Failed(ReasonCode.VALUE_OUT_OF_BOUNDS)
        }
        return try {
            val (lat, lon) = MetricGrid.snapToCellCenter(input.latitude, input.longitude, gridMeters)
            TransformOutcome.Ok(
                input.copy(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = maxOf(input.accuracyMeters, gridMeters.toFloat()),
                    cityId = null,
                ),
            )
        } catch (_: Exception) {
            TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
        }
    }
}

/**
 * RADIUS transformer (Doc 12 section 14).
 *
 * Prototype semantics (frozen for v0.1, see IMPLEMENTATION_NOTES.md): deterministic
 * snap to a metric grid of side R with the cell center as representative. The worst
 * case distance from the true point is R*sqrt(2)/2 < R, satisfying the bounded
 * uncertainty invariant "output remains within defined privacy geometry".
 */
object RadiusTransformer {
    fun transform(input: LocationSample, radiusMeters: Double): TransformOutcome {
        if (!input.hasValidCoordinate()) {
            return TransformOutcome.Failed(ReasonCode.INVALID_VALUE)
        }
        if (!radiusMeters.isFinite() || radiusMeters < PrototypeConstants.MIN_RADIUS_METERS ||
            radiusMeters > PrototypeConstants.MAX_RADIUS_METERS
        ) {
            return TransformOutcome.Failed(ReasonCode.VALUE_OUT_OF_BOUNDS)
        }
        return try {
            val (lat, lon) = MetricGrid.snapToCellCenter(input.latitude, input.longitude, radiusMeters)
            TransformOutcome.Ok(
                input.copy(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = maxOf(input.accuracyMeters, radiusMeters.toFloat()),
                    cityId = null,
                ),
            )
        } catch (_: Exception) {
            TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
        }
    }
}

/** Offline city fixture entry (Doc 12 section 15: deterministic offline table). */
data class CityEntry(val cityId: String, val latitude: Double, val longitude: Double)

/**
 * CITY transformer (Doc 12 section 15). Maps a coordinate to a city-level
 * representation via a deterministic offline region table - never by truncating
 * decimal digits, and never via network geocoding (Doc 12 section 38).
 */
object CityTransformer {
    /** Minimal v0.1 fixture table (see IMPLEMENTATION_NOTES.md). */
    val table: List<CityEntry> = listOf(
        CityEntry("mumbai", 19.0760, 72.8777),
        CityEntry("delhi", 28.6139, 77.2090),
        CityEntry("london", 51.5074, -0.1278),
        CityEntry("new_york", 40.7128, -74.0060),
        CityEntry("sydney", -33.8688, 151.2093),
        CityEntry("sao_paulo", -23.5558, -46.6396),
        CityEntry("tokyo", 35.6762, 139.6503),
        CityEntry("berlin", 52.5200, 13.4050),
    )

    fun transform(input: LocationSample, pinnedCityId: String? = null): TransformOutcome {
        if (!input.hasValidCoordinate()) {
            return TransformOutcome.Failed(ReasonCode.INVALID_VALUE)
        }
        return try {
            if (pinnedCityId != null) {
                val pinned = table.firstOrNull { it.cityId == pinnedCityId }
                    ?: return TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
                return TransformOutcome.Ok(cityOutput(input, pinned))
            }
            var best: CityEntry? = null
            var bestDist = Double.POSITIVE_INFINITY
            for (city in table) {
                val d = haversineMeters(input.latitude, input.longitude, city.latitude, city.longitude)
                if (d < bestDist) {
                    bestDist = d
                    best = city
                }
            }
            if (best != null && bestDist <= PrototypeConstants.CITY_MATCH_RADIUS_METERS) {
                return TransformOutcome.Ok(cityOutput(input, best))
            }
            // SP-021 defined fallback: deterministic coarse grid, no city attribution.
            val (lat, lon) = MetricGrid.snapToCellCenter(
                input.latitude,
                input.longitude,
                PrototypeConstants.CITY_FALLBACK_GRID_METERS,
            )
            TransformOutcome.Ok(
                input.copy(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = maxOf(
                        input.accuracyMeters,
                        PrototypeConstants.CITY_FALLBACK_GRID_METERS.toFloat(),
                    ),
                    cityId = null,
                ),
            )
        } catch (_: Exception) {
            TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
        }
    }

    private fun cityOutput(input: LocationSample, city: CityEntry): LocationSample =
        input.copy(
            latitude = city.latitude,
            longitude = city.longitude,
            accuracyMeters = maxOf(input.accuracyMeters, PrototypeConstants.CITY_ACCURACY_METERS),
            cityId = city.cityId,
        )

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = PrototypeConstants.EARTH_RADIUS_METERS
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * kotlin.math.asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * RANDOMIZED transformer (Doc 12 section 16). Perturbs within a configured bound.
 * The seed is NEVER derived from raw coordinates - only from identity, period
 * index, or session id - so the output cannot leak the original point through
 * deterministic misuse (Doc 12 section 39 checklist).
 */
object RandomizationTransformer {
    fun transform(
        input: LocationSample,
        policy: RandomizationPolicy,
        ctx: TransformationContext,
    ): TransformOutcome {
        if (!input.hasValidCoordinate()) {
            return TransformOutcome.Failed(ReasonCode.INVALID_VALUE)
        }
        val radius = policy.radiusMeters
        if (!policy.enabled || radius == null || !radius.isFinite() ||
            radius < PrototypeConstants.MIN_RADIUS_METERS || radius > PrototypeConstants.MAX_RADIUS_METERS
        ) {
            return TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
        }
        return try {
            val draw: RandomSource = when (policy.seedMode) {
                SeedMode.PER_DELIVERY, SeedMode.SYSTEM_MANAGED -> ctx.random
                SeedMode.PERIOD_STABLE -> {
                    val stableMs = policy.stableForMs
                        ?: return TransformOutcome.Failed(ReasonCode.MISSING_FIELD)
                    if (stableMs <= 0) return TransformOutcome.Failed(ReasonCode.INVALID_VALUE)
                    val period = nanosToMillisSaturated(ctx.nowElapsedNanos) / stableMs
                    DeterministicRandomSource(stableSeed(ctx.identity?.toString() ?: "unknown", period))
                }
                SeedMode.SESSION_STABLE -> {
                    val session = ctx.sessionId
                        ?: return TransformOutcome.Failed(ReasonCode.MISSING_FIELD)
                    DeterministicRandomSource(stableSeed(ctx.identity?.toString() ?: "unknown", session))
                }
            }
            val u: Double
            val v: Double
            try {
                u = draw.nextDouble()
                v = draw.nextDouble()
            } catch (_: Exception) {
                return TransformOutcome.Failed(ReasonCode.RNG_FAILURE)
            }
            if (!u.isFinite() || !v.isFinite() || u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) {
                return TransformOutcome.Failed(ReasonCode.RNG_FAILURE)
            }
            val dist = radius * sqrt(u)
            val angle = 2 * PI * v
            val east = dist * cos(angle)
            val north = dist * sin(angle)
            val cosLat = MetricGrid.cosLat(input.latitude)
            val dLat = (north / PrototypeConstants.EARTH_RADIUS_METERS) * 180.0 / PI
            val dLon = (east / (PrototypeConstants.EARTH_RADIUS_METERS * cosLat)) * 180.0 / PI
            val lat = (input.latitude + dLat).coerceIn(-90.0, 90.0)
            val lon = MetricGrid.wrapLongitude(input.longitude + dLon)
            TransformOutcome.Ok(
                input.copy(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = maxOf(input.accuracyMeters, radius.toFloat()),
                    cityId = null,
                ),
            )
        } catch (_: Exception) {
            TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
        }
    }

    private fun nanosToMillisSaturated(nanos: Long): Long = nanos / 1_000_000L

    /** FNV-1a 64-bit: deterministic across JVMs (unlike default hashCode). */
    internal fun stableSeed(vararg parts: Any): Long {
        var hash = -3750763034362895579L // offset basis (signed 64-bit of 0xcbf29ce484222325)
        for (part in parts) {
            for (b in part.toString().toByteArray(Charsets.UTF_8)) {
                hash = hash xor (b.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            hash = hash xor 0xffL
            hash *= 0x100000001b3L
        }
        return hash
    }
}

/**
 * Transformation facade (Doc 11 section 18). Dispatches on the effective spatial
 * mode; returns a NEW representation and never mutates the input. Any failure
 * yields [TransformOutcome.Failed] - the caller must fail closed and must never
 * fall back to the raw input (Doc 05 FR-103).
 */
object TransformationEngine {
    fun transform(
        input: LocationSample,
        spatial: SpatialPolicy,
        randomization: RandomizationPolicy,
        ctx: TransformationContext,
    ): TransformOutcome {
        if (!input.hasValidCoordinate()) {
            return TransformOutcome.Failed(ReasonCode.INVALID_VALUE)
        }
        return try {
            when (spatial.mode) {
                SpatialMode.EXACT, SpatialMode.ANDROID_CEILING -> {
                    if (randomization.enabled) {
                        RandomizationTransformer.transform(input, randomization, ctx)
                    } else {
                        // Identity transformation; metadata is still sanitized downstream.
                        TransformOutcome.Ok(input.copy(cityId = null))
                    }
                }
                SpatialMode.RADIUS -> {
                    val r = spatial.radiusMeters
                        ?: return TransformOutcome.Failed(ReasonCode.MISSING_FIELD)
                    applyRandomizationIfEnabled(
                        RadiusTransformer.transform(input, r),
                        input,
                        randomization,
                        ctx,
                    )
                }
                SpatialMode.GRID -> {
                    val g = spatial.gridMeters
                        ?: return TransformOutcome.Failed(ReasonCode.MISSING_FIELD)
                    applyRandomizationIfEnabled(
                        GridTransformer.transform(input, g),
                        input,
                        randomization,
                        ctx,
                    )
                }
                SpatialMode.CITY -> {
                    applyRandomizationIfEnabled(
                        CityTransformer.transform(input, spatial.cityId),
                        input,
                        randomization,
                        ctx,
                    )
                }
                SpatialMode.RANDOMIZED -> {
                    if (!randomization.enabled) {
                        return TransformOutcome.Failed(ReasonCode.CONTRADICTORY_FIELDS)
                    }
                    RandomizationTransformer.transform(input, randomization, ctx)
                }
                SpatialMode.DENY -> TransformOutcome.Failed(ReasonCode.SPATIAL_DENY)
            }
        } catch (_: Exception) {
            TransformOutcome.Failed(ReasonCode.TRANSFORMATION_FAILURE)
        }
    }

    private fun applyRandomizationIfEnabled(
        base: TransformOutcome,
        input: LocationSample,
        randomization: RandomizationPolicy,
        ctx: TransformationContext,
    ): TransformOutcome {
        if (!randomization.enabled) return base
        val ok = base as? TransformOutcome.Ok ?: return base
        // Layer bounded perturbation over the degraded base (never over raw input).
        return RandomizationTransformer.transform(ok.location.copy(), randomization, ctx)
    }
}
