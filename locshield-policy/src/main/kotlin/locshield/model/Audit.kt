package locshield.model

/**
 * Audit event value type only (Doc 11 section 30, Doc 07 C10).
 * Default records deliberately carry NO latitude/longitude: audit must never become
 * a raw-location disclosure channel (Doc 04 T18, SEC-012). The core only constructs
 * these values; persistence/emission is a future system-service concern, and
 * enforcement never depends on auditing succeeding.
 */
data class AuditEvent(
    val timestampWallMs: Long,
    val uid: Int,
    val packageName: String,
    val requestType: RequestType,
    val decision: Decision,
    val policyGeneration: Long,
    val reasonCode: ReasonCode,
    val sourceClass: SourceType,
)
