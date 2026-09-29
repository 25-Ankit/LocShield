package locshield.engine

import locshield.model.AppIdentity
import locshield.model.BackgroundMode
import locshield.model.Decision
import locshield.model.EffectivePolicy
import locshield.model.LocationContext
import locshield.model.MetadataAction
import locshield.model.PolicyDecision
import locshield.model.ReasonCode
import locshield.model.RequestContext
import locshield.model.RequestType
import locshield.model.SpatialMode
import locshield.model.TemporalAction
import locshield.model.TemporalPolicy
import locshield.policy.PolicyResolver
import locshield.policy.Restriction
import locshield.temporal.TemporalController

/**
 * Deterministic policy evaluator (Doc 07 C05, Doc 12 sections 4/8/30).
 *
 * Reference pseudocode implemented verbatim:
 * ```
 * if (!auth.locationAllowed)            DENY(ANDROID_AUTHORIZATION)
 * if (!enabled / expired)               DENY(...)
 * background gate                       DENY or RESTRICT-derivation
 * temporal.evaluate(...) == SUPPRESS    THROTTLE(TEMPORAL_POLICY)
 * spatial == DENY                       DENY(SPATIAL_POLICY)
 * else                                  ALLOW or TRANSFORM
 * ```
 *
 * The engine is stateless and performs no I/O: no Binder, disk, network, UI,
 * randomness, or wall-clock reads. Time arrives as explicit parameters. Given the
 * same policy generation, request context, ceiling and evaluation time, it
 * produces the same decision (Doc 12 section 4).
 *
 * Cached/passive results ([LocationContext.isCached]/[isPassive]) and geofence
 * events go through this SAME evaluation; the flags never bypass it (Doc 12
 * sections 22-24). GNSS measurements/NMEA/navigation messages are NOT ordinary
 * location decisions and must use separate capability paths (Doc 12 section 25);
 * this engine never claims them.
 */
class PolicyEngine(
    val temporal: TemporalController = TemporalController(),
    private val resolver: PolicyResolver? = null,
) {
    /**
     * Full pipeline entry: resolve then evaluate. Requires a resolver; without one
     * use [evaluate] with a pre-resolved [EffectivePolicy].
     */
    fun resolveAndEvaluate(
        identity: AppIdentity,
        request: RequestContext,
        ceiling: locshield.model.AndroidAuthorization,
        location: LocationContext,
        nowWallMs: Long,
    ): PolicyDecision {
        val r = resolver
            ?: return failClosed(
                generation = -1,
                atNanos = location.deliveryElapsedNanos,
                appliedSpatial = locshield.model.SpatialPolicy(SpatialMode.DENY),
                appliedTemporal = TemporalPolicy(locshield.model.TemporalMode.REALTIME),
                reason = ReasonCode.ENGINE_EXCEPTION,
            )
        return when (val outcome = r.resolve(identity, request, ceiling, nowWallMs, location.deliveryElapsedNanos)) {
            is locshield.policy.ResolutionOutcome.Effective ->
                evaluate(identity, request, outcome.policy, location, location.deliveryElapsedNanos)
            is locshield.policy.ResolutionOutcome.Denied ->
                PolicyDecision(
                    decision = Decision.DENY,
                    spatialMode = SpatialMode.DENY,
                    temporalAction = TemporalAction.SUPPRESS,
                    metadataAction = MetadataAction.SANITIZE,
                    reasonCode = outcome.reason,
                    policyGeneration = outcome.generation,
                    generatedAtElapsedNanos = location.deliveryElapsedNanos,
                    appliedSpatial = locshield.model.SpatialPolicy(SpatialMode.DENY),
                    appliedTemporal = TemporalPolicy(locshield.model.TemporalMode.REALTIME),
                )
        }
    }

    /** Evaluates an already-resolved effective policy. */
    fun evaluate(
        identity: AppIdentity,
        request: RequestContext,
        effective: EffectivePolicy,
        location: LocationContext,
        nowElapsedNanos: Long,
    ): PolicyDecision {
        try {
            // 0. Input validity gate: corrupt location objects fail closed, and the
            //    cached/passive/geofence flags are recorded but never bypass.
            if (!location.location.hasValidCoordinate()) {
                return failClosed(
                    generation = effective.policyGeneration,
                    atNanos = nowElapsedNanos,
                    appliedSpatial = effective.spatial,
                    appliedTemporal = effective.temporal,
                    reason = ReasonCode.INVALID_VALUE,
                )
            }

            // 1. Android ceiling is re-checked at evaluation time (defense in depth;
            //    the resolver already denied, but EffectivePolicy values must never
            //    be trusted blindly if constructed elsewhere).
            if (!effective.ceiling.locationAllowed) {
                return denied(
                    effective,
                    nowElapsedNanos,
                    ReasonCode.ANDROID_AUTHORIZATION_DENIED,
                    effective.spatial,
                    effective.temporal,
                )
            }

            // 2. Background gate (Doc 12 section 21). LocShield never bypasses an
            //    Android background denial with its own policy.
            var spatial = effective.spatial
            var temporalPolicy: TemporalPolicy = effective.temporal
            if (!request.isForeground) {
                if (!request.hasBackgroundPermission || !effective.ceiling.backgroundAllowed) {
                    return denied(
                        effective,
                        nowElapsedNanos,
                        ReasonCode.BACKGROUND_DENIED,
                        effective.spatial,
                        effective.temporal,
                    )
                }
                when (effective.background.mode) {
                    BackgroundMode.DENY -> return denied(
                        effective,
                        nowElapsedNanos,
                        ReasonCode.BACKGROUND_DENIED,
                        effective.spatial,
                        effective.temporal,
                    )
                    BackgroundMode.RESTRICT -> {
                        val (rs, rt) = applyRestrict(spatial, temporalPolicy)
                        spatial = rs
                        temporalPolicy = rt
                    }
                    BackgroundMode.INHERIT_ANDROID, BackgroundMode.ALLOW_POLICY -> { /* no extra restriction */ }
                }
            }

            // 3. Geofence registration under a DENY spatial policy is non-operative.
            if ((request.requestType == RequestType.GEOFENCE_REGISTRATION ||
                    request.requestType == RequestType.GEOFENCE_EVENT ||
                    location.isGeofenceEvent) &&
                spatial.mode == SpatialMode.DENY
            ) {
                return denied(effective, nowElapsedNanos, ReasonCode.SPATIAL_DENY, spatial, temporalPolicy)
            }

            // 4. Temporal gate applies to DELIVERY, not request admission.
            val temporalDecision = try {
                temporal.evaluate(identity, temporalPolicy, effective.policyGeneration, nowElapsedNanos)
            } catch (_: Exception) {
                return failClosed(
                    generation = effective.policyGeneration,
                    atNanos = nowElapsedNanos,
                    appliedSpatial = spatial,
                    appliedTemporal = temporalPolicy,
                    reason = ReasonCode.TEMPORAL_STATE_INVALID,
                )
            }
            if (temporalDecision.action == TemporalAction.SUPPRESS) {
                return PolicyDecision(
                    decision = Decision.THROTTLE,
                    spatialMode = spatial.mode,
                    temporalAction = TemporalAction.SUPPRESS,
                    metadataAction = MetadataAction.SANITIZE,
                    reasonCode = ReasonCode.TEMPORAL_THROTTLE,
                    policyGeneration = effective.policyGeneration,
                    generatedAtElapsedNanos = nowElapsedNanos,
                    appliedSpatial = spatial,
                    appliedTemporal = temporalPolicy,
                )
            }

            // 5. Spatial gate.
            if (spatial.mode == SpatialMode.DENY) {
                return denied(effective, nowElapsedNanos, ReasonCode.SPATIAL_DENY, spatial, temporalPolicy)
            }

            // 6. Build ALLOW vs TRANSFORM. ANDROID_CEILING under a precise grant is
            //    ALLOW (no extra degradation); everything else degrading is TRANSFORM.
            //    (Under an approximate-only ceiling the resolver has already rewritten
            //    EXACT/ANDROID_CEILING to GRID, so reaching EXACT here implies precise.)
            val needsTransform = when (spatial.mode) {
                SpatialMode.EXACT, SpatialMode.ANDROID_CEILING -> effective.randomization.enabled
                SpatialMode.RADIUS, SpatialMode.GRID, SpatialMode.CITY, SpatialMode.RANDOMIZED -> true
                SpatialMode.DENY -> false // unreachable (returned above); kept for exhaustiveness
            }
            val metadataAction = if (metadataNeedsSanitize(effective)) MetadataAction.SANITIZE else MetadataAction.RETAIN
            return if (needsTransform) {
                PolicyDecision(
                    decision = Decision.TRANSFORM,
                    spatialMode = spatial.mode,
                    temporalAction = TemporalAction.PROCEED,
                    metadataAction = metadataAction,
                    reasonCode = ReasonCode.TRANSFORM_OK,
                    policyGeneration = effective.policyGeneration,
                    generatedAtElapsedNanos = nowElapsedNanos,
                    appliedSpatial = spatial,
                    appliedTemporal = temporalPolicy,
                )
            } else {
                PolicyDecision(
                    decision = Decision.ALLOW,
                    spatialMode = spatial.mode,
                    temporalAction = TemporalAction.PROCEED,
                    metadataAction = metadataAction,
                    reasonCode = ReasonCode.ALLOW_OK,
                    policyGeneration = effective.policyGeneration,
                    generatedAtElapsedNanos = nowElapsedNanos,
                    appliedSpatial = spatial,
                    appliedTemporal = temporalPolicy,
                )
            }
        } catch (_: Exception) {
            // Any unexpected failure fails closed (Doc 12 Table 11, SEC invariants).
            return failClosed(
                generation = effective.policyGeneration,
                atNanos = nowElapsedNanos,
                appliedSpatial = effective.spatial,
                appliedTemporal = effective.temporal,
                reason = ReasonCode.ENGINE_EXCEPTION,
            )
        }
    }

    // -- internals ----------------------------------------------------------

    private fun denied(
        effective: EffectivePolicy,
        nowNanos: Long,
        reason: ReasonCode,
        appliedSpatial: locshield.model.SpatialPolicy,
        appliedTemporal: TemporalPolicy,
    ): PolicyDecision =
        PolicyDecision(
            decision = Decision.DENY,
            spatialMode = SpatialMode.DENY,
            temporalAction = TemporalAction.SUPPRESS,
            metadataAction = MetadataAction.SANITIZE,
            reasonCode = reason,
            policyGeneration = effective.policyGeneration,
            generatedAtElapsedNanos = nowNanos,
            appliedSpatial = appliedSpatial,
            appliedTemporal = appliedTemporal,
        )

    private fun failClosed(
        generation: Long,
        atNanos: Long,
        appliedSpatial: locshield.model.SpatialPolicy,
        appliedTemporal: TemporalPolicy,
        reason: ReasonCode,
    ): PolicyDecision =
        PolicyDecision(
            decision = Decision.FAIL_CLOSED,
            spatialMode = appliedSpatial.mode,
            temporalAction = TemporalAction.SUPPRESS,
            metadataAction = MetadataAction.SANITIZE,
            reasonCode = reason,
            policyGeneration = generation,
            generatedAtElapsedNanos = atNanos,
            appliedSpatial = appliedSpatial,
            appliedTemporal = appliedTemporal,
        )

    private fun applyRestrict(
        spatial: locshield.model.SpatialPolicy,
        temporal: TemporalPolicy,
    ): Pair<locshield.model.SpatialPolicy, TemporalPolicy> {
        val floorSpatial = locshield.model.SpatialPolicy(
            SpatialMode.GRID,
            gridMeters = locshield.model.PrototypeConstants.BG_RESTRICT_GRID_METERS,
        )
        val floorTemporal = TemporalPolicy(
            locshield.model.TemporalMode.MIN_INTERVAL,
            minimumIntervalMs = locshield.model.PrototypeConstants.BG_RESTRICT_MIN_INTERVAL_MS,
        )
        return Restriction.stricterSpatial(spatial, floorSpatial) to
            Restriction.stricterTemporal(temporal, floorTemporal)
    }

    private fun metadataNeedsSanitize(effective: EffectivePolicy): Boolean {
        val m = effective.metadata
        return m.accuracyMode != locshield.model.FieldHandling.RETAIN ||
            m.timestampMode != locshield.model.FieldHandling.RETAIN ||
            m.elapsedRealtimeMode != locshield.model.FieldHandling.RETAIN ||
            m.altitudeMode != locshield.model.FieldHandling.RETAIN ||
            m.speedMode != locshield.model.FieldHandling.RETAIN ||
            m.bearingMode != locshield.model.FieldHandling.RETAIN ||
            m.providerMode != locshield.model.FieldHandling.RETAIN ||
            m.extrasMode != locshield.model.FieldHandling.RETAIN
    }
}
