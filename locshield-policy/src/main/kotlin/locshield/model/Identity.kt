package locshield.model

/**
 * Application identity (Doc 07 section 8, Doc 11 section 15).
 *
 * In production this tuple is populated by the trusted Application Identity
 * Resolver from Android caller context (UID / user / validated package /
 * attribution). The pure engine treats it as an opaque, already-trusted input:
 * it never validates identity against Binder itself, and a client-supplied
 * package name alone is never sufficient proof (Doc 04 section 16).
 *
 * Used as the isolation key for policy lookup, temporal state and
 * randomization state: different identities can never share mutable state.
 */
data class AppIdentity(
    val uid: Int,
    val userId: Int,
    val packageName: String,
    val attributionTag: String? = null,
    val featureId: String? = null,
)

/**
 * Selector used for policy management operations (Doc 11 section 6).
 * Administrative targeting of another package requires privileged authority,
 * which lives in the future system service, not in this pure core.
 */
data class ApplicationSelector(
    val packageName: String,
    val userId: Int,
)
