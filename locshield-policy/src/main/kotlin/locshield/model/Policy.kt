package locshield.model

import locshield.model.PrototypeConstants.CURRENT_SCHEMA_VERSION

/**
 * Policy data model (Doc 11 sections 7-12, Doc 07 section 18).
 * All policies are immutable value objects; updates replace the whole object and
 * bump the store generation (Doc 11 section 29).
 */
data class SpatialPolicy(
    val mode: SpatialMode,
    val radiusMeters: Double? = null,
    val gridMeters: Double? = null,
    val cityId: String? = null,
)

data class TemporalPolicy(
    val mode: TemporalMode,
    val minimumIntervalMs: Long? = null,
    val periodicIntervalMs: Long? = null,
    val maxDeliveriesPerWindow: Int? = null,
    val windowMs: Long? = null,
)

data class BackgroundPolicy(
    val mode: BackgroundMode,
)

data class MetadataPolicy(
    val accuracyMode: FieldHandling = FieldHandling.COARSE,
    val timestampMode: FieldHandling = FieldHandling.RETAIN,
    val elapsedRealtimeMode: FieldHandling = FieldHandling.RETAIN,
    val altitudeMode: FieldHandling = FieldHandling.DENY,
    val speedMode: FieldHandling = FieldHandling.DENY,
    val bearingMode: FieldHandling = FieldHandling.DENY,
    val providerMode: FieldHandling = FieldHandling.RETAIN,
    val extrasMode: FieldHandling = FieldHandling.DENY,
)

data class RandomizationPolicy(
    val enabled: Boolean = false,
    val radiusMeters: Double? = null,
    val stableForMs: Long? = null,
    val seedMode: SeedMode = SeedMode.PER_DELIVERY,
)

data class AppPolicy(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val packageName: String,
    val userId: Int,
    val enabled: Boolean = true,
    val spatial: SpatialPolicy = SpatialPolicy(SpatialMode.ANDROID_CEILING),
    val temporal: TemporalPolicy = TemporalPolicy(TemporalMode.REALTIME),
    val background: BackgroundPolicy = BackgroundPolicy(BackgroundMode.INHERIT_ANDROID),
    val metadata: MetadataPolicy = MetadataPolicy(),
    val randomization: RandomizationPolicy = RandomizationPolicy(),
    val createdAtWallMs: Long = 0L,
    val updatedAtWallMs: Long = 0L,
    /** Null means "never expires". Compared against wall-clock time only. */
    val expiresAtWallMs: Long? = null,
)

/**
 * Explicit versioned system default (Doc 12 section 10). An absent policy is never
 * interpreted as unlimited access: it resolves to this value, which preserves the
 * Android ceiling and sanitizes extras. A production deployment may choose a
 * stricter default; that choice must be explicit, never silent.
 */
fun systemDefaultPolicy(): AppPolicy =
    AppPolicy(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        packageName = "",
        userId = -1,
        enabled = true,
        spatial = SpatialPolicy(SpatialMode.ANDROID_CEILING),
        temporal = TemporalPolicy(TemporalMode.REALTIME),
        background = BackgroundPolicy(BackgroundMode.INHERIT_ANDROID),
        metadata = MetadataPolicy(),
        randomization = RandomizationPolicy(enabled = false),
        createdAtWallMs = 0L,
        updatedAtWallMs = 0L,
        expiresAtWallMs = null,
    )

/** Safe expiration fallback: expired policies never broaden access (Doc 12 section 28). */
fun expiredFallbackPolicy(): AppPolicy =
    systemDefaultPolicy().copy(
        spatial = SpatialPolicy(SpatialMode.DENY),
        temporal = TemporalPolicy(TemporalMode.REALTIME),
    )
