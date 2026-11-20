package com.nuvio.tv.core.homeassistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What Home Assistant's media player card can ask NuvioMate to do. */
enum class HaCommand(val topicName: String) {
    PLAY_PAUSE("playpause"),
    PLAY("play"),
    PAUSE("pause"),
    NEXT("next"),
    PREVIOUS("previous");

    companion object {
        fun ofTopic(topic: String): HaCommand? = entries.firstOrNull { topic.endsWith("/cmd/${it.topicName}") }
    }
}

/**
 * Hands Home Assistant's commands to the player that's on screen. The films/series player and the
 * full-screen live player live in their screens, so they listen here; nobody listening means the
 * command is dropped, which is right (nothing to act on).
 */
object HaRemoteCommands {
    private val _vodToggle = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Play/pause for the films/series player (already decided: it always means "toggle"). */
    val vodToggle: SharedFlow<Unit> = _vodToggle.asSharedFlow()

    private val _liveZap = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** +1 / -1: next or previous channel, for the full-screen live player. */
    val liveZap: SharedFlow<Int> = _liveZap.asSharedFlow()

    fun toggleVod() = _vodToggle.tryEmit(Unit)
    fun zapLive(direction: Int) = _liveZap.tryEmit(direction)
}

/**
 * A media player for Home Assistant through the "MQTT Media Player" integration (bkbilly, from
 * the Marketplace), which reads its own discovery topic: no YAML needed. Each field has its own
 * retained topic. Plain Kotlin so it can be unit tested.
 */
class HaMediaPlayer(deviceId: String, private val deviceName: String, discoveryPrefix: String) {
    private val nodeId = "nuviomate_$deviceId"
    private val base = "nuviomate/$deviceId/mp"
    private val cmdBase = "nuviomate/$deviceId/cmd"

    val discoveryTopic = "${discoveryPrefix.trimEnd('/')}/media_player/$nodeId/lecteur/config"
    val availabilityTopic = "nuviomate/$deviceId/availability"
    val stateTopic = "$base/state"
    val titleTopic = "$base/title"
    val artistTopic = "$base/artist"
    val albumTopic = "$base/album"
    val mediaTypeTopic = "$base/mediatype"
    val albumArtTopic = "$base/albumart"
    val commandFilter = "$cmdBase/+"

    fun commandTopic(command: HaCommand) = "$cmdBase/${command.topicName}"

    fun discoveryPayload(): String = Json.write(
        linkedMapOf(
            "name" to deviceName,
            "unique_id" to "${nodeId}_lecteur",
            "device" to linkedMapOf("identifiers" to listOf("${nodeId}_lecteur"), "name" to deviceName, "manufacturer" to "NuvioMate"),
            "availability" to linkedMapOf(
                "topic" to availabilityTopic,
                "payload_available" to HaDiscovery.ONLINE,
                "payload_not_available" to HaDiscovery.OFFLINE
            ),
            "state_state_topic" to stateTopic,
            "state_title_topic" to titleTopic,
            "state_artist_topic" to artistTopic,
            "state_album_topic" to albumTopic,
            "state_mediatype_topic" to mediaTypeTopic,
            "state_albumart_topic" to albumArtTopic,
            "command_playpause_topic" to commandTopic(HaCommand.PLAY_PAUSE),
            "command_play_topic" to commandTopic(HaCommand.PLAY),
            "command_pause_topic" to commandTopic(HaCommand.PAUSE),
            "command_next_topic" to commandTopic(HaCommand.NEXT),
            "command_previous_topic" to commandTopic(HaCommand.PREVIOUS)
        )
    )

    /**
     * topic → payload for everything but the picture. On live TV the title is the programme and
     * the "artist" line the channel; for a series, the episode and the show; for a film, the film
     * and its year.
     */
    fun statePayloads(now: NowPlaying): Map<String, String> {
        val state = when {
            now.activity == NowPlayingActivity.NONE -> "idle"
            now.status == NowPlayingStatus.PAUSED -> "paused"
            now.status == NowPlayingStatus.STOPPED -> "idle"
            else -> "playing"
        }
        val (title, artist, album, type) = when (now.activity) {
            NowPlayingActivity.NONE -> Quad("", "", "", "video")
            NowPlayingActivity.LIVE, NowPlayingActivity.CATCHUP -> Quad(
                now.programTitle ?: now.channelName.orEmpty(),
                now.channelName.orEmpty(),
                now.channelGroup.orEmpty(),
                "channel"
            )
            NowPlayingActivity.MOVIE -> Quad(now.mediaTitle.orEmpty(), now.year.orEmpty(), "", "movie")
            NowPlayingActivity.EPISODE -> {
                val code = if (now.season != null && now.episode != null) "S${now.season}E${now.episode}" else null
                Quad(
                    listOfNotNull(code, now.episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { now.mediaTitle.orEmpty() },
                    now.mediaTitle.orEmpty(),
                    now.season?.let { "Saison $it" }.orEmpty(),
                    "episode"
                )
            }
        }
        return linkedMapOf(
            stateTopic to state,
            titleTopic to title,
            artistTopic to artist,
            albumTopic to album,
            mediaTypeTopic to type
        )
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}
