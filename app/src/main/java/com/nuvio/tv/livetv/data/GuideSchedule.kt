package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/**
 * One guide channel, one schedule.
 *
 * Some providers' guide files list a channel several times over: the same schedule twice, a copy
 * of it an hour early, and another source's listings for the same shows, all under one channel
 * id. Stored as they came, two shows were "on now" at once, and each screen picked its own (the
 * guide grid the later one, the player the earlier one).
 *
 * These are the only rules for which show is on at a given time; everything the app shows comes
 * from listings that went through them (when a guide file is stored, and again when listings are
 * read or added to, which costs nothing when they're already clean).
 */
internal object GuideSchedule {
    private const val MINUTE = 60_000L

    /** Two shows may run into each other by this much and still follow one another. */
    private const val RUN_OVER_MS = 5 * MINUTE

    private class Slot(var startMs: Long, var stopMs: Long, var show: EpgProgram, var listed: Int, var first: Int) {
        val length get() = stopMs - startMs
        /** Where the show counts as over for what follows it (see [RUN_OVER_MS]). */
        val softStop get() = stopMs - minOf(RUN_OVER_MS, length / 2)
        /** The show with the slot's times (they differ when copies a few minutes apart were put together). */
        fun timed() = if (show.startMs == startMs && show.stopMs == stopMs) show else show.copy(startMs = startMs, stopMs = stopMs)
    }

    private fun sameTitle(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /** True when two shows are on at the same time for more than one running into the next. */
    fun hasConflicts(list: List<EpgProgram>): Boolean {
        if (list.size < 2) return false
        var sorted = true
        for (i in 1 until list.size) if (list[i].startMs < list[i - 1].startMs) { sorted = false; break }
        val byStart = if (sorted) list else list.sortedBy { it.startMs }
        var softEnd = Long.MIN_VALUE
        var lastStart = Long.MIN_VALUE
        for (p in byStart) {
            val length = p.stopMs - p.startMs
            if (length <= 0) continue
            if (p.startMs == lastStart) return true
            if (softEnd > p.startMs) return true
            val soft = p.stopMs - minOf(RUN_OVER_MS, length / 2)
            if (soft > softEnd) softEnd = soft
            lastStart = p.startMs
        }
        return false
    }

    /**
     * [list] with one schedule left where several were on top of each other. Shows are left out, or
     * cut to fit, and a list with no such conflict comes back as it is (the very same
     * list). The order of [list] matters when two schedules are equally likely: the one listed
     * first wins (a guide file's own listings come before the copies added to it).
     *
     * How the schedule is picked: among all the ways of lining shows up one after another, the
     * one covering the most time, where a show listed several times (the same times, whatever
     * each copy calls it) counts for more than one listed once. In the files seen, the right
     * schedule is the one the file repeats most, and the hour-early copy is listed less often.
     * Shows left out go back in wherever the result has a hole of two minutes or more, cut to
     * the hole: a guide that is merely sloppy keeps all its shows, and no hole is added.
     */
    fun oneSchedule(list: List<EpgProgram>): List<EpgProgram> {
        if (!hasConflicts(list)) return list
        val n = list.size
        val order = (0 until n).filter { list[it].stopMs > list[it].startMs }
            .sortedWith(compareBy<Int> { list[it].startMs }.thenByDescending { list[it].stopMs }.thenBy { it })
        // The same show listed more than once is one slot: the very same times, or the same
        // title a few minutes apart.
        val slots = ArrayList<Slot>()
        val exact = HashMap<Long, HashMap<Long, Slot>>()
        for (i in order) {
            val p = list[i]
            var slot = exact[p.startMs]?.get(p.stopMs)
            if (slot == null) {
                var j = slots.size - 1
                while (j >= 0 && p.startMs - slots[j].startMs <= RUN_OVER_MS) {
                    val s = slots[j]
                    // Mostly at the same time too: two short episodes in a row are two shows.
                    val both = minOf(s.stopMs, p.stopMs) - maxOf(s.startMs, p.startMs)
                    if (kotlin.math.abs(s.stopMs - p.stopMs) <= RUN_OVER_MS && both * 2 >= minOf(s.length, p.stopMs - p.startMs) &&
                        sameTitle(s.show.title, p.title)) { slot = s; break }
                    j--
                }
            }
            if (slot == null) {
                slot = Slot(p.startMs, p.stopMs, p, 0, i)
                slots += slot
                exact.getOrPut(p.startMs) { HashMap() }[p.stopMs] = slot
            } else {
                // The copy listed first is the one kept, unless only a later one has a description.
                if (i < slot.first && (p.description != null || slot.show.description == null)) slot.show = p
                else if (slot.show.description == null && p.description != null && slot.startMs == p.startMs && slot.stopMs == p.stopMs) slot.show = p
                if (i < slot.first) slot.first = i
                // Listed a few minutes apart: from the earlier start to the later end.
                if (p.startMs < slot.startMs) slot.startMs = p.startMs
                if (p.stopMs > slot.stopMs) slot.stopMs = p.stopMs
            }
            slot.listed++
        }
        // The heaviest line-up of slots that follow one another (weighted interval scheduling).
        slots.sortWith(compareBy<Slot> { it.softStop }.thenBy { it.startMs })
        val softStops = LongArray(slots.size) { slots[it].softStop }
        val best = DoubleArray(slots.size + 1)
        val before = IntArray(slots.size)
        val taken = BooleanArray(slots.size)
        for (j in slots.indices) {
            val s = slots[j]
            // How many of the slots before this one are over by the time it starts.
            var lo = 0; var hi = j
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (softStops[mid] <= s.startMs) lo = mid + 1 else hi = mid }
            before[j] = lo
            val weight = s.length.toDouble() * (s.listed * 2.0 * n + (n - s.first))
            if (best[lo] + weight > best[j]) { best[j + 1] = best[lo] + weight; taken[j] = true } else best[j + 1] = best[j]
        }
        val kept = ArrayList<Slot>()
        var j = slots.size - 1
        while (j >= 0) { if (taken[j]) { kept += slots[j]; j = before[j] - 1 } else j-- }
        kept.reverse()
        // A very short show right at the start of the next one passes for "over by then": it
        // isn't before it, so it's left out (and goes back in below if there's a hole for it).
        val inOrder = ArrayList<Slot>(kept.size)
        for (s in kept) {
            while (inOrder.isNotEmpty() && inOrder.last().startMs >= s.startMs) inOrder.removeAt(inOrder.lastIndex)
            inOrder += s
        }
        kept.clear(); kept += inOrder
        val keptSet = kept.toHashSet()
        val out = ArrayList<EpgProgram>(kept.size)
        kept.forEach { out += it.timed() }
        // Shows left out go back in wherever nothing was kept, cut to the hole (two minutes or
        // more): the schedule picked must not have holes the file had a show for. The shows
        // listed most often, then first, get the holes first.
        val inUse = java.util.TreeMap<Long, Long>() // start -> stop of every stretch in use
        kept.forEach { inUse[it.startMs] = maxOf(inUse[it.startMs] ?: Long.MIN_VALUE, it.stopMs) }
        val left = slots.filter { it !in keptSet }.sortedWith(compareByDescending<Slot> { it.listed }.thenBy { it.first })
        for (s in left) {
            // Every stretch of this show that nothing in use covers.
            val free = ArrayList<LongArray>()
            var cursor = s.startMs
            val firstKey = inUse.floorKey(s.startMs) ?: inUse.ceilingKey(s.startMs)
            if (firstKey != null) {
                for ((from, to) in inUse.tailMap(firstKey, true)) {
                    if (from >= s.stopMs) break
                    if (to <= cursor) continue
                    if (from - cursor >= 2 * MINUTE) free += longArrayOf(cursor, from)
                    cursor = maxOf(cursor, to)
                    if (cursor >= s.stopMs) break
                }
            }
            if (s.stopMs - cursor >= 2 * MINUTE) free += longArrayOf(cursor, s.stopMs)
            for ((from, to) in free.map { it[0] to it[1] }) {
                inUse[from] = to
                out += if (from == s.show.startMs && to == s.show.stopMs) s.show else s.show.copy(startMs = from, stopMs = to)
            }
        }
        out.sortBy { it.startMs }
        return out
    }

    /**
     * What [other] has for the times [own] has nothing: its shows, cut to the holes in [own]
     * (two minutes or more), in time order. Before [own]'s first show and after its last count as
     * holes too, so a list that goes further back or ahead adds those days. [own] itself is never
     * touched: a provider's catch-up list used to replace the guide's past, holes and all; now
     * it only fills in where the guide says nothing.
     */
    fun fillers(own: List<EpgProgram>, other: List<EpgProgram>): List<EpgProgram> {
        if (other.isEmpty()) return emptyList()
        val from = tidy(other.sortedBy { it.startMs })
        if (own.isEmpty()) return from
        // The stretches [own] covers, joined up, in time order.
        val spans = ArrayList<LongArray>()
        for (o in own.sortedBy { it.startMs }) {
            if (o.stopMs <= o.startMs) continue
            val last = spans.lastOrNull()
            if (last != null && o.startMs <= last[1]) { if (o.stopMs > last[1]) last[1] = o.stopMs }
            else spans += longArrayOf(o.startMs, o.stopMs)
        }
        val out = ArrayList<EpgProgram>()
        var first = 0
        for (p in from) {
            // Stretches over before this show starts are over for every later show too.
            while (first < spans.size && spans[first][1] <= p.startMs) first++
            var cursor = p.startMs
            var k = first
            while (cursor < p.stopMs) {
                val span = if (k < spans.size && spans[k][0] < p.stopMs) spans[k] else null
                val freeTo = span?.get(0)?.coerceAtLeast(cursor) ?: p.stopMs
                if (freeTo - cursor >= 2 * MINUTE) {
                    out += if (cursor == p.startMs && freeTo == p.stopMs) p else p.copy(startMs = cursor, stopMs = freeTo)
                }
                if (span == null) break
                cursor = maxOf(cursor, span[1])
                k++
            }
        }
        return out
    }

    /**
     * A channel's listings with exactly one show at any time, in time order: [oneSchedule], then
     * a show that runs into the next ends where the next one starts (one left under a minute
     * long is left out). Listings already like that come back as they are (the very same list).
     */
    fun tidy(list: List<EpgProgram>): List<EpgProgram> {
        if (list.size < 2) return list
        var clean = true
        for (i in 1 until list.size) {
            if (list[i].startMs < list[i - 1].stopMs || list[i].startMs < list[i - 1].startMs) { clean = false; break }
        }
        if (clean) return list
        val one = oneSchedule(list)
        val sorted = one.sortedWith(compareBy<EpgProgram> { it.startMs }.thenByDescending { it.stopMs })
        val out = ArrayList<EpgProgram>(sorted.size)
        for (p in sorted) {
            if (p.stopMs <= p.startMs) continue
            val prev = out.lastOrNull()
            if (prev != null && p.startMs < prev.stopMs) {
                // Inside the show before it: cutting that one short here would leave a hole after this one.
                if (p.startMs <= prev.startMs || p.stopMs <= prev.stopMs) continue
                if (p.startMs - prev.startMs < MINUTE) out.removeAt(out.lastIndex)
                else out[out.lastIndex] = prev.copy(stopMs = p.startMs)
            }
            out += p
        }
        return out
    }
}
