package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowAtTest {
    private val m = 60_000L
    private fun show(start: Long, stop: Long, title: String) = EpgProgram(start * m, stop * m, title)
    private val news = show(480, 500, "Journal")
    private val weather = show(500, 510, "Météo")
    private val film = show(510, 630, "Film")
    private val guide = listOf(news, weather, film)

    @Test fun `inside the show playing it stays`() {
        assertEquals(film, showAt(film, guide, 600 * m, "TF1"))
    }

    @Test fun `back past its start the show before`() {
        assertEquals(weather, showAt(film, guide, 509 * m, "TF1"))
    }

    @Test fun `and the one before that`() {
        assertEquals(news, showAt(film, guide, 490 * m, "TF1"))
    }

    @Test fun `on past its end the next show`() {
        val late = show(630, 700, "Magazine")
        assertEquals(late, showAt(film, guide + late, 640 * m, "TF1"))
    }

    @Test fun `before the guide's first show nothing`() {
        assertNull(showAt(film, guide, 470 * m, "TF1"))
    }

    @Test fun `a hole in the guide is a show of its own`() {
        val list = listOf(news, film)
        val hole = showAt(film, list, 505 * m, "TF1")!!
        assertEquals(500 * m, hole.startMs)
        assertEquals(510 * m, hole.stopMs)
        assertEquals("TF1", hole.title)
    }

    @Test fun `a listing that runs on into the show playing ends where it starts`() {
        val long = show(490, 520, "Journal")
        val p = showAt(film, listOf(long, film), 495 * m, "TF1")!!
        assertEquals(490 * m, p.startMs)
        assertEquals(510 * m, p.stopMs)
    }
}
