package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class ShowAfterTest {
    private val m = 60_000L
    private fun show(start: Long, stop: Long, title: String) = EpgProgram(start * m, stop * m, title)
    private val race = show(580, 630, "Formule 1")

    @Test fun `the guide's next show`() {
        val next = show(630, 700, "Podium")
        assertEquals(next, showAfter(race, listOf(race, next), 631 * m))
    }

    @Test fun `the guide's longer listing of the same show`() {
        val longer = show(580, 690, "Formule 1 : Grand Prix")
        assertEquals(longer, showAfter(race, listOf(longer, show(690, 720, "Podium")), 645 * m))
    }

    @Test fun `no show in the guide carries on under the same name`() {
        val next = showAfter(race, listOf(race), 645 * m)
        assertEquals(630 * m, next.startMs)
        assertEquals(680 * m, next.stopMs)
        assertEquals("Formule 1", next.title)
        // And again after that stretch.
        assertEquals(680 * m, showAfter(race, listOf(race), 681 * m).startMs)
    }

    @Test fun `a hole ends at the next listed show`() {
        val later = show(650, 700, "Rugby")
        val next = showAfter(race, listOf(race, later), 640 * m)
        assertEquals(630 * m, next.startMs)
        assertEquals(650 * m, next.stopMs)
    }

    @Test fun `a hole after a later show starts at that show's end`() {
        val list = listOf(race, show(630, 660, "Podium"))
        assertEquals(660 * m, showAfter(race, list, 670 * m).startMs)
    }
}
