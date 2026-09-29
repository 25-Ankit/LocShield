package locshield.model

/**
 * Resolved, intersected, immutable effective policy (Doc 11 sections 27-28).
 * Carries the generation it was resolved at so deliveries can prove they used the
 * current policy after an update (Doc 13 RES-024, Doc 11 section 40).
 */
data class EffectivePolicy(
    val spatial: SpatialPolicy,
    val temporal: TemporalPolicy,
    val background: BackgroundPolicy,
    val metadata: MetadataPolicy,
    val randomization: RandomizationPolicy,
    val ceiling: AndroidAuthorization,
    val policyGeneration: Long,
    val resolvedAtElapsedNanos: Long,
)
