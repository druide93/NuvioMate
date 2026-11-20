package com.nuvio.tv.core.homeassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the app is showing, in the words Home Assistant automations use. */
enum class NowPlayingActivity(val haValue: String) {
    NONE("rien"),
    LIVE("direct"),
    CATCHUP("replay"),
    MOVIE("film"),
    EPISODE("episode")
}

enum class NowPlayingStatus(val haValue: String) {
    PLAYING("lecture"),
    PAUSED("pause"),
    LOADING("chargement"),
    STOPPED("arret")
}

/** One snapshot of what's on screen, built from the films/series player or the live TV player. */
data class NowPlaying(
    val activity: NowPlayingActivity,
    val status: NowPlayingStatus,
    /** Film title, show name, or the programme on a live channel. */
    val mediaTitle: String? = null,
    val channelName: String? = null,
    val channelNumber: Int? = null,
    val channelGroup: String? = null,
    val programTitle: String? = null,
    val programStartMs: Long? = null,
    val programEndMs: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val year: String? = null,
    val imageUrl: String? = null
) {
    /** A readable one-liner: "France 2 – Journal de 20h", "Dune (2024)", "The Bear – S3E4 · Fishes". */
    val summary: String
        get() = when (activity) {
            NowPlayingActivity.NONE -> ""
            NowPlayingActivity.LIVE, NowPlayingActivity.CATCHUP ->
                listOfNotNull(channelName, programTitle).filter { it.isNotBlank() }.joinToString(" – ")
            NowPlayingActivity.MOVIE ->
                listOfNotNull(mediaTitle, year?.let { "($it)" }).filter { it.isNotBlank() }.joinToString(" ")
            NowPlayingActivity.EPISODE -> buildString {
                append(mediaTitle.orEmpty())
                val code = if (season != null && episode != null) "S${season}E$episode" else null
                val detail = listOfNotNull(code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    if (isNotEmpty()) append(" – ")
                    append(detail)
                }
            }
        }

    companion object {
        val NOTHING = NowPlaying(NowPlayingActivity.NONE, NowPlayingStatus.STOPPED)
    }
}

/**
 * The films/series player lives inside its screen's ViewModel, so it reports here. Holds the
 * newest player only: an older player that closes late can't clear what a newer one reported.
 */
object VodNowPlaying {
    private data class Entry(val owner: Any, val value: NowPlaying)

    private val entry = MutableStateFlow<Entry?>(null)
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    fun publish(owner: Any, value: NowPlaying) {
        entry.value = Entry(owner, value)
        _state.value = value
    }

    fun clear(owner: Any) {
        entry.update { current -> if (current?.owner === owner) null else current }
        if (entry.value == null) _state.value = null
    }
}
