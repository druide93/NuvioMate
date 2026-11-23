package com.nuvio.tv.core.image

// From Nuvio Arc (ysosrs123/NuvioTV-Fork, GPL-3.0), core/image/HomePosterLoading.kt.

/** A smaller TMDB size for artwork shown at [width] pixels or less. */
fun sizedHomeArtworkUrl(data: String, width: Int?): String {
    if (!data.startsWith("https://image.tmdb.org/t/p/original/") || width == null || width !in 1..780) return data
    val bucket = when { width <= 342 -> "w342"; width <= 500 -> "w500"; else -> "w780" }
    return data.replace("/t/p/original/", "/t/p/$bucket/")
}
