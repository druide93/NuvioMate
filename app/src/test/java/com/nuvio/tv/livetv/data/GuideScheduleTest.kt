package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideScheduleTest {
    private val m = 60_000L
    private fun show(fromMin: Int, toMin: Int, title: String, description: String? = null) =
        EpgProgram(fromMin * m, toMin * m, title, description = description)

    private fun onAt(list: List<EpgProgram>, min: Int) = list.filter { min * m >= it.startMs && min * m < it.stopMs }.map { it.title }

    @Test fun `a clean schedule comes back as the very same list`() {
        val list = listOf(show(0, 60, "A"), show(60, 90, "B"), show(120, 180, "C"))
        assertFalse(GuideSchedule.hasConflicts(list))
        assertSame(list, GuideSchedule.oneSchedule(list))
        assertSame(list, GuideSchedule.tidy(list))
    }

    @Test fun `a show running a few minutes into the next is not a conflict`() {
        val list = listOf(show(0, 63, "A"), show(60, 120, "B"))
        assertFalse(GuideSchedule.hasConflicts(list))
        assertSame(list, GuideSchedule.oneSchedule(list))
        assertEquals(listOf(show(0, 60, "A"), show(60, 120, "B")), GuideSchedule.tidy(list))
    }

    @Test fun `the same listings twice are kept once`() {
        val list = listOf(show(0, 60, "A"), show(60, 120, "B"), show(0, 60, "A"), show(60, 120, "B"))
        assertEquals(listOf(show(0, 60, "A"), show(60, 120, "B")), GuideSchedule.tidy(list))
    }

    /** France 2 on a Sunday morning, as one provider's file had it (minutes from 09:00). */
    @Test fun `the schedule listed most often wins over its copy an hour early`() {
        val right = listOf(show(30, 120, "Matinée interreligieuse"), show(120, 175, "Live: La messe"),
            show(175, 180, "Le Jour du Seigneur"), show(180, 237, "Tout le monde veut prendre sa place"))
        val early = listOf(show(60, 115, "Live: La messe"), show(115, 120, "Le Jour du Seigneur"),
            show(120, 177, "Tout le monde veut prendre sa place"), show(177, 179, "C'est bon à savoir"))
        val other = listOf(show(30, 120, "Matinée interreligieuse"), show(120, 175, "Messe"),
            show(175, 180, "Parole inattendue"), show(180, 237, "Tout le monde veut prendre sa place"))
        val out = GuideSchedule.tidy(right + right + early + early + other)
        assertEquals(right, out)
        assertEquals(listOf("Matinée interreligieuse"), onAt(out, 92)) // 10:32, not "La messe"
    }

    @Test fun `equally likely schedules, the one listed first wins`() {
        val right = listOf(show(0, 60, "A"), show(60, 120, "B"), show(120, 180, "C"))
        val early = listOf(show(-60, 0, "A"), show(0, 60, "B"), show(60, 120, "C"))
        assertEquals(right, GuideSchedule.tidy(right + early).filter { it.startMs >= 0 })
        assertEquals(listOf("B"), onAt(GuideSchedule.tidy(right + early), 90))
        assertEquals(listOf("C"), onAt(GuideSchedule.tidy(early + right), 90))
    }

    @Test fun `never two shows at once, whatever the input`() {
        val random = java.util.Random(7)
        repeat(300) {
            val list = List(random.nextInt(40) + 2) {
                val start = random.nextInt(600)
                show(start, start + 1 + random.nextInt(120), "S" + random.nextInt(12))
            }
            val out = GuideSchedule.tidy(list)
            for (i in 1 until out.size) assertTrue(out[i].startMs >= out[i - 1].stopMs)
            out.forEach { assertTrue(it.stopMs > it.startMs) }
            assertTrue(out.isNotEmpty())
        }
    }

    @Test fun `no hole is added where the file had a show`() {
        val random = java.util.Random(11)
        repeat(300) {
            val list = List(random.nextInt(60) + 2) {
                val start = random.nextInt(600)
                show(start, start + 2 + random.nextInt(90), "S" + random.nextInt(8))
            }
            val out = GuideSchedule.tidy(list)
            // Every minute some show covered is still covered, bar holes under two minutes
            // and the minute or two a cut can take off.
            var missing = 0
            var run = 0
            for (min in 0 until 700) {
                val was = list.any { min * m >= it.startMs && (min + 1) * m <= it.stopMs }
                val now = out.any { min * m >= it.startMs && (min + 1) * m <= it.stopMs }
                if (was && !now) { run++; if (run > 2) missing++ } else run = 0
            }
            assertEquals(0, missing)
        }
    }

    @Test fun `two short episodes in a row stay two shows`() {
        val list = listOf(show(0, 3, "Bluey"), show(3, 6, "Bluey"), show(6, 9, "Bluey"), show(0, 30, "Block"), show(0, 30, "Block"))
        val twice = listOf(show(0, 3, "Bluey"), show(3, 6, "Bluey"), show(0, 3, "Bluey"), show(3, 6, "Bluey"), show(0, 10, "Other"))
        assertEquals(listOf(show(0, 30, "Block")), GuideSchedule.tidy(list))
        assertEquals(listOf(show(0, 3, "Bluey"), show(3, 6, "Bluey"), show(6, 10, "Other")), GuideSchedule.tidy(twice))
    }

    @Test fun `the same show listed a few minutes apart runs from the earlier start to the later end`() {
        val list = listOf(show(15, 35, "A"), show(35, 60, "B"), show(10, 35, "A"), show(35, 60, "B"))
        assertEquals(listOf(show(10, 35, "A"), show(35, 60, "B")), GuideSchedule.tidy(list))
    }

    @Test fun `a sloppy guide keeps its shows, cut to fit`() {
        // B starts half an hour before A ends: both stay, B from where A ends.
        val list = listOf(show(0, 90, "A"), show(60, 120, "B"), show(120, 180, "C"))
        assertEquals(listOf(show(0, 90, "A"), show(90, 120, "B"), show(120, 180, "C")), GuideSchedule.tidy(list))
    }

    @Test fun `a short show inside a long one does not stay beside it`() {
        val list = listOf(show(0, 5, "Short"), show(0, 60, "Long"), show(60, 120, "Next"))
        val out = GuideSchedule.tidy(list)
        assertEquals(1, onAt(out, 2).size)
        for (i in 1 until out.size) assertTrue(out[i].startMs >= out[i - 1].stopMs)
    }

    @Test fun `the copy with a description is the one kept`() {
        val list = listOf(show(0, 60, "A"), show(0, 60, "A", "about A"), show(60, 120, "B"))
        assertEquals("about A", GuideSchedule.tidy(list).first().description)
    }
}
