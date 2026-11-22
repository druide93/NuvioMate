package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyStarvationTest {

    /**
     * A replay playing from its copy, checked every 2 s like the stall watch, close to how the
     * app does it:
     *  - the save comes in at [rateAt] (seconds of show per second, from when its request was
     *    answered) and counts in 6 s pieces, as the copy's list does;
     *  - the player only sees new pieces when it reads the list again: 10 s after a read that
     *    found more, 5 s after one that didn't;
     *  - once it ran out it waits for 4 s more before it plays on;
     *  - links start on the whole minute; a new request (what [onAsk] stands for, when it's
     *    acted on) waits under the waiting screen until it has the point where it froze plus
     *    13 s saved, or 45 s at most, then plays from there (or from 1 s before what's saved).
     * Times are kept as show time from the show's start ([absolute] positions).
     */
    private inner class Sim(
        /** The save's speed, by time since its request was answered. */
        val rateAt: (Long) -> Double,
        /** A new request when asked (false: nothing changes, the same save goes on). */
        val asksAgain: Boolean = true,
        /** Where playback starts in the show. */
        startAt: Long = 27_000,
    ) {
        val watch = CopyStarvation()
        var now = 0L
        var linkStart = (startAt / 60_000) * 60_000
        var requestAt = 0L
        var saved = (startAt - linkStart) + 13_000.0
        var position = startAt - linkStart
        var listed = 0L
        var nextRead = 0L
        var stalled = false
        var waitingForCopy = false
        val asks = ArrayList<Long>()
        var gaveUp = false
        var frozenMs = 0L
        var replayedMs = 0L

        val absolute get() = linkStart + position
        private fun savedListed(): Long = (saved.toLong() / 6_000) * 6_000

        fun run(untilMs: Long) {
            while (now < untilMs) {
                now += 2_000
                saved += rateAt(now - requestAt) * 2_000
                if (waitingForCopy) {
                    frozenMs += 2_000
                    val lead = position
                    val waited = now - requestAt
                    if (savedListed() >= lead + 13_000 || waited >= 45_000) {
                        waitingForCopy = false
                        val start = minOf(lead, savedListed() - 1_000).coerceAtLeast(0)
                        replayedMs += lead - start
                        position = start
                        listed = savedListed()
                        nextRead = now + 10_000
                        stalled = false
                    }
                    watch.notWatching()
                    continue
                }
                if (now >= nextRead) {
                    val more = savedListed() > listed
                    listed = savedListed()
                    nextRead = now + if (more) 10_000 else 5_000
                }
                val ahead = listed - position
                if (stalled && ahead >= 4_000) stalled = false
                if (!stalled) {
                    if (ahead >= 2_000) position += 2_000 else { position = listed; stalled = true }
                }
                if (stalled) frozenMs += 2_000
                when (watch.check(now, savedListed(), position, waiting = stalled)) {
                    CopyStarvation.Verdict.ASK_AGAIN -> {
                        asks += now
                        if (asksAgain) newRequest()
                    }
                    CopyStarvation.Verdict.GIVE_UP -> gaveUp = true
                    CopyStarvation.Verdict.WAIT -> Unit
                }
            }
        }

        /** Asked again from where it froze: a new link from the whole minute, a new save. */
        private fun newRequest() {
            val point = absolute
            linkStart = (point / 60_000) * 60_000
            position = point - linkStart
            requestAt = now
            saved = 0.0
            waitingForCopy = true
            watch.newCopy()
        }
    }

    /** PureTv: 1.2x for the first 90 s of each request, then 0.25x. */
    private val pureTv: (Long) -> Double = { sinceAsked -> if (sinceAsked < 90_000) 1.2 else 0.25 }

    @Test
    fun `a save that keeps up never asks again`() {
        // OTT: 8-11x.
        val sim = Sim({ 9.0 })
        sim.run(20 * 60_000)
        assertTrue(sim.asks.isEmpty())
    }

    @Test
    fun `waiting at the end of a copy whose save keeps up is not running dry`() {
        // The player hasn't read the copy's list again yet (it does every 10 s), while the save
        // comes in at 1.2x: frozen at the end for as long as you like, never a request.
        val watch = CopyStarvation()
        var t = 0L
        var saved = 30_000L
        repeat(40) {
            t += 2_000
            saved += 2_400
            assertEquals(CopyStarvation.Verdict.WAIT, watch.check(t, saved, positionMs = saved - 6_000, waiting = true))
        }
    }

    @Test
    fun `waiting well before the end of what's saved is not running dry`() {
        // 20 s still saved past where it waits (something else holds it up): not this.
        val watch = CopyStarvation()
        var t = 0L
        var saved = 30_000L
        repeat(40) {
            t += 2_000
            saved += 400 // 0.2x
            assertEquals(CopyStarvation.Verdict.WAIT, watch.check(t, saved, positionMs = saved - 20_000, waiting = true))
        }
    }

    @Test
    fun `slow and frozen at the end asks once it has been frozen 12 s`() {
        val watch = CopyStarvation()
        var t = 0L
        var saved = 30_000L
        fun step(waiting: Boolean) = run { t += 2_000; saved += 400; watch.check(t, saved, saved - 2_000, waiting) }
        // Long enough to know the save's speed (0.2x), playing.
        repeat(12) { assertEquals(CopyStarvation.Verdict.WAIT, step(waiting = false)) }
        repeat(5) { assertEquals(CopyStarvation.Verdict.WAIT, step(waiting = true)) }
        assertEquals(CopyStarvation.Verdict.ASK_AGAIN, step(waiting = true))
        assertTrue(watch.askedAtRate in 0.15..0.25)
    }

    @Test
    fun `a save just under real time asks nothing`() {
        // 0.95x: it reaches the end of the copy after a few minutes, then stops now and then, but
        // a new request wouldn't do better.
        val sim = Sim({ 0.95 })
        sim.run(10 * 60_000)
        assertTrue(sim.asks.isEmpty())
    }

    @Test
    fun `a copy that runs dry on a slow save carries on from where it froze`() {
        val sim = Sim(rateAt = pureTv)
        sim.run(10 * 60_000)
        assertTrue("asked again at least once", sim.asks.isNotEmpty())
        // Not back to what was already watched (the whole minute the new link starts at): at
        // most a few seconds, when the 45 s wait ran out just before the point.
        assertTrue("played again ${sim.replayedMs / 1000} s over ${sim.asks.size} requests", sim.replayedMs <= 8_000L * sim.asks.size)
        // Frozen less than before (see the test below), waiting screens included.
        assertTrue("frozen ${sim.frozenMs / 1000} s of 600", sim.frozenMs < 300_000)
        assertTrue("got ${sim.absolute / 1000} s into the show", sim.absolute > 330_000)
    }

    @Test
    fun `without asking again the same replay is frozen most of the time`() {
        // What the app did on PureTv before (nothing stepped in): the comparison for the test above.
        val sim = Sim(rateAt = pureTv, asksAgain = false)
        sim.run(10 * 60_000)
        assertTrue("frozen ${sim.frozenMs / 1000} s of 600", sim.frozenMs > 350_000)
        // At most what it sends: 13 s, 90 s at 1.2x, then a quarter of real time.
        assertTrue("got ${sim.absolute / 1000} s into the show", sim.absolute < 290_000)
        // Asking again gets well further in the same 10 minutes.
        val asking = Sim(rateAt = pureTv).also { it.run(10 * 60_000) }
        assertTrue("${asking.absolute / 1000} s against ${sim.absolute / 1000} s", asking.absolute > sim.absolute + 60_000)
    }

    @Test
    fun `at most one request every 45 s`() {
        // A request that changes nothing (the save goes on as slow as before).
        val sim = Sim(rateAt = { 0.2 }, asksAgain = false)
        sim.run(5 * 60_000)
        assertTrue(sim.asks.size >= 2)
        sim.asks.zipWithNext().forEach { (a, b) -> assertTrue("asked ${(b - a) / 1000} s apart", b - a >= 45_000) }
    }

    @Test
    fun `it stops asking when requests don't help`() {
        // Every new request is just as slow from its first second: three tries, then it waits.
        val sim = Sim(rateAt = { 0.2 })
        sim.run(20 * 60_000)
        assertTrue(sim.gaveUp)
        assertEquals(3, sim.asks.size)
    }

    @Test
    fun `requests that help keep it asking`() {
        val sim = Sim(rateAt = pureTv)
        sim.run(30 * 60_000)
        assertTrue(!sim.gaveUp)
        assertTrue(sim.asks.size >= 5)
    }

    @Test
    fun `nothing is decided before the save's speed is known`() {
        // Frozen from the start (a copy that started with nothing ahead): no request in the
        // first 20 s, the time it takes to know how fast the save comes in.
        val watch = CopyStarvation()
        var t = 0L
        while (t < 18_000) {
            t += 2_000
            assertEquals(CopyStarvation.Verdict.WAIT, watch.check(t, savedMs = 6_000, positionMs = 6_000, waiting = true))
        }
    }

    @Test
    fun `a request not acted on doesn't count`() {
        val watch = CopyStarvation()
        var t = 0L
        var saved = 30_000L
        fun step(waiting: Boolean) = run { t += 2_000; saved += 400; watch.check(t, saved, saved - 2_000, waiting) }
        repeat(12) { step(waiting = false) }
        repeat(5) { step(waiting = true) }
        assertEquals(CopyStarvation.Verdict.ASK_AGAIN, step(waiting = true))
        // A skip of yours was on its way: not asked.
        watch.declined()
        // Still frozen: it asks again as soon as it knows the speed again and has been frozen
        // 12 s more, without the 45 s between requests.
        val verdicts = (1..12).map { step(waiting = true) }
        val first = verdicts.indexOf(CopyStarvation.Verdict.ASK_AGAIN)
        assertTrue("asked again after ${(first + 1) * 2} s: $verdicts", first in 9..11)
    }

    @Test
    fun `paused at the end of the copy is not frozen`() {
        val watch = CopyStarvation()
        var t = 0L
        var saved = 30_000L
        fun step(waiting: Boolean) = run { t += 2_000; saved += 400; watch.check(t, saved, saved - 1_000, waiting) }
        // Playing on a 0.2x save long enough to know its speed, then 10 s frozen at the end.
        repeat(12) { step(waiting = false) }
        repeat(5) { assertEquals(CopyStarvation.Verdict.WAIT, step(waiting = true)) }
        // Paused there: that wait doesn't count.
        watch.notWatching()
        // Playing again: frozen 10 s more is not yet the 12 s it takes.
        repeat(5) { assertEquals(CopyStarvation.Verdict.WAIT, step(waiting = true)) }
        // Counted from the pause, 12 s frozen: it asks.
        assertEquals(CopyStarvation.Verdict.ASK_AGAIN, step(waiting = true))
    }
}
