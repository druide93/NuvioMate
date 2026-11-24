package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchupNextLinkTest {
    private val m = 60_000L
    private val start = 1_800_000_000_000L - 1_800_000_000_000L % m
    private val url = "http://host/timeshift/u/p/2/2026-11-22:15-52/1330.ts"

    @Test fun `carries on from the minute the link ends at`() {
        val next = CatchupUrlBuilder.nextLink(url, start, start + 5 * m + 40_000)
        assertEquals("http://host/timeshift/u/p/3/2026-11-22:15-54/1330.ts" to start + 2 * m, next)
    }

    @Test fun `nothing when under a minute more has aired`() {
        assertNull(CatchupUrlBuilder.nextLink(url, start, start + 3 * m + 20_000))
    }

    @Test fun `past the hour and midnight, with the query kept`() {
        val late = "http://host/timeshift/u/p/10/2026-11-22:23-55/7.m3u8?x=1"
        assertEquals("http://host/timeshift/u/p/1/2026-11-23:00-05/7.m3u8?x=1", CatchupUrlBuilder.nextLink(late, start, start + 11 * m + 30_000)?.first)
    }

    // M6, 2026-11-24: asked at 01:32 from 01:25 to the show's end (02:10), 44 minutes; the provider
    // sent what had aired and ended there.
    private val onAir = "http://host/timeshift/u/p/44/2026-11-24:00-25/3988.ts"

    @Test fun `a link to the end of a show still on air carries on from where the save got to`() {
        // 6 min 40 s saved: on from the 6th minute, with what has aired since.
        val next = CatchupUrlBuilder.nextLink(onAir, start, start + 9 * m, savedUpToMs = start + 6 * m + 40_000)
        assertEquals("http://host/timeshift/u/p/2/2026-11-24:00-31/3988.ts" to start + 6 * m, next)
    }

    @Test fun `from where the save got to, it waits for a minute more to air`() {
        assertNull(CatchupUrlBuilder.nextLink(onAir, start, start + 7 * m + 20_000, savedUpToMs = start + 6 * m + 40_000))
    }

    @Test fun `a save that got to the link's end carries on from the link's end`() {
        val next = CatchupUrlBuilder.nextLink(url, start, start + 5 * m + 40_000, savedUpToMs = start + 2 * m - 3_000)
        assertEquals("http://host/timeshift/u/p/3/2026-11-22:15-54/1330.ts" to start + 2 * m, next)
    }

    @Test fun `a save that didn't get past the link's first minute goes by the link's end`() {
        // Nothing would move on from there: asking the same minute again and again.
        val next = CatchupUrlBuilder.nextLink(onAir, start, start + 46 * m, savedUpToMs = start + 40_000)
        assertEquals(start + 44 * m, next?.second)
    }

    @Test fun `three hours at most, and only this kind of link`() {
        assertEquals("http://host/timeshift/u/p/180/2026-11-22:15-54/1330.ts", CatchupUrlBuilder.nextLink(url, start, start + 1000 * m)?.first)
        assertNull(CatchupUrlBuilder.nextLink("http://host/streaming/timeshift.php?stream=1&start=2026-11-22:15-52&duration=2", start, start + 1000 * m))
    }
}
