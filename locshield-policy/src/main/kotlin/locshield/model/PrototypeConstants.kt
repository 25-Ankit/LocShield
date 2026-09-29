package locshield.model

/**
 * Prototype-wide numeric bounds and tuning constants.
 *
 * Documents 11-13 deliberately leave exact bounds as "implementation-defined".
 * Every value here is a documented prototype choice (see IMPLEMENTATION_NOTES.md),
 * chosen defensively: out-of-range inputs are rejected, never clamped silently.
 */
object PrototypeConstants {
    /** Current serialized policy schema version (Doc 11 section 32). */
    const val CURRENT_SCHEMA_VERSION: Int = 1

    /** Radius bounds for RADIUS / RANDOMIZED policies, in meters. */
    const val MIN_RADIUS_METERS: Double = 1.0
    const val MAX_RADIUS_METERS: Double = 100_000.0

    /** Grid cell bounds for GRID policies, in meters. */
    const val MIN_GRID_METERS: Double = 1.0
    const val MAX_GRID_METERS: Double = 100_000.0

    /** Upper bound for any configured interval/window, in milliseconds (7 days). */
    const val MAX_INTERVAL_MS: Long = 7L * 24L * 3_600L * 1_000L

    /** Upper bound for RATE_LIMIT deliveries per window. */
    const val MAX_DELIVERIES_PER_WINDOW: Int = 100_000

    /**
     * Coarse grid applied when Android granted approximate-only authorization but
     * the LocShield policy requests EXACT/ANDROID_CEILING (RES-002). Prototype choice.
     */
    const val APPROX_CEILING_GRID_METERS: Double = 2_000.0

    /** Prototype RESTRICT background degradation (Doc 12 section 21). */
    const val BG_RESTRICT_GRID_METERS: Double = 1_000.0
    const val BG_RESTRICT_MIN_INTERVAL_MS: Long = 15L * 60L * 1_000L

    /** CITY transformer geometry (Doc 12 section 15). */
    const val CITY_MATCH_RADIUS_METERS: Double = 50_000.0
    const val CITY_FALLBACK_GRID_METERS: Double = 20_000.0
    const val CITY_ACCURACY_METERS: Float = 5_000f

    /** Metadata bucketing (Doc 12 Table 8). */
    const val TIMESTAMP_BUCKET_MS: Long = 60_000L
    const val ELAPSED_BUCKET_NANOS: Long = 60_000_000_000L
    const val SPEED_COARSE_STEP_MPS: Float = 5.0f
    const val BEARING_COARSE_STEP_DEG: Float = 45.0f
    const val ALTITUDE_COARSE_STEP_M: Double = 50.0

    /** WGS-84 mean earth radius used by the metric grid projection. */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0
}
