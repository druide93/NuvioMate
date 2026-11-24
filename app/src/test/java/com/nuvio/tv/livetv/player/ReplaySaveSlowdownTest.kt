package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySaveSlowdownTest {

    @Test
    fun `fast then slowed right down is throttled`() {
        assertTrue(replaySaveThrottled(peakRate = 17.0, rate = 1.2))
    }

    @Test
    fun `simply slow, even well under real time, asks for nothing`() {
        // beIN Sports 1: about real time, then 0.2x. A new connection there only ended the save.
        assertFalse(replaySaveThrottled(peakRate = 1.1, rate = 0.2))
        assertFalse(replaySaveThrottled(peakRate = 1.0, rate = 1.0))
    }

    @Test
    fun `still well ahead of playback asks for nothing`() {
        assertFalse(replaySaveThrottled(peakRate = 3.0, rate = 2.5))
        assertFalse(replaySaveThrottled(peakRate = 17.0, rate = 8.0))
    }

    @Test
    fun `a save that caught up with what has aired is near aired`() {
        // M6, 2026-11-24: link from 01:17:00, 410 s saved at 01:25:03 (73 s behind the clock).
        val linkStart = 1_795_475_820_000L
        val now = linkStart + 8 * 60_000 + 3_000
        assertTrue(replaySaveNearAired(linkStart, 410_000, now))
    }

    @Test
    fun `a save well behind what has aired is not near aired`() {
        val linkStart = 1_795_475_820_000L
        // Ten minutes saved of a show that started an hour ago: a slow save, worth a new connection.
        assertFalse(replaySaveNearAired(linkStart, 600_000, linkStart + 60 * 60_000))
        // The link's time isn't known (a time shift is set): as before.
        assertFalse(replaySaveNearAired(null, 600_000, linkStart + 11 * 60_000))
    }

    @Test
    fun `a provider that sent other bytes is remembered by its host`() {
        assertFalse(providerResends("https://resends.example/timeshift/a/b/41/2026-11-20:12-04/382.ts"))
        markProviderResends("https://resends.example/timeshift/a/b/41/2026-11-20:12-04/382.ts")
        assertTrue(providerResends("https://RESENDS.example/streaming/timeshift.php?stream=382"))
        assertFalse(providerResends("https://other.example/x.ts"))
    }
}

class SavedTailTest {

    private fun bytes(from: Int, n: Int) = ByteArray(n) { (from + it).toByte() }

    @Test
    fun `keeps the last bytes in order across wraps`() {
        val tail = SavedTail(10)
        tail.add(bytes(0, 7), 0, 7)
        tail.add(bytes(7, 6), 0, 6) // 0..12 written: holds 3..12
        assertEquals(10, tail.filled)
        assertEquals(bytes(3, 10).toList(), tail.last(10).toList())
        assertEquals(bytes(9, 4).toList(), tail.last(4).toList())
    }

    @Test
    fun `a write longer than it holds keeps its end`() {
        val tail = SavedTail(10)
        tail.add(bytes(0, 3), 0, 3)
        tail.add(bytes(100, 25), 5, 20) // bytes 105..124
        assertEquals(bytes(115, 10).toList(), tail.last(10).toList())
    }

    @Test
    fun `before it is full it holds what was written`() {
        val tail = SavedTail(10)
        tail.add(bytes(0, 4), 0, 4)
        assertEquals(4, tail.filled)
        assertEquals(bytes(0, 4).toList(), tail.last(4).toList())
    }

    @Test
    fun `same bytes compares the stretch asked for`() {
        val a = bytes(0, 20)
        assertTrue(sameBytes(a, 5, bytes(5, 10), 0, 10))
        assertFalse(sameBytes(a, 5, bytes(6, 10), 0, 10))
    }
}
