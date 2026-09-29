package locshield.model

/**
 * Explicit invalid-policy representation (Doc 11 section 23, Doc 13 VAL).
 * Kotlin's type system already rejects unknown enum constants at the boundary via
 * the `*Of()` parsers; this type covers every other malformed case so that an
 * invalid policy can never flow into evaluation as a permissive policy.
 */
sealed interface ValidationResult {
    data object Accepted : ValidationResult

    data class Rejected(
        val reason: ReasonCode,
        val message: String,
        val field: String? = null,
    ) : ValidationResult

    fun isAccepted(): Boolean = this is Accepted
}
