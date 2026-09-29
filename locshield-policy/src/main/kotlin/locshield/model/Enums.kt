package locshield.model

/**
 * Frozen vocabularies for LocShield Policy Engine v0.1.
 *
 * Authority: Document 11 (Tables 3-7) and Document 12 (Tables 2-5).
 *
 * Compatibility mapping with Documents 01-10 terminology:
 * - Doc 03 `ALLOW_TRANSFORM` == [Decision.TRANSFORM].
 * - Doc 03 `ALLOW_WITH_TEMPORAL_RESTRICTION` == [Decision.THROTTLE].
 * - Doc 11 `ERROR_FAIL_CLOSED` == [Decision.FAIL_CLOSED] (Doc 12 name adopted).
 * - Doc 01/07 `REGION`, Doc 10 `AREA`, Doc 07 `NOISE` are reserved aliases and are
 *   NOT separate enum values in v0.1: REGION/AREA map to [SpatialMode.CITY]-scale
 *   handling, NOISE maps to [SpatialMode.RANDOMIZED]. See IMPLEMENTATION_NOTES.md.
 */

/** Spatial precision modes (Doc 11 Table 3, Doc 12 Table 5). */
enum class SpatialMode {
    EXACT,
    RADIUS,
    GRID,
    CITY,
    RANDOMIZED,
    DENY,
    /**
     * Prototype default marker (Doc 12 section 10): no additional LocShield spatial
     * degradation; Android authorization alone governs. The resolver maps it to an
     * EXACT-equivalent under precise authorization, or to a coarse grid under an
     * approximate-only ceiling.
     */
    ANDROID_CEILING,
}

/** Temporal delivery modes (Doc 11 Table 4, Doc 12 section 17). */
enum class TemporalMode {
    REALTIME,
    MIN_INTERVAL,
    PERIODIC,
    RATE_LIMIT,
    ONE_SHOT,
}

/** Background handling modes (Doc 11 Table 5, Doc 12 section 21). */
enum class BackgroundMode {
    INHERIT_ANDROID,
    ALLOW_POLICY,
    RESTRICT,
    DENY,
}

/** Final decision states (Doc 12 Table 4). */
enum class Decision {
    ALLOW,
    TRANSFORM,
    THROTTLE,
    DENY,
    FAIL_CLOSED,
}

/** Temporal gate outcome used inside decision building. */
enum class TemporalAction {
    PROCEED,
    SUPPRESS,
}

/** Metadata treatment outcome reported on the decision. */
enum class MetadataAction {
    RETAIN,
    SANITIZE,
}

/** Randomization seed strategies (Doc 11 Table 6, Doc 12 Table 7). */
enum class SeedMode {
    PER_DELIVERY,
    PERIOD_STABLE,
    SESSION_STABLE,
    SYSTEM_MANAGED,
}

/** Request classes visible to the pure engine (Doc 12 sections 22-24, Doc 13 section 5). */
enum class RequestType {
    CONTINUOUS_UPDATES,
    CURRENT_LOCATION,
    CACHED_LOCATION,
    GEOFENCE_REGISTRATION,
    GEOFENCE_EVENT,
}

/** Location source classes (Doc 11 section 17, Doc 12 section 25). */
enum class SourceType {
    GNSS,
    FUSED,
    NETWORK,
    PASSIVE,
    CACHED,
    GEOFENCE,
    UNKNOWN,
}

/** Per-field metadata handling. */
enum class FieldHandling {
    RETAIN,
    COARSE,
    DENY,
}

/**
 * Stable machine-readable reason codes. Documents 11-13 require a reason code on
 * every decision but do not enumerate values; this table is frozen for v0.1 and any
 * addition must be additive-only (never renumber/redefine).
 */
enum class ReasonCode {
    ALLOW_OK,
    TRANSFORM_OK,
    ANDROID_AUTHORIZATION_DENIED,
    BACKGROUND_DENIED,
    POLICY_DISABLED,
    POLICY_EXPIRED,
    SPATIAL_DENY,
    TEMPORAL_THROTTLE,
    INVALID_POLICY,
    UNKNOWN_MODE,
    MISSING_FIELD,
    INVALID_VALUE,
    VALUE_OUT_OF_BOUNDS,
    CONTRADICTORY_FIELDS,
    UNSUPPORTED_SCHEMA,
    OVERFLOW,
    CORRUPT_SNAPSHOT,
    TRANSFORMATION_FAILURE,
    METADATA_FAILURE,
    RNG_FAILURE,
    TEMPORAL_STATE_INVALID,
    ENGINE_EXCEPTION,
    UNKNOWN_CAPABILITY,
}

/**
 * Strict name parsers. Unknown names return null so callers are forced into
 * reject/fail-closed paths (fuzz oracle: "No invalid enum reaches decision logic").
 */
fun spatialModeOf(name: String): SpatialMode? =
    try {
        SpatialMode.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    }

fun temporalModeOf(name: String): TemporalMode? =
    try {
        TemporalMode.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    }

fun backgroundModeOf(name: String): BackgroundMode? =
    try {
        BackgroundMode.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    }

fun seedModeOf(name: String): SeedMode? =
    try {
        SeedMode.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    }

fun fieldHandlingOf(name: String): FieldHandling? =
    try {
        FieldHandling.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    }
