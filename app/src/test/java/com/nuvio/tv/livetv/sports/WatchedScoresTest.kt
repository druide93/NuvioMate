package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which games are read every 10 s, and how their new scores go in ([WatchedScores]). */
class WatchedScoresTest {
    private val now = 1_800_000_000_000L

    private fun event(id: String, state: GameState = GameState.LIVE, away: String? = "10", home: String? = "12"): SportsEvent {
        fun side(team: String, score: String?, isHome: Boolean) = Side(
            TeamInfo("basketball/nba", team, team, team, team, null, null, null, null),
            score, null, false, isHome
        )
        return SportsEvent(id, "basketball/nba", now, true, state, id, null, null,
            side("a$id", away, false), side("h$id", home, true))
    }

    private val all = listOf(event("on"), event("pin1"), event("pin2"), event("next", GameState.PRE), event("done", GameState.POST))

    private fun ids(list: List<SportsEvent>) = list.map { it.id }.toSet()

    @Test
    fun `the game on the playing channel and the pinned ones`() {
        val got = WatchedScores.games(SportsSettings(), all, "on", listOf("pin1", "pin2"), playing = true)
        assertEquals(setOf("on", "pin1", "pin2"), ids(got))
    }

    @Test
    fun `only live games`() {
        val got = WatchedScores.games(SportsSettings(), all, "next", listOf("done", "pin1"), playing = true)
        assertEquals(setOf("pin1"), ids(got))
    }

    @Test
    fun `nothing while nothing plays`() {
        assertTrue(WatchedScores.games(SportsSettings(), all, "on", listOf("pin1"), playing = false).isEmpty())
    }

    @Test
    fun `nothing with scores hidden or Sports off`() {
        assertTrue(WatchedScores.games(SportsSettings(hideScores = true), all, "on", listOf("pin1"), playing = true).isEmpty())
        assertTrue(WatchedScores.games(SportsSettings(showInSidebar = false), all, "on", listOf("pin1"), playing = true).isEmpty())
    }

    @Test
    fun `each part follows its own setting`() {
        assertEquals(setOf("pin1"), ids(WatchedScores.games(SportsSettings(scoreOnInfoBar = false), all, "on", listOf("pin1"), playing = true)))
        assertEquals(setOf("on"), ids(WatchedScores.games(SportsSettings(pinnedScores = false), all, "on", listOf("pin1"), playing = true)))
    }

    @Test
    fun `no channel game and no pins - nothing`() {
        assertTrue(WatchedScores.games(SportsSettings(), all, null, emptyList(), playing = true).isEmpty())
    }

    @Test
    fun `new scores replace their game, the rest stays`() {
        val old = listOf(event("on"), event("other"), event("done", GameState.POST))
        val newer = event("on", away = "14")
        val got = WatchedScores.freshen(old, listOf(newer))
        assertEquals(listOf("on", "other", "done"), got.map { it.id })
        assertSame(newer, got[0])
        assertSame(old[1], got[1])
        assertSame(old[2], got[2])
    }

    @Test
    fun `games only in the quick answer wait for the full round`() {
        val old = listOf(event("on"))
        val got = WatchedScores.freshen(old, listOf(event("on", away = "14"), event("unknown")))
        assertEquals(listOf("on"), got.map { it.id })
        assertEquals("14", got[0].away?.score)
    }

    @Test
    fun `nothing new - the very same list`() {
        val old = listOf(event("on"), event("other"))
        assertSame(old, WatchedScores.freshen(old, listOf(event("on"), event("other"))))
        assertSame(old, WatchedScores.freshen(old, emptyList()))
    }
}
