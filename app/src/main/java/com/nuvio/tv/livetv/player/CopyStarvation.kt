package com.nuvio.tv.livetv.player

/**
 * Watches a catch-up replay that plays from its copy on this box while the copy is still being
 * saved, for the copy running dry: playback caught up with what's saved, and the save comes in
 * slower than it plays.
 *
 * Seen on PureTv: each request comes in at 1-1.3x real time for the first 40-130 s, then at
 * 0.2-0.3x. The picture froze at the end of what was saved, played a few seconds each time a
 * piece came in, and froze again, for as long as the replay lasted. Nothing stepped in: each few
 * seconds of play counted as playing, and a piece came in less than a minute apart. Only a skip
 * past what's saved got it going, because that asks the provider again, and a new request gets
 * the fast start again. [check] says when to do the same by itself.
 *
 * Not for a save that keeps up (OTT saves at 8-11x): waiting at the end of the copy there only
 * means the player hasn't read the copy's list again yet.
 *
 * Plain numbers in and out, so it's tested on its own (see CopyStarvationTest).
 */
internal class CopyStarvation(
    /** How often [check] is called (each frozen check counts this long). */
    private val periodMs: Long = 2_000,
    /** Frozen at the end of the copy for this long… */
    private val frozenNeededMs: Long = 12_000,
    /** …out of the last this long (the few seconds played as each piece comes in don't reset it). */
    private val windowMs: Long = 30_000,
    /** The save's speed is measured over this long. */
    private val rateWindowMs: Long = 30_000,
    /** Slower than this (seconds saved per second) is too slow to play on. */
    private val slowRate: Double = 0.9,
    /** Closer than this to the end of what's saved counts as at the end. */
    private val edgeMs: Long = 15_000,
    /** At most one request every this long. */
    private val minGapMs: Long = 45_000,
    /** A request after which it played this long (not waiting) helped. */
    private val helpedPlayMs: Long = 45_000,
    /** Requests running that didn't help before it stops asking. */
    private val maxFruitless: Int = 3,
) {
    enum class Verdict { WAIT, ASK_AGAIN, GIVE_UP }

    private val frozen = ArrayDeque<Long>()
    private val saves = ArrayDeque<LongArray>()
    private var askedAt: Long? = null
    private var helpedSinceAsked = false
    private var playedSinceAsked = 0L
    private var fruitless = 0
    private var gaveUp = false

    /** How things stood before the last [Verdict.ASK_AGAIN], for [declined]. */
    private class Before(val askedAt: Long?, val helped: Boolean, val played: Long, val fruitless: Int)
    private var before: Before? = null

    /** The save's speed (seconds saved per second of wall time) when it last said [Verdict.ASK_AGAIN]. */
    var askedAtRate: Double = 0.0
        private set

    /** A new replay: everything starts over. */
    fun reset() {
        frozen.clear()
        saves.clear()
        askedAt = null
        helpedSinceAsked = false
        playedSinceAsked = 0L
        fruitless = 0
        gaveUp = false
        before = null
    }

    /**
     * The last [Verdict.ASK_AGAIN] wasn't acted on (a skip of yours was on its way, say): it
     * doesn't count, and the next check can say so again once it's still frozen.
     */
    fun declined() {
        val b = before ?: return
        askedAt = b.askedAt
        helpedSinceAsked = b.helped
        playedSinceAsked = b.played
        fruitless = b.fruitless
        before = null
    }

    /** A new copy is being saved (its own amounts): what was measured on the last one goes. */
    fun newCopy() {
        frozen.clear()
        saves.clear()
    }

    /** Not playing from a copy that's still being saved right now (paused, loading, ended…). */
    fun notWatching() {
        frozen.clear()
    }

    /**
     * Every [periodMs]: [savedMs] saved so far and playback at [positionMs] (both into the copy),
     * [waiting] true when playback wants to play and waits for more.
     */
    fun check(now: Long, savedMs: Long, positionMs: Long, waiting: Boolean): Verdict {
        saves.addLast(longArrayOf(now, savedMs))
        while (saves.size > 2 && now - saves[1][0] >= rateWindowMs) saves.removeFirst()
        val ahead = savedMs - positionMs
        if (!waiting) playedSinceAsked += periodMs
        if (playedSinceAsked >= helpedPlayMs) helpedSinceAsked = true
        if (waiting && ahead < edgeMs) frozen.addLast(now)
        while (frozen.isNotEmpty() && now - frozen.first() >= windowMs) frozen.removeFirst()
        if (gaveUp) return Verdict.WAIT
        val first = saves.first()
        val span = now - first[0]
        // Too soon to tell how fast it comes in (a copy only just started).
        if (span < rateWindowMs * 2 / 3) return Verdict.WAIT
        val rate = (savedMs - first[1]).toDouble() / span
        if (rate >= slowRate || frozen.size * periodMs < frozenNeededMs) return Verdict.WAIT
        val last = askedAt
        if (last != null && now - last < minGapMs) return Verdict.WAIT
        before = Before(askedAt, helpedSinceAsked, playedSinceAsked, fruitless)
        // Asked before, and it hardly played after that request: asking doesn't help here.
        fruitless = if (last != null && !helpedSinceAsked) fruitless + 1 else 0
        if (fruitless >= maxFruitless) {
            gaveUp = true
            before = null
            return Verdict.GIVE_UP
        }
        askedAt = now
        askedAtRate = rate
        helpedSinceAsked = false
        playedSinceAsked = 0L
        frozen.clear()
        saves.clear()
        return Verdict.ASK_AGAIN
    }
}
