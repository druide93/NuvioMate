package com.nuvio.tv.livetv.sports

/**
 * The live games whose score is in front of you while something plays: the one on the channel
 * that's playing (its score on the info bar and the corner card) and the pinned scoreboards.
 * Their league is read straight from ESPN every [EVERY_MS], ESPN's own pace (its answers are
 * kept 9 seconds), instead of every minute or more (pinned games of a league you don't follow
 * waited up to 45 minutes). Only those games' days are asked for, and only games already known
 * are replaced: the league's full round still brings everything else.
 */
internal object WatchedScores {
    /** How often a watched game's league is read. */
    const val EVERY_MS = 10_000L

    /**
     * The games to keep current: [playingCardId] (the game on the channel that's playing) when
     * the info bar shows scores, and [pins] when pinned scoreboards are on. Only while something
     * plays ([playing]), only live games, and nothing when scores are hidden.
     */
    fun games(
        settings: SportsSettings,
        events: List<SportsEvent>,
        playingCardId: String?,
        pins: List<String>,
        playing: Boolean
    ): List<SportsEvent> {
        if (!playing || !settings.showInSidebar || settings.hideScores) return emptyList()
        val ids = HashSet<String>()
        if (settings.scoreOnInfoBar && playingCardId != null) ids += playingCardId
        if (settings.pinnedScores) ids += pins
        if (ids.isEmpty()) return emptyList()
        return events.filter { it.id in ids && it.state == GameState.LIVE }
    }

    /**
     * [old] with each game that's in [fresh] replaced by its new version. Games only in [fresh]
     * are left for the full round. [old] itself (the same list) when nothing changed.
     */
    fun freshen(old: List<SportsEvent>, fresh: List<SportsEvent>): List<SportsEvent> {
        if (fresh.isEmpty() || old.isEmpty()) return old
        val byId = fresh.associateBy { it.id }
        var changed = false
        val out = old.map { ev ->
            val f = byId[ev.id]
            if (f != null && f != ev) { changed = true; f } else ev
        }
        return if (changed) out else old
    }
}
