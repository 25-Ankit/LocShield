package locshield.model

/**
 * Engine outputs (Doc 11 section 13, Doc 12 Table 4).
 * Immutable. A decision never carries raw location: the caller holds the input and
 * receives either a separately transformed object (TRANSFORM/ALLOW path) or nothing.
 *
 * [appliedSpatial]/[appliedTemporal] are the background-restriction-adjusted policies
 * the enforcement path MUST use for transformation, sanitization floor selection and
 * temporal recording. They equal the effective policy except when background
 * RESTRICT derivation applied (Doc 12 section 21). Carrying them on the decision
 * (instead of requiring the adapter to re-derive) closes a silent-under-enforcement
 * gap: the adapter transforms per-decision, never per stored policy.
 */
data class PolicyDecision(
    val decision: Decision,
    val spatialMode: SpatialMode,
    val temporalAction: TemporalAction,
    val metadataAction: MetadataAction,
    val reasonCode: ReasonCode,
    val policyGeneration: Long,
    val generatedAtElapsedNanos: Long,
    val appliedSpatial: SpatialPolicy,
    val appliedTemporal: TemporalPolicy,
)

/** Temporal gate result with an optional advisory retry hint for the adapter. */
data class TemporalDecision(
    val action: TemporalAction,
    val nextAllowedElapsedNanos: Long? = null,
)
