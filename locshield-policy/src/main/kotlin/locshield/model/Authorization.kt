package locshield.model

/**
 * Android authorization ceiling supplied by the future Enforcement Adapter from
 * platform permission/AppOps state (Doc 12 sections 6-7, Doc 05 FR-020).
 * The pure core only intersects against it; it never performs permission checks.
 */
data class AndroidAuthorization(
    val locationAllowed: Boolean,
    /** True when the platform granted approximate (coarse) but not precise location. */
    val approximateOnly: Boolean,
    val backgroundAllowed: Boolean,
) {
    companion object {
        fun denied(): AndroidAuthorization =
            AndroidAuthorization(
                locationAllowed = false,
                approximateOnly = false,
                backgroundAllowed = false,
            )

        fun precise(foregroundOnly: Boolean = false): AndroidAuthorization =
            AndroidAuthorization(
                locationAllowed = true,
                approximateOnly = false,
                backgroundAllowed = !foregroundOnly,
            )

        fun approximate(backgroundAllowed: Boolean = false): AndroidAuthorization =
            AndroidAuthorization(
                locationAllowed = true,
                approximateOnly = true,
                backgroundAllowed = backgroundAllowed,
            )
    }
}
