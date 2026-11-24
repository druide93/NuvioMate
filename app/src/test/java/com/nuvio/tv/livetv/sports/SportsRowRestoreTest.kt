package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a My Teams row's highlight lands when you come back to it ([SportsRows.restoreTarget]). */
class SportsRowRestoreTest {

    @Test
    fun `back to the game you left the row on`() {
        assertEquals("c", SportsRows.restoreTarget(listOf("a", "b", "c", "d"), listOf("b", "c", "d"), lastId = "c", startIndex = 1))
    }

    @Test
    fun `first time on the row - the game on now or next`() {
        assertEquals("b", SportsRows.restoreTarget(listOf("a", "b", "c"), listOf("a", "b", "c"), lastId = null, startIndex = 1))
    }

    @Test
    fun `game you left scrolled off - the game on now or next`() {
        assertEquals("c", SportsRows.restoreTarget(listOf("a", "b", "c", "d", "e"), listOf("c", "d", "e"), lastId = "a", startIndex = 2))
    }

    @Test
    fun `neither on screen - the first game on screen`() {
        assertEquals("d", SportsRows.restoreTarget(listOf("a", "b", "c", "d", "e"), listOf("d", "e"), lastId = "a", startIndex = 1))
    }

    @Test
    fun `row shrank under a stale layout - never past its end`() {
        // The crash of 2026-11-23: the screen still showed the old row (3 games, scrolled to the
        // third), the schedule now has 2. The old code took position 2 and read events[2].
        assertEquals("b", SportsRows.restoreTarget(listOf("a", "b"), listOf("c", "b"), lastId = "c", startIndex = 5))
        assertNull(SportsRows.restoreTarget(listOf("a", "b"), listOf("c", "x"), lastId = "c", startIndex = 2))
    }

    @Test
    fun `game you left has gone from the row`() {
        assertEquals("a", SportsRows.restoreTarget(listOf("a", "b"), listOf("a", "b", "z"), lastId = "z", startIndex = 0))
    }

    @Test
    fun `empty row or nothing laid out yet`() {
        assertNull(SportsRows.restoreTarget(emptyList(), listOf("a"), lastId = "a", startIndex = 0))
        assertNull(SportsRows.restoreTarget(listOf("a"), emptyList(), lastId = "a", startIndex = 0))
    }

    @Test
    fun `keys that are not game ids are skipped`() {
        assertEquals("b", SportsRows.restoreTarget(listOf("a", "b"), listOf(7, "b"), lastId = null, startIndex = -1))
    }
}
