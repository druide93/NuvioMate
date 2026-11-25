package com.nuvio.tv.livetv.sports

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.livetv.ui.LiveTvTexts
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.tr

// ------------------------------------------------------------------ colors

/**
 * A team's color that reads on a dark screen: the alternate color when the main one is near white.
 * Always the same for the same team (see [TeamColors]), wherever it's shown.
 */
internal fun teamColor(t: TeamInfo?): Color {
    val known = t?.let { TeamColors.paint(it) }
    val main = known?.color?.let { Color(it) }
    val alt = known?.altColor?.let { Color(it) }
    val pick = when {
        main == null -> alt
        main.luminance() > 0.62f && alt != null && alt.luminance() < 0.62f -> alt
        else -> main
    }
    return pick ?: Color(0xFF3A4150)
}

internal fun leagueColor(l: League?): Color = Color(l?.color ?: 0xFF2A2F3A)

// ------------------------------------------------------------------ words for times and states

internal fun startLabel(ev: SportsEvent, use24h: Boolean): String = when {
    ev.hasTime -> formatClock(ev.startMs, use24h)
    // Several days (a tournament, a race weekend): when it begins, not "All day".
    ev.endMs != null && ev.endMs - ev.startMs > 20 * 3_600_000L -> "Starts".tr()
    else -> "All day".tr()
}

/** "in 1 h 51", "in 12 min", "Tonight", "Tomorrow". */
internal fun untilLabel(startMs: Long, now: Long, dayEnd: Long): String {
    val m = ((startMs - now) / 60_000L).toInt()
    return when {
        m <= 0 -> "Starting".tr()
        m < 60 -> LiveTvTexts.trf("in {0} min", m)
        m < 6 * 60 -> LiveTvTexts.trf("in {0} h {1}", m / 60, m % 60)
        startMs < dayEnd -> "Later today".tr()
        else -> ""
    }
}

/**
 * What the corner of a card says: the game clock, "Started 1:00", "Final", or the start time.
 * In the device's language (callers put a "● " in front of it, or show it in capitals).
 */
internal fun cornerStatus(ev: SportsEvent, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> if (!hideScores && ev.status != null && ev.source != EventSource.GUIDE && ev.hasScore) ev.status.tr()
    else "Started ".tr() + formatClock(ev.startMs, use24h)
    GameState.POST -> if (ev.source == EventSource.GUIDE) "Ended".tr() else (ev.status ?: "Final").tr()
    GameState.OFF -> (ev.status ?: "Postponed").tr()
    GameState.PRE -> startLabel(ev, use24h)
}

// ------------------------------------------------------------------ words that need their own translation

/**
 * A word that means something else elsewhere in the app ("Home" a team's ground, not the Home
 * page): looked up as "{0}" + [word], so it has a translation of its own. Shown as [word] when
 * there's none.
 */
internal fun trOwn(word: String): String = LiveTvTexts.trf("{0}$word", "")

/** "Home" or "Away" beside a team (not the app's Home page). */
internal fun homeAwayWord(home: Boolean): String = if (home) trOwn("Home") else "Away".tr()

/** A result letter (W, D, L) in the device's language: the first letter of its word (V, N, D in French). */
internal fun resultLetter(r: String): String =
    when (r) { "W" -> "Win"; "D" -> "Draw"; "L" -> "Loss"; else -> null }?.tr()?.take(1)?.uppercase() ?: r

/** A table column's heading: the single letters (P, W, D, L, T) by the word they stand for; the rest as they are. */
internal fun columnHeading(label: String): String =
    when (label) { "P" -> "Played"; "W" -> "Won"; "D" -> "Drawn"; "L" -> "Lost"; "T" -> "Tied"; else -> null }?.tr()?.take(1)?.uppercase() ?: label

/** A game's subtitle with its round or week in the device's language ("Round 12 · Anfield"). */
internal fun shownSubtitle(s: String?): String? = s?.split(" · ")?.joinToString(" · ") { roundWords(it) }

/** A race weekend's session ("Practice 2", "Qualifying", "Race") in the device's language. */
internal fun sessionName(label: String): String =
    if (label.startsWith("Practice ")) LiveTvTexts.trf("Practice {0}", label.removePrefix("Practice ")) else label.tr()

/** "Round 12", "Week 8", "Matchday 3" in the device's language; anything else as it is. */
internal fun roundWords(part: String): String = when {
    part.startsWith("Round ") -> LiveTvTexts.trf("Round {0}", part.removePrefix("Round "))
    part.startsWith("Week ") -> LiveTvTexts.trf("Week {0}", part.removePrefix("Week "))
    part.startsWith("Matchday ") -> LiveTvTexts.trf("Matchday {0}", part.removePrefix("Matchday "))
    else -> part
}

/** Time bar: how far through, from the game clock when known, otherwise from the start time and usual length. */
internal fun progressOf(ev: SportsEvent, now: Long): Float {
    if (ev.state == GameState.POST) return 1f
    if (ev.state != GameState.LIVE) return 0f
    ev.progress?.let { return it }
    val league = SportsCatalog.league(ev.leagueId)
    val end = ev.endMs ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
    return ((now - ev.startMs).toFloat() / (end - ev.startMs).coerceAtLeast(1L)).coerceIn(0.02f, 0.98f)
}

internal fun endEstimate(ev: SportsEvent): Long {
    val league = SportsCatalog.league(ev.leagueId)
    return ev.endMs?.takeIf { ev.hasTime } ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
}

/** A followed team plays, a league you follow, a name you follow, or a reminder you set. */
internal fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
    ev.leagueId in f.leagues || ev.word != null ||
        // A followed event sport (UFC, F1, wrestling…): its events are yours (there's no team to pick).
        (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT) ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
        f.reminders.any { it.eventId == ev.id }
