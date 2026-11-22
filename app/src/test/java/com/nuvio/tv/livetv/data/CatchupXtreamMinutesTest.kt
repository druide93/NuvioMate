package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatchupXtreamMinutesTest {
    private val t0 = 1_800_000_000_000L - 1_800_000_000_000L % 60_000L // on a whole minute
    private val later = t0 + 24 * 3_600_000L

    @Test
    fun `a show on whole minutes keeps its length`() {
        assertEquals(60L, CatchupUrlBuilder.xtreamMinutes(t0, 3_600, later))
    }

    @Test
    fun `a start inside a minute still reaches the end asked for`() {
        // 9:10:45 for 10 min ends at 9:20:45; the link starts at 9:10:00, so it needs 11 minutes.
        assertEquals(11L, CatchupUrlBuilder.xtreamMinutes(t0 + 45_000, 600, later))
    }

    @Test
    fun `never rounded up past now`() {
        // Ends 30 s into a minute, 40 s into which it is now: rounding up would reach the future.
        val start = t0 + 30_000
        val end = start + 600_000
        assertEquals(10L, CatchupUrlBuilder.xtreamMinutes(start, 600, end + 10_000))
    }

    @Test
    fun `at least a minute`() {
        assertEquals(1L, CatchupUrlBuilder.xtreamMinutes(t0, 20, later))
    }
}
