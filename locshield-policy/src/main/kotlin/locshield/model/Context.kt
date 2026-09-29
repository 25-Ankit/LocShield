package locshield.model

/**
 * Request/delivery context shapes (Doc 11 sections 16-17, Doc 13 section 5).
 * Populated by the future Enforcement Adapter from framework state; in v0.1 tests
 * they are constructed synthetically (Doc 12 section 42).
 */
data class RequestContext(
    val identity: AppIdentity,
    val requestType: RequestType = RequestType.CONTINUOUS_UPDATES,
    val providerType: SourceType = SourceType.FUSED,
    val isForeground: Boolean = true,
    val requestedIntervalMs: Long = 0L,
    val requestedQuality: Int = 0,
    val hasBackgroundPermission: Boolean = false,
    val requestStartElapsedNanos: Long = 0L,
)

data class LocationContext(
    val location: LocationSample,
    val deliveryElapsedNanos: Long,
    val source: SourceType = SourceType.FUSED,
    val isCached: Boolean = false,
    val isPassive: Boolean = false,
    val isGeofenceEvent: Boolean = false,
)
