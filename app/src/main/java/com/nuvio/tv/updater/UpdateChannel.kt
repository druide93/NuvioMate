package com.nuvio.tv.updater

enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true)
        }

        // NuvioMate: new installs start on Stable. The version can't tell a beta (every NuvioMate
        // version has a suffix): betas are the GitHub releases marked "pre-release" (ReleaseSelector).
        @Suppress("UNUSED_PARAMETER")
        fun defaultForVersion(versionName: String): UpdateChannel = STABLE
    }
}
