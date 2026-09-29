package locshield.policy

import locshield.model.AppPolicy
import locshield.model.BackgroundMode
import locshield.model.PrototypeConstants
import locshield.model.RandomizationPolicy
import locshield.model.ReasonCode
import locshield.model.SeedMode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.model.TemporalMode
import locshield.model.TemporalPolicy
import locshield.model.ValidationResult

/**
 * Policy validation gate (Doc 11 section 23, Doc 13 VAL-001..VAL-014).
 *
 * Runs in the control plane (future system service) before any policy becomes
 * active, AND defensively at resolution time. Invalid input is rejected with an
 * explicit reason; it can never become a permissive active policy (Doc 05 REL-002).
 */
object PolicyValidator {
    fun validate(policy: AppPolicy): ValidationResult {
        if (policy.schemaVersion != PrototypeConstants.CURRENT_SCHEMA_VERSION) {
            return ValidationResult.Rejected(
                reason = ReasonCode.UNSUPPORTED_SCHEMA,
                message = "Unsupported schemaVersion=${policy.schemaVersion}",
                field = "schemaVersion",
            )
        }
        if (policy.packageName.isBlank()) {
            return ValidationResult.Rejected(
                reason = ReasonCode.INVALID_VALUE,
                message = "packageName must not be blank",
                field = "packageName",
            )
        }
        if (policy.userId < 0) {
            return ValidationResult.Rejected(
                reason = ReasonCode.INVALID_VALUE,
                message = "userId must be >= 0",
                field = "userId",
            )
        }
        validateSpatial(policy.spatial)?.let { return it }
        validateTemporal(policy.temporal)?.let { return it }
        validateRandomization(policy.randomization)?.let { return it }
        if (policy.createdAtWallMs < 0 || policy.updatedAtWallMs < 0) {
            return ValidationResult.Rejected(
                reason = ReasonCode.INVALID_VALUE,
                message = "Creation/update timestamps must be >= 0",
                field = "createdAtWallMs",
            )
        }
        val expiresAt = policy.expiresAtWallMs
        if (expiresAt != null && expiresAt <= policy.createdAtWallMs) {
            return ValidationResult.Rejected(
                reason = ReasonCode.INVALID_VALUE,
                message = "expiresAt must be later than createdAt",
                field = "expiresAtWallMs",
            )
        }
        return ValidationResult.Accepted
    }

    private fun validateSpatial(spatial: SpatialPolicy): ValidationResult.Rejected? {
        fun reject(
            reason: ReasonCode,
            message: String,
            field: String,
        ): ValidationResult.Rejected = ValidationResult.Rejected(reason, message, field)

        when (spatial.mode) {
            SpatialMode.RADIUS -> {
                val r = spatial.radiusMeters
                    ?: return reject(ReasonCode.MISSING_FIELD, "RADIUS requires radiusMeters", "spatial.radiusMeters")
                if (!r.isFinite() || r < PrototypeConstants.MIN_RADIUS_METERS || r > PrototypeConstants.MAX_RADIUS_METERS) {
                    return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "radiusMeters out of bounds", "spatial.radiusMeters")
                }
                if (spatial.gridMeters != null) {
                    return reject(ReasonCode.CONTRADICTORY_FIELDS, "RADIUS must not carry gridMeters", "spatial.gridMeters")
                }
            }
            SpatialMode.GRID -> {
                val g = spatial.gridMeters
                    ?: return reject(ReasonCode.MISSING_FIELD, "GRID requires gridMeters", "spatial.gridMeters")
                if (!g.isFinite() || g < PrototypeConstants.MIN_GRID_METERS || g > PrototypeConstants.MAX_GRID_METERS) {
                    return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "gridMeters out of bounds", "spatial.gridMeters")
                }
                if (spatial.radiusMeters != null) {
                    return reject(ReasonCode.CONTRADICTORY_FIELDS, "GRID must not carry radiusMeters", "spatial.radiusMeters")
                }
            }
            SpatialMode.EXACT, SpatialMode.DENY, SpatialMode.CITY, SpatialMode.ANDROID_CEILING -> {
                if (spatial.radiusMeters != null || spatial.gridMeters != null) {
                    return reject(
                        ReasonCode.CONTRADICTORY_FIELDS,
                        "${spatial.mode} must not carry radius/grid parameters",
                        "spatial",
                    )
                }
            }
            SpatialMode.RANDOMIZED -> {
                // RANDOMIZED is a spatial mode; its bound lives in RandomizationPolicy.
                if (spatial.radiusMeters != null || spatial.gridMeters != null) {
                    return reject(
                        ReasonCode.CONTRADICTORY_FIELDS,
                        "RANDOMIZED carries its bound in randomization, not in spatial",
                        "spatial",
                    )
                }
            }
        }
        if (spatial.radiusMeters != null && !spatial.radiusMeters.isFinite()) {
            return reject(ReasonCode.INVALID_VALUE, "radiusMeters must be finite", "spatial.radiusMeters")
        }
        if (spatial.gridMeters != null && !spatial.gridMeters.isFinite()) {
            return reject(ReasonCode.INVALID_VALUE, "gridMeters must be finite", "spatial.gridMeters")
        }
        if (spatial.cityId != null && spatial.cityId.isBlank()) {
            return reject(ReasonCode.INVALID_VALUE, "cityId must not be blank when set", "spatial.cityId")
        }
        return null
    }

    private fun validateTemporal(temporal: TemporalPolicy): ValidationResult.Rejected? {
        fun reject(
            reason: ReasonCode,
            message: String,
            field: String,
        ): ValidationResult.Rejected = ValidationResult.Rejected(reason, message, field)

        fun checkInterval(
            value: Long?,
            field: String,
            required: Boolean,
        ): ValidationResult.Rejected? {
            if (value == null) {
                return if (required) {
                    reject(ReasonCode.MISSING_FIELD, "$field is required for ${temporal.mode}", "temporal.$field")
                } else {
                    null
                }
            }
            if (value < 0 || value > PrototypeConstants.MAX_INTERVAL_MS) {
                return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "$field out of bounds", "temporal.$field")
            }
            return null
        }

        when (temporal.mode) {
            TemporalMode.MIN_INTERVAL -> {
                checkInterval(temporal.minimumIntervalMs, "minimumIntervalMs", required = true)?.let { return it }
                if (temporal.periodicIntervalMs != null || temporal.maxDeliveriesPerWindow != null || temporal.windowMs != null) {
                    return reject(ReasonCode.CONTRADICTORY_FIELDS, "MIN_INTERVAL carries unrelated fields", "temporal")
                }
            }
            TemporalMode.PERIODIC -> {
                checkInterval(temporal.periodicIntervalMs, "periodicIntervalMs", required = true)?.let { return it }
                if (temporal.minimumIntervalMs != null || temporal.maxDeliveriesPerWindow != null || temporal.windowMs != null) {
                    return reject(ReasonCode.CONTRADICTORY_FIELDS, "PERIODIC carries unrelated fields", "temporal")
                }
            }
            TemporalMode.RATE_LIMIT -> {
                val max = temporal.maxDeliveriesPerWindow
                    ?: return reject(ReasonCode.MISSING_FIELD, "RATE_LIMIT requires maxDeliveriesPerWindow", "temporal.maxDeliveriesPerWindow")
                if (max <= 0 || max > PrototypeConstants.MAX_DELIVERIES_PER_WINDOW) {
                    return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "maxDeliveriesPerWindow out of bounds", "temporal.maxDeliveriesPerWindow")
                }
                checkInterval(temporal.windowMs, "windowMs", required = true)?.let { return it }
                if (temporal.windowMs!! <= 0) {
                    return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "windowMs must be > 0", "temporal.windowMs")
                }
                if (temporal.minimumIntervalMs != null || temporal.periodicIntervalMs != null) {
                    return reject(ReasonCode.CONTRADICTORY_FIELDS, "RATE_LIMIT carries unrelated fields", "temporal")
                }
            }
            TemporalMode.REALTIME, TemporalMode.ONE_SHOT -> {
                if (temporal.minimumIntervalMs != null ||
                    temporal.periodicIntervalMs != null ||
                    temporal.maxDeliveriesPerWindow != null ||
                    temporal.windowMs != null
                ) {
                    return reject(ReasonCode.CONTRADICTORY_FIELDS, "${temporal.mode} carries interval fields", "temporal")
                }
            }
        }
        return null
    }

    private fun validateRandomization(randomization: RandomizationPolicy): ValidationResult.Rejected? {
        fun reject(
            reason: ReasonCode,
            message: String,
            field: String,
        ): ValidationResult.Rejected = ValidationResult.Rejected(reason, message, field)

        if (!randomization.enabled) {
            if (randomization.radiusMeters != null || randomization.stableForMs != null) {
                return reject(
                    ReasonCode.CONTRADICTORY_FIELDS,
                    "Disabled randomization must not carry parameters",
                    "randomization",
                )
            }
            return null
        }
        val r = randomization.radiusMeters
            ?: return reject(ReasonCode.MISSING_FIELD, "Enabled randomization requires radiusMeters", "randomization.radiusMeters")
        if (!r.isFinite() || r < PrototypeConstants.MIN_RADIUS_METERS || r > PrototypeConstants.MAX_RADIUS_METERS) {
            return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "randomization.radiusMeters out of bounds", "randomization.radiusMeters")
        }
        val stable = randomization.stableForMs
        if (stable != null && (stable < 0 || stable > PrototypeConstants.MAX_INTERVAL_MS)) {
            return reject(ReasonCode.VALUE_OUT_OF_BOUNDS, "stableForMs out of bounds", "randomization.stableForMs")
        }
        if (randomization.seedMode == SeedMode.PERIOD_STABLE && (stable == null || stable <= 0)) {
            return reject(ReasonCode.MISSING_FIELD, "PERIOD_STABLE requires stableForMs > 0", "randomization.stableForMs")
        }
        if (randomization.seedMode == SeedMode.SESSION_STABLE && stable != null) {
            return reject(ReasonCode.CONTRADICTORY_FIELDS, "SESSION_STABLE must not carry stableForMs", "randomization.stableForMs")
        }
        return null
    }

    /** Background modes are a closed enum; structural validation is trivial but explicit. */
    @Suppress("unused")
    fun validateBackgroundMode(mode: BackgroundMode): ValidationResult = ValidationResult.Accepted
}
