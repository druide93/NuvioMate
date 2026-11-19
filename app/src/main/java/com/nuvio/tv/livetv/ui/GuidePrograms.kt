package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel

private const val HOUR_BLOCK_MS = 60L * 60L * 1000L
const val NO_GUIDE_TITLE = "No Program Guide Available"

/**
 * TiviMate style: a channel that offers catch-up but has no (or patchy) guide gets hourly
 * "No Program Guide Available" entries, so you can still step back in time and watch from an hour.
 */
internal fun prepareGuidePrograms(ch: LiveChannel, list: List<EpgProgram>, nowMs: Long, pastHours: Int): List<EpgProgram> {
    val cu = ch.catchup ?: return list
    val from = maxOf(nowMs - cu.days.coerceAtMost(14) * 24L * HOUR_BLOCK_MS, nowMs - pastHours * HOUR_BLOCK_MS)
    val to = nowMs - Math.floorMod(nowMs, HOUR_BLOCK_MS) + HOUR_BLOCK_MS
    var t = from - Math.floorMod(from, HOUR_BLOCK_MS)
    if (list.isEmpty()) {
        val out = ArrayList<EpgProgram>()
        while (t < to) { out += EpgProgram(t, t + HOUR_BLOCK_MS, NO_GUIDE_TITLE); t += HOUR_BLOCK_MS }
        return out
    }
    val sorted = list.sortedBy { it.startMs }
    val extra = ArrayList<EpgProgram>()
    var cursor = t
    for (p in sorted) {
        while (cursor + HOUR_BLOCK_MS <= p.startMs && cursor < to) { extra += EpgProgram(cursor, cursor + HOUR_BLOCK_MS, NO_GUIDE_TITLE); cursor += HOUR_BLOCK_MS }
        if (p.stopMs > cursor) cursor = p.stopMs
    }
    while (cursor + HOUR_BLOCK_MS <= to) { extra += EpgProgram(cursor, cursor + HOUR_BLOCK_MS, NO_GUIDE_TITLE); cursor += HOUR_BLOCK_MS }
    return if (extra.isEmpty()) list else (list + extra).sortedBy { it.startMs }
}

/**
 * A channel's listings with no two shows at the same time, for the guide (its tiles and moving
 * through them). Some guides list a show twice a minute or two apart (two sources merged:
 * "French Pop" 18:58–19:51 and 18:59–19:53), or a show running into the next:
 *  - the same show listed twice is one entry, from the earlier start to the later end;
 *  - otherwise a show ends where the next one starts, and one left under a minute long (or
 *    starting at the same time as another) is left out.
 * Listings that don't overlap come back unchanged.
 */
internal fun withoutOverlaps(list: List<EpgProgram>): List<EpgProgram> {
    if (list.size < 2) return list
    val sorted = list.sortedWith(compareBy<EpgProgram> { it.startMs }.thenByDescending { it.stopMs })
    if ((1 until sorted.size).none { sorted[it].startMs < sorted[it - 1].stopMs }) return list
    val out = ArrayList<EpgProgram>(sorted.size)
    for (p in sorted) {
        val prev = out.lastOrNull()
        if (prev != null && p.startMs < prev.stopMs) {
            // The same show, also when only one of the guides marks it new ("X (New)", see XmltvParser):
            // one listing, kept with its NEW mark.
            val pm = titleMarks(p.title)
            val qm = titleMarks(prev.title)
            if (pm.title.trim().equals(qm.title.trim(), ignoreCase = true)) {
                val base = if (pm.isNew && !qm.isNew) prev.copy(title = p.title) else prev
                out[out.lastIndex] = base.copy(stopMs = maxOf(prev.stopMs, p.stopMs))
                continue
            }
            if (p.startMs <= prev.startMs) continue
            if (p.startMs - prev.startMs < 60_000L) out.removeAt(out.lastIndex)
            else out[out.lastIndex] = prev.copy(stopMs = p.startMs)
        }
        out += p
    }
    return out
}
