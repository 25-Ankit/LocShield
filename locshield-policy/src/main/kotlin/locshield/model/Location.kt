package locshield.model

/**
 * Synthetic location carrier for the pure engine (Doc 12 section 42, Doc 13 section 5
 * `TestLocation`). Stands in for `android.location.Location` so the core has zero
 * Android dependencies. The future Enforcement Adapter maps platform objects to and
 * from this shape at the trust boundary.
 *
 * All instances are immutable. Transformation and sanitization always allocate a new
 * instance and must never mutate provider-owned (here: caller-owned) objects.
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    /** Wall-clock timestamp (may be bucketed by the sanitizer). */
    val timestampWallMs: Long,
    /** Monotonic clock reading used for interval enforcement. */
    val elapsedRealtimeNanos: Long,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val extras: Map<String, String> = emptyMap(),
    /** Set only by the CITY transformer; null otherwise. */
    val cityId: String? = null,
) {
    /** Coordinate validity gate. Invalid coordinates fail closed (fuzz oracle). */
    fun hasValidCoordinate(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() &&
            accuracyMeters >= 0f
}
