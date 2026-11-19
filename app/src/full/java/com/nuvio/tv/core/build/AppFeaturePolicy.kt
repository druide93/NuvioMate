package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

object AppFeaturePolicy {
    val pluginsEnabled: Boolean = true
    // NuvioMate: off in the debug app (NuvioMate Dev), which can't install release-signed updates.
    val inAppUpdatesEnabled: Boolean = BuildConfig.FEATURE_IN_APP_UPDATES_ENABLED
    val inAppTrailerPlaybackEnabled: Boolean = true
    val externalTrailerPlaybackEnabled: Boolean = true
    val supportNuvioEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true
}
