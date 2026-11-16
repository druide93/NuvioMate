package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupUrlBuilderFormatTest {
    private val xtream = CatchupInfo(type = "xc", source = null, days = 7)
    private val start = 1_794_861_000_000L
    private val stop = start + 30 * 60_000L

    private fun link(liveUrl: String, preferHls: Boolean): String =
        CatchupUrlBuilder.build(liveUrl, xtream, start, stop, stop + 60_000L, preferHls = preferHls, serverTimezone = "UTC")!!

    @Test
    fun preferHlsOffAsksForTsEvenOnAnM3u8Channel() {
        val url = link("https://host.example/live/user/pass/4249.m3u8", preferHls = false)
        assertTrue(url, url.endsWith("/4249.ts"))
    }

    @Test
    fun preferHlsOffAsksForTsOnATsChannel() {
        val url = link("https://host.example/user/pass/4249.ts", preferHls = false)
        assertTrue(url, url.endsWith("/4249.ts"))
    }

    @Test
    fun preferHlsOnAsksForHlsOnATsChannel() {
        val url = link("https://host.example/user/pass/4249.ts", preferHls = true)
        assertTrue(url, url.endsWith("/4249.m3u8"))
    }
}
