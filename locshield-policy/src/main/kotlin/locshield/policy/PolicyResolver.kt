package locshield.policy

import locshield.model.AndroidAuthorization
import locshield.model.AppIdentity
import locshield.model.AppPolicy
import locshield.model.ApplicationSelector
import locshield.model.BackgroundPolicy
import locshield.model.EffectivePolicy
import locshield.model.PrototypeConstants
import locshield.model.ReasonCode
import locshield.model.RequestContext
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.model.ValidationResult
import locshield.model.expiredFallbackPolicy
import locshield.model.systemDefaultPolicy

/**
 * Resolution outcome. Denials carry the current generation for auditability;
 * they never carry location information.
 */
sealed interface ResolutionOutcome {
    val generation: Long

    data class Effective(val policy: EffectivePolicy, override val generation: Long) : ResolutionOutcome

    data class Denied(val reason: ReasonCode, override val generation: Long) : ResolutionOutcome
}

/**
 * Android authorization intersection (Doc 05 FR-020/SEC-005, Doc 12 section 7).
 * The ceiling can only preserve or reduce access, never expand it.
 */
object AuthorizationIntersecter {
    /**
     * RES-002: an approximate-only ceiling over an EXACT/ANDROID_CEILING request
     * degrades to a coarse grid. The grid size is a documented prototype choice
     * ([PrototypeConstants.APPROX_CEILING_GRID_METERS]).
     */
    fun intersectSpatial(spatial: SpatialPolicy, ceiling: AndroidAuthorization): SpatialPolicy {
        if (ceiling.approximateOnly && (spatial.mode == SpatialMode.EXACT || spatial.mode == SpatialMode.ANDROID_CEILING)) {
            return SpatialPolicy(
                mode = SpatialMode.GRID,
                gridMeters = PrototypeConstants.APPROX_CEILING_GRID_METERS,
            )
        }
        return spatial
    }
}

/**
 * Restriction ordering helpers implementing "policies compose through restrictive
 * intersection" (Doc 12 section 26) and CMP-005 (RADIUS + GRID -> stricter).
 * Characteristic scale: approximate meters of uncertainty; DENY is infinite.
 */
object Restriction {
    fun spatialScaleMeters(spatial: SpatialPolicy): Double =
        when (spatial.mode) {
            SpatialMode.DENY -> Double.POSITIVE_INFINITY
            SpatialMode.CITY -> PrototypeConstants.CITY_ACCURACY_METERS.toDouble()
            SpatialMode.GRID -> spatial.gridMeters ?: Double.POSITIVE_INFINITY
            SpatialMode.RADIUS -> spatial.radiusMeters ?: Double.POSITIVE_INFINITY
            SpatialMode.RANDOMIZED -> Double.POSITIVE_INFINITY // bound lives in RandomizationPolicy; handled separately
            SpatialMode.EXACT, SpatialMode.ANDROID_CEILING -> 0.0
        }

    fun stricterSpatial(a: SpatialPolicy, b: SpatialPolicy): SpatialPolicy =
        if (spatialScaleMeters(a) >= spatialScaleMeters(b)) a else b

    /** Approximate minimum delivery interval; ONE_SHOT is infinite (single delivery). */
    fun temporalFloorMs(temporal: TemporalPolicy): Long =
        when (temporal.mode) {
            TemporalMode.REALTIME -> 0L
            TemporalMode.MIN_INTERVAL -> temporal.minimumIntervalMs ?: 0L
            TemporalMode.PERIODIC -> temporal.periodicIntervalMs ?: 0L
            TemporalMode.RATE_LIMIT -> {
                val max = temporal.maxDeliveriesPerWindow ?: 1
                val window = temporal.windowMs ?: 0L
                if (max <= 0) Long.MAX_VALUE else window / max
            }
            TemporalMode.ONE_SHOT -> Long.MAX_VALUE
        }

    fun stricterTemporal(a: TemporalPolicy, b: TemporalPolicy): TemporalPolicy =
        if (temporalFloorMs(a) >= temporalFloorMs(b)) a else b
}

/**
 * Effective-policy resolution (Doc 11 section 27, Doc 12 section 6):
 * explicit -> user default -> system default, then enabled/expiry checks, then
 * Android-ceiling intersection. Pure and side-effect free.
 */
class PolicyResolver(
    private val store: PolicySnapshotProvider,
) {
    fun resolve(
        identity: AppIdentity,
        request: RequestContext,
        ceiling: AndroidAuthorization,
        nowWallMs: Long,
        nowElapsedNanos: Long,
    ): ResolutionOutcome {
        val generation = store.getGeneration(identity.userId)

        if (!ceiling.locationAllowed) {
            return ResolutionOutcome.Denied(ReasonCode.ANDROID_AUTHORIZATION_DENIED, generation)
        }

        var policy: AppPolicy =
            store.get(ApplicationSelector(identity.packageName, identity.userId))
                ?: store.getDefault(identity.userId)
                ?: systemDefaultPolicy()

        if (!policy.enabled) {
            return ResolutionOutcome.Denied(ReasonCode.POLICY_DISABLED, generation)
        }

        val expiresAt = policy.expiresAtWallMs
        if (expiresAt != null && nowWallMs >= expiresAt) {
            // Expired policies fall back to a safe deny-preserving policy, never to a
            // broader state (Doc 12 section 28).
            policy = expiredFallbackPolicy()
            if (!policy.enabled || policy.spatial.mode == SpatialMode.DENY) {
                return ResolutionOutcome.Denied(ReasonCode.POLICY_EXPIRED, generation)
            }
        }

        // Defensive re-validation: a corrupt snapshot can never become permissive.
        val validation = PolicyValidator.validate(
            if (policy.userId < 0) {
                policy.copy(packageName = identity.packageName.ifBlank { "unknown" }, userId = identity.userId)
            } else {
                policy
            },
        )
        if (validation is ValidationResult.Rejected) {
            return ResolutionOutcome.Denied(ReasonCode.CORRUPT_SNAPSHOT, generation)
        }

        val effective = EffectivePolicy(
            spatial = AuthorizationIntersecter.intersectSpatial(policy.spatial, ceiling),
            temporal = policy.temporal,
            background = policy.background,
            metadata = policy.metadata,
            randomization = policy.randomization,
            ceiling = ceiling,
            policyGeneration = generation,
            resolvedAtElapsedNanos = nowElapsedNanos,
        )
        return ResolutionOutcome.Effective(effective, generation)
    }

    /**
     * Background RESTRICT degradation (Doc 12 section 21): combine the configured
     * spatial/temporal policy restrictively with the prototype RESTRICT floor.
     */
    fun applyBackgroundRestriction(
        spatial: SpatialPolicy,
        temporal: TemporalPolicy,
        mode: BackgroundPolicy,
    ): Pair<SpatialPolicy, TemporalPolicy> {
        if (mode.mode != locshield.model.BackgroundMode.RESTRICT) {
            return spatial to temporal
        }
        val floorSpatial = SpatialPolicy(SpatialMode.GRID, gridMeters = PrototypeConstants.BG_RESTRICT_GRID_METERS)
        val floorTemporal = TemporalPolicy(TemporalMode.MIN_INTERVAL, minimumIntervalMs = PrototypeConstants.BG_RESTRICT_MIN_INTERVAL_MS)
        return Restriction.stricterSpatial(spatial, floorSpatial) to
            Restriction.stricterTemporal(temporal, floorTemporal)
    }
}
