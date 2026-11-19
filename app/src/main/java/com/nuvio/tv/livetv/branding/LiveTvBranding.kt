package com.nuvio.tv.livetv.branding

import com.nuvio.tv.R

/**
 * "NuvioMate" branding. The fork's own logo images sit next to Nuvio's (they don't replace
 * them), so Nuvio's artwork stays untouched and future Nuvio updates merge cleanly.
 */
object LiveTvBranding {
    const val APP_NAME = "NuvioMate"
    const val TAGLINE = "Live TV  ·  On Demand  ·  Streaming"

    /** The "NuvioMate" version of a theme's Nuvio wordmark (any other image is returned as is). */
    fun iptvWordmark(nuvioWordmark: Int): Int = when (nuvioWordmark) {
        R.drawable.app_logo_wordmark_gold -> R.drawable.livetv_wordmark_iptv_gold
        R.drawable.app_logo_wordmark_jade -> R.drawable.livetv_wordmark_iptv_jade
        R.drawable.app_logo_wordmark_rose_gold -> R.drawable.livetv_wordmark_iptv_rose_gold
        R.drawable.app_logo_wordmark_arctic_blue -> R.drawable.livetv_wordmark_iptv_arctic_blue
        R.drawable.app_logo_wordmark_graphite -> R.drawable.livetv_wordmark_iptv_graphite
        R.drawable.app_logo_wordmark -> R.drawable.livetv_wordmark_iptv
        else -> nuvioWordmark
    }
}
