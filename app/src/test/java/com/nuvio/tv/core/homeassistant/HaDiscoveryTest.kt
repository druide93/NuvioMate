package com.nuvio.tv.core.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaDiscoveryTest {

    private val discovery = HaDiscovery(
        deviceId = "abc123",
        deviceName = "Télé Salon",
        discoveryPrefix = "homeassistant",
        appVersion = "1.2.3.mate.4"
    )

    @Test
    fun `topics are per device`() {
        assertEquals("homeassistant/device/nuviomate_abc123/config", discovery.discoveryTopic)
        assertEquals("nuviomate/abc123/state", discovery.stateTopic)
        assertEquals("nuviomate/abc123/availability", discovery.availabilityTopic)
        assertEquals("nuviomate/abc123/image", discovery.imageTopic)
    }

    @Test
    fun `discovery announces the device and its five entities`() {
        val json = discovery.discoveryPayload()
        assertTrue(json.contains("\"ids\":[\"nuviomate_abc123\"]"))
        assertTrue(json.contains("\"name\":\"Télé Salon\""))
        for (key in listOf("activite", "etat", "en_cours", "chaine", "affiche")) {
            assertTrue("missing $key", json.contains("\"uniq_id\":\"nuviomate_abc123_$key\""))
        }
        assertTrue(json.contains("\"default_entity_id\":\"sensor.nuviomate_activite\""))
        assertTrue(json.contains("\"default_entity_id\":\"image.nuviomate_affiche\""))
        assertTrue(json.contains("\"ops\":[\"rien\",\"direct\",\"replay\",\"film\",\"episode\"]"))
        assertTrue(json.contains("\"ops\":[\"lecture\",\"pause\",\"chargement\",\"arret\"]"))
        assertTrue(json.contains("\"avty_t\":\"nuviomate/abc123/availability\""))
        assertTrue(json.contains("\"url_t\":\"nuviomate/abc123/image\""))
    }

    @Test
    fun `live state carries channel and programme`() {
        val now = NowPlaying(
            activity = NowPlayingActivity.LIVE,
            status = NowPlayingStatus.PLAYING,
            mediaTitle = "Journal de 20h",
            channelName = "France 2",
            channelNumber = 2,
            channelGroup = "TNT",
            programTitle = "Journal de 20h",
            programStartMs = 0L,
            programEndMs = 1_800_000L,
            imageUrl = "https://example.org/f2.png"
        )
        val json = discovery.statePayload(now, "Thomas")
        assertTrue(json.startsWith("{\"activite\":\"direct\",\"etat\":\"lecture\",\"en_cours\":\"France 2 – Journal de 20h\",\"chaine\":\"France 2\""))
        assertTrue(json.contains("\"numero\":2"))
        assertTrue(json.contains("\"debut\":\"1970-01-01T00:00:00Z\""))
        assertTrue(json.contains("\"fin\":\"1970-01-01T00:30:00Z\""))
        assertTrue(json.contains("\"profil\":\"Thomas\""))
        assertEquals("https://example.org/f2.png", discovery.imageUrl(now))
    }

    @Test
    fun `nothing playing sends nulls and the fallback image`() {
        val json = discovery.statePayload(NowPlaying.NOTHING, null)
        assertTrue(json.startsWith("{\"activite\":\"rien\",\"etat\":\"arret\",\"en_cours\":null,\"chaine\":null,"))
        assertEquals(HaDiscovery.FALLBACK_IMAGE, discovery.imageUrl(NowPlaying.NOTHING))
    }

    @Test
    fun `summaries read naturally`() {
        assertEquals(
            "Dune (2024)",
            NowPlaying(NowPlayingActivity.MOVIE, NowPlayingStatus.PLAYING, mediaTitle = "Dune", year = "2024").summary
        )
        assertEquals(
            "The Bear – S3E4 · Fishes",
            NowPlaying(
                NowPlayingActivity.EPISODE, NowPlayingStatus.PAUSED,
                mediaTitle = "The Bear", season = 3, episode = 4, episodeTitle = "Fishes"
            ).summary
        )
        assertEquals(
            "TF1",
            NowPlaying(NowPlayingActivity.LIVE, NowPlayingStatus.LOADING, channelName = "TF1").summary
        )
    }

    @Test
    fun `json escapes quotes and control characters`() {
        assertEquals("{\"a\":\"say \\\"hi\\\"\\n\",\"b\":[1,true,null]}", Json.write(linkedMapOf("a" to "say \"hi\"\n", "b" to listOf(1, true, null))))
        assertFalse(Json.write("tab\there").contains("\t"))
    }

    @Test
    fun `newest player wins and an old one cannot clear it`() {
        val older = Any()
        val newer = Any()
        val movie = NowPlaying(NowPlayingActivity.MOVIE, NowPlayingStatus.PLAYING, mediaTitle = "A")
        VodNowPlaying.publish(older, movie)
        VodNowPlaying.publish(newer, movie.copy(mediaTitle = "B"))
        VodNowPlaying.clear(older)
        assertEquals("B", VodNowPlaying.state.value?.mediaTitle)
        VodNowPlaying.clear(newer)
        assertEquals(null, VodNowPlaying.state.value)
    }
}
