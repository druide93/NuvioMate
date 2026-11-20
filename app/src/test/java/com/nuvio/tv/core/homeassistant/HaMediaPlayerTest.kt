package com.nuvio.tv.core.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaMediaPlayerTest {

    private val player = HaMediaPlayer("abc123", "NuvioMate-Salon", "homeassistant")

    @Test
    fun `discovery goes to the MQTT Media Player topic with every field`() {
        assertEquals("homeassistant/media_player/nuviomate_abc123/lecteur/config", player.discoveryTopic)
        val json = player.discoveryPayload()
        listOf(
            "\"name\":\"NuvioMate-Salon\"",
            "\"state_state_topic\":\"nuviomate/abc123/mp/state\"",
            "\"state_albumart_topic\":\"nuviomate/abc123/mp/albumart\"",
            "\"command_playpause_topic\":\"nuviomate/abc123/cmd/playpause\"",
            "\"command_next_topic\":\"nuviomate/abc123/cmd/next\"",
            "\"topic\":\"nuviomate/abc123/availability\""
        ).forEach { assertTrue(it, it in json) }
    }

    @Test
    fun `live shows the programme and the channel`() {
        val live = NowPlaying(
            NowPlayingActivity.LIVE, NowPlayingStatus.PLAYING,
            channelName = "France 2", channelGroup = "TNT", programTitle = "Journal de 20h"
        )
        val p = player.statePayloads(live)
        assertEquals("playing", p[player.stateTopic])
        assertEquals("Journal de 20h", p[player.titleTopic])
        assertEquals("France 2", p[player.artistTopic])
        assertEquals("channel", p[player.mediaTypeTopic])
        // No guide: the channel stands in for the title.
        assertEquals("France 2", player.statePayloads(live.copy(programTitle = null))[player.titleTopic])
    }

    @Test
    fun `series and films`() {
        val episode = NowPlaying(
            NowPlayingActivity.EPISODE, NowPlayingStatus.PAUSED,
            mediaTitle = "La Meneuse", season = 2, episode = 4, episodeTitle = "MVP"
        )
        val e = player.statePayloads(episode)
        assertEquals("paused", e[player.stateTopic])
        assertEquals("S2E4 · MVP", e[player.titleTopic])
        assertEquals("La Meneuse", e[player.artistTopic])
        assertEquals("Saison 2", e[player.albumTopic])

        val film = player.statePayloads(NowPlaying(NowPlayingActivity.MOVIE, NowPlayingStatus.LOADING, mediaTitle = "Dune", year = "2024"))
        assertEquals("playing", film[player.stateTopic])
        assertEquals("Dune", film[player.titleTopic])
        assertEquals("2024", film[player.artistTopic])
        assertEquals("movie", film[player.mediaTypeTopic])

        assertEquals("idle", player.statePayloads(NowPlaying.NOTHING)[player.stateTopic])
    }

    @Test
    fun `commands are read from their topic`() {
        assertEquals(HaCommand.PLAY_PAUSE, HaCommand.ofTopic("nuviomate/abc123/cmd/playpause"))
        assertEquals(HaCommand.PREVIOUS, HaCommand.ofTopic("nuviomate/abc123/cmd/previous"))
        assertNull(HaCommand.ofTopic("nuviomate/abc123/cmd/volume"))
    }
}
