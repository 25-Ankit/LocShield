package locshield

import locshield.engine.PolicyEngine
import locshield.model.AndroidAuthorization
import locshield.model.AppIdentity
import locshield.model.AppPolicy
import locshield.model.BackgroundMode
import locshield.model.BackgroundPolicy
import locshield.model.EffectivePolicy
import locshield.model.LocationContext
import locshield.model.LocationSample
import locshield.model.MetadataPolicy
import locshield.model.RandomizationPolicy
import locshield.model.RequestContext
import locshield.model.RequestType
import locshield.model.SeedMode
import locshield.model.SourceType
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.policy.InMemoryPolicyStore
import locshield.policy.PolicyResolver
import locshield.policy.TransactionResult
import locshield.spatial.DeterministicRandomSource
import locshield.spatial.TransformationContext
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Shared deterministic fixtures (Doc 13 sections 4-5).
 * Reference fixture: Mumbai 19.0760 / 72.8777, accuracy 10 m (Doc 13 section 11).
 */
object Fixtures {
    const val MUMBAI_LAT = 19.0760
    const val MUMBAI_LON = 72.8777
    const val ACC_10M = 10f

    fun msToNanos(ms: Long): Long = ms * 1_000_000L

    fun identity(
        pkg: String = "com.example.app",
        uid: Int = 10101,
        user: Int = 0,
    ): AppIdentity = AppIdentity(uid = uid, userId = user, packageName = pkg)

    fun preciseCeiling(backgroundAllowed: Boolean = true): AndroidAuthorization =
        AndroidAuthorization(locationAllowed = true, approximateOnly = false, backgroundAllowed = backgroundAllowed)

    fun approximateCeiling(): AndroidAuthorization =
        AndroidAuthorization(locationAllowed = true, approximateOnly = true, backgroundAllowed = false)

    fun deniedCeiling(): AndroidAuthorization = AndroidAuthorization.denied()

    fun sample(
        lat: Double = MUMBAI_LAT,
        lon: Double = MUMBAI_LON,
        accuracy: Float = ACC_10M,
        elapsedNanos: Long = 0L,
        wallMs: Long = 1_700_000_000_000L,
        speed: Float? = 3.0f,
        bearing: Float? = 90.0f,
        altitude: Double? = 15.0,
    ): LocationSample =
        LocationSample(
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy,
            timestampWallMs = wallMs,
            elapsedRealtimeNanos = elapsedNanos,
            altitudeMeters = altitude,
            speedMps = speed,
            bearingDeg = bearing,
            provider = "fused",
            extras = mapOf("test" to "fixture"),
        )

    fun request(
        identity: AppIdentity = identity(),
        foreground: Boolean = true,
        bgPermission: Boolean = true,
        type: RequestType = RequestType.CONTINUOUS_UPDATES,
        provider: SourceType = SourceType.FUSED,
    ): RequestContext =
        RequestContext(
            identity = identity,
            requestType = type,
            providerType = provider,
            isForeground = foreground,
            hasBackgroundPermission = bgPermission,
        )

    fun locationCtx(
        sample: LocationSample = sample(),
        atNanos: Long = 0L,
        cached: Boolean = false,
        passive: Boolean = false,
        geofence: Boolean = false,
        source: SourceType = SourceType.FUSED,
    ): LocationContext =
        LocationContext(
            location = sample,
            deliveryElapsedNanos = atNanos,
            source = source,
            isCached = cached,
            isPassive = passive,
            isGeofenceEvent = geofence,
        )

    fun basePolicy(
        pkg: String = "com.example.app",
        user: Int = 0,
        spatial: SpatialPolicy = SpatialPolicy(SpatialMode.EXACT),
    ): AppPolicy =
        AppPolicy(
            packageName = pkg,
            userId = user,
            spatial = spatial,
            createdAtWallMs = 1_000L,
            updatedAtWallMs = 1_000L,
        )

    fun exactPolicy(pkg: String = "com.example.app", user: Int = 0): AppPolicy = basePolicy(pkg, user)

    fun cityPolicy(pkg: String = "com.example.app", user: Int = 0): AppPolicy =
        basePolicy(pkg, user, SpatialPolicy(SpatialMode.CITY))

    fun denyPolicy(pkg: String = "com.example.app", user: Int = 0): AppPolicy =
        basePolicy(pkg, user, SpatialPolicy(SpatialMode.DENY))

    fun gridPolicy(meters: Double, pkg: String = "com.example.app", user: Int = 0): AppPolicy =
        basePolicy(pkg, user, SpatialPolicy(SpatialMode.GRID, gridMeters = meters))

    fun radiusPolicy(meters: Double, pkg: String = "com.example.app", user: Int = 0): AppPolicy =
        basePolicy(pkg, user, SpatialPolicy(SpatialMode.RADIUS, radiusMeters = meters))

    fun randomizedPolicy(
        radius: Double = 500.0,
        seedMode: SeedMode = SeedMode.PER_DELIVERY,
        pkg: String = "com.example.app",
    ): AppPolicy =
        basePolicy(pkg, 0, SpatialPolicy(SpatialMode.RANDOMIZED)).copy(
            randomization = RandomizationPolicy(enabled = true, radiusMeters = radius, seedMode = seedMode),
        )

    fun minIntervalPolicy(
        intervalMs: Long,
        spatial: SpatialPolicy = SpatialPolicy(SpatialMode.EXACT),
        pkg: String = "com.example.app",
    ): AppPolicy =
        basePolicy(pkg, 0, spatial).copy(
            temporal = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = intervalMs),
        )

    fun backgroundDenyPolicy(pkg: String = "com.example.app"): AppPolicy =
        basePolicy(pkg, 0).copy(background = BackgroundPolicy(BackgroundMode.DENY))

    fun transformCtx(seed: Long = 42L, nowNanos: Long = 0L, session: String? = null): TransformationContext =
        TransformationContext(DeterministicRandomSource(seed), nowNanos, session)

    fun effective(
        policy: AppPolicy,
        ceiling: AndroidAuthorization = preciseCeiling(),
        generation: Long = 1L,
    ): EffectivePolicy =
        EffectivePolicy(
            spatial = policy.spatial,
            temporal = policy.temporal,
            background = policy.background,
            metadata = policy.metadata,
            randomization = policy.randomization,
            ceiling = ceiling,
            policyGeneration = generation,
            resolvedAtElapsedNanos = 0L,
        )

    data class Rig(
        val engine: PolicyEngine,
        val resolver: PolicyResolver,
        val store: InMemoryPolicyStore,
    )

    /** Engine + resolver + store with the given policies committed (must all be valid). */
    fun rig(vararg policies: AppPolicy): Rig {
        val store = InMemoryPolicyStore()
        for (p in policies) {
            val r = store.put(p)
            assertTrue(r is TransactionResult.Committed, "Fixture policy failed validation: $r")
        }
        val resolver = PolicyResolver(store)
        return Rig(PolicyEngine(temporal = locshield.temporal.TemporalController(), resolver = resolver), resolver, store)
    }

    /** Metadata policy that denies everything except coordinates+accuracy. */
    fun strictMetadata(): MetadataPolicy =
        MetadataPolicy(
            accuracyMode = locshield.model.FieldHandling.COARSE,
            timestampMode = locshield.model.FieldHandling.COARSE,
            elapsedRealtimeMode = locshield.model.FieldHandling.DENY,
            altitudeMode = locshield.model.FieldHandling.DENY,
            speedMode = locshield.model.FieldHandling.DENY,
            bearingMode = locshield.model.FieldHandling.DENY,
            providerMode = locshield.model.FieldHandling.DENY,
            extrasMode = locshield.model.FieldHandling.DENY,
        )
}
