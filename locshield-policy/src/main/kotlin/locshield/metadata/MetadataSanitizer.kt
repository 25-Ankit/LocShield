package locshield.metadata

import locshield.model.FieldHandling
import locshield.model.LocationSample
import locshield.model.MetadataPolicy
import locshield.model.PrototypeConstants
import locshield.model.ReasonCode
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import kotlin.math.round

/**
 * Metadata sanitizer (Doc 07 C08, Doc 11 section 19, Doc 12 sections 19-20).
 *
 * Runs AFTER spatial transformation so metadata stays consistent with the final
 * precision. The security invariant: the combined output object must never imply
 * greater precision than the spatial policy allows (Doc 12 section 12, P6).
 * In particular the accuracy floor from the spatial mode OVERRIDES a RETAIN
 * metadata setting - a precise accuracy paired with a coarse coordinate would
 * restore precision (Doc 04 T11/T17).
 */
object MetadataSanitizer {
    sealed interface SanitizeOutcome {
        data class Ok(val location: LocationSample) : SanitizeOutcome

        data class Failed(val reason: ReasonCode) : SanitizeOutcome
    }

    fun sanitize(
        input: LocationSample,
        spatial: SpatialPolicy,
        policy: MetadataPolicy,
    ): SanitizeOutcome {
        return try {
            if (!input.latitude.isFinite() || !input.longitude.isFinite()) {
                return SanitizeOutcome.Failed(ReasonCode.METADATA_FAILURE)
            }
            val floor = accuracyFloorMeters(spatial)
            var out = input

            // Accuracy: never claim more precision than the spatial policy (MET-001).
            val widenedAccuracy = maxOf(input.accuracyMeters, floor)
            out = out.copy(accuracyMeters = widenedAccuracy)

            out = when (policy.timestampMode) {
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE -> out.copy(
                    timestampWallMs = bucketWall(out.timestampWallMs, PrototypeConstants.TIMESTAMP_BUCKET_MS),
                )
                FieldHandling.DENY -> out.copy(timestampWallMs = 0L)
            }

            out = when (policy.elapsedRealtimeMode) {
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE -> out.copy(
                    elapsedRealtimeNanos = bucketNanos(
                        out.elapsedRealtimeNanos,
                        PrototypeConstants.ELAPSED_BUCKET_NANOS,
                    ),
                )
                FieldHandling.DENY -> out.copy(elapsedRealtimeNanos = 0L)
            }

            out = when (policy.altitudeMode) {
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE -> out.copy(
                    altitudeMeters = out.altitudeMeters?.let {
                        round(it / PrototypeConstants.ALTITUDE_COARSE_STEP_M) *
                            PrototypeConstants.ALTITUDE_COARSE_STEP_M
                    },
                )
                FieldHandling.DENY -> out.copy(altitudeMeters = null)
            }

            out = when (policy.speedMode) {
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE -> out.copy(
                    speedMps = out.speedMps?.let {
                        if (!it.isFinite() || it < 0f) {
                            null
                        } else {
                            round(it / PrototypeConstants.SPEED_COARSE_STEP_MPS) *
                                PrototypeConstants.SPEED_COARSE_STEP_MPS
                        }
                    },
                )
                FieldHandling.DENY -> out.copy(speedMps = null)
            }

            out = when (policy.bearingMode) {
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE -> out.copy(
                    bearingDeg = out.bearingDeg?.let {
                        if (!it.isFinite()) {
                            null
                        } else {
                            ((round(it / PrototypeConstants.BEARING_COARSE_STEP_DEG) *
                                PrototypeConstants.BEARING_COARSE_STEP_DEG) % 360f + 360f) % 360f
                        }
                    },
                )
                FieldHandling.DENY -> out.copy(bearingDeg = null)
            }

            out = when (policy.providerMode) {
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE -> out.copy(provider = out.provider?.let { "coarse" })
                FieldHandling.DENY -> out.copy(provider = null)
            }

            out = when (policy.extrasMode) {
                // v0.1 location-bearing allowlist is empty: COARSE strips unknown
                // fields exactly like DENY (MET-008). Documented prototype choice.
                FieldHandling.RETAIN -> out
                FieldHandling.COARSE, FieldHandling.DENY -> out.copy(extras = emptyMap())
            }

            if (!out.accuracyMeters.isFinite() || out.accuracyMeters < 0f) {
                return SanitizeOutcome.Failed(ReasonCode.METADATA_FAILURE)
            }
            SanitizeOutcome.Ok(out)
        } catch (_: Exception) {
            SanitizeOutcome.Failed(ReasonCode.METADATA_FAILURE)
        }
    }

    /**
     * Minimum accuracy the output may claim, derived from the spatial mode.
     * EXACT/ANDROID_CEILING impose no floor (platform accuracy governs).
     */
    fun accuracyFloorMeters(spatial: SpatialPolicy): Float =
        when (spatial.mode) {
            SpatialMode.EXACT, SpatialMode.ANDROID_CEILING -> 0f
            SpatialMode.DENY -> Float.POSITIVE_INFINITY
            SpatialMode.GRID -> spatial.gridMeters?.toFloat() ?: Float.POSITIVE_INFINITY
            SpatialMode.RADIUS -> spatial.radiusMeters?.toFloat() ?: Float.POSITIVE_INFINITY
            SpatialMode.CITY -> PrototypeConstants.CITY_ACCURACY_METERS
            SpatialMode.RANDOMIZED -> Float.POSITIVE_INFINITY // bound unknown here; transform already widened
        }

    private fun bucketWall(value: Long, bucket: Long): Long {
        if (bucket <= 0) return value
        return if (value >= 0) (value / bucket) * bucket else ((value - bucket + 1) / bucket) * bucket
    }

    private fun bucketNanos(value: Long, bucket: Long): Long {
        if (bucket <= 0 || value < 0) return 0L
        return (value / bucket) * bucket
    }
}
