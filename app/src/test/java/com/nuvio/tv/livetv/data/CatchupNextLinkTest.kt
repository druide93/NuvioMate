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

    @Test fun `three hours at most, and only this kind of link`() {
        assertEquals("http://host/timeshift/u/p/180/2026-11-22:15-54/1330.ts", CatchupUrlBuilder.nextLink(url, start, start + 1000 * m)?.first)
        assertNull(CatchupUrlBuilder.nextLink("http://host/streaming/timeshift.php?stream=1&start=2026-11-22:15-52&duration=2", start, start + 1000 * m))
    }
}
