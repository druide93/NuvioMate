package com.nuvio.tv.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lower-quality copy of a channel offered when its replay comes in too slowly (names as PureTv writes them). */
class LighterReplayTest {

    @Test
    fun `PureTv quality copies share a backup name`() {
        val fhd = "|FR| BEIN SPORTS 1 FHD"
        assertEquals(backupName(fhd), backupName("|FR| BEIN SPORTS 1 HD"))
        assertEquals(backupName(fhd), backupName("|FR| BEIN SPORTS 1 SD"))
        assertEquals(backupName(fhd), backupName("|FR| BEIN SPORTS 1 FHD+"))
        assertEquals(backupName("|FR| CANAL+ SPORT FHD"), backupName("|FR| CANAL+ SPORT HD"))
        // Not the same channel.
        assert(backupName(fhd) != backupName("|FR| BEIN SPORTS 2 HD"))
        assert(backupName("|FR| CANAL+ SPORT FHD") != backupName("|FR| CANAL+ SPORT 360 HD"))
    }

    @Test
    fun `qualities read from PureTv names`() {
        assertEquals("FHD", backupQuality("|FR| BEIN SPORTS 1 FHD"))
        assertEquals("FHD", backupQuality("|FR| BEIN SPORTS 1 FHD+"))
        assertEquals("HD", backupQuality("|FR| BEIN SPORTS 1 HD"))
        assertEquals("SD", backupQuality("|FR| BEIN SPORTS 1 SD"))
        assertEquals("4K", backupQuality("|FR| CANAL+ EVENEMENT UHD "))
        assertNull(backupQuality("|FR| BEIN SPORTS 1"))
    }

    @Test
    fun `HD is offered before SD for an FHD channel`() {
        val pick = pickLighter("FHD", listOf("sd" to "SD", "plus" to "FHD", "hd" to "HD"))
        assertEquals("hd" to "HD", pick)
    }

    @Test
    fun `SD when it is the only lighter copy`() {
        assertEquals("sd" to "SD", pickLighter("FHD", listOf("plus" to "FHD", "sd" to "SD")))
        assertEquals("sd" to "SD", pickLighter("HD", listOf("sd" to "SD")))
    }

    @Test
    fun `4K goes to FHD first`() {
        assertEquals("fhd" to "FHD", pickLighter("4K", listOf("hd" to "HD", "fhd" to "FHD")))
    }

    @Test
    fun `nothing for a channel with no quality, the lowest, or no lighter copy`() {
        assertNull(pickLighter(null, listOf("hd" to "HD")))
        assertNull(pickLighter("SD", listOf("x" to "SD")))
        assertNull(pickLighter("FHD", listOf("plus" to "FHD", "none" to null)))
        assertNull(pickLighter("FHD", emptyList<Pair<String, String?>>()))
    }
}
