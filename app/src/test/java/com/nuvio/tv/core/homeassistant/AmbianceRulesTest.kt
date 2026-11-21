package com.nuvio.tv.core.homeassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AmbianceRulesTest {

    private val lamp = LightSetup(
        entityId = "light.lampe_salon",
        name = "Lampe Salon",
        start = LightAction(LightActionType.OFF),
        pause = LightAction(LightActionType.ON, 40, LightColor.WARM.key),
        end = LightAction(LightActionType.RESTORE)
    )
    private val cinema = Ambiance(enabled = true, lights = listOf(lamp))
    private val film = NowPlaying(NowPlayingActivity.MOVIE, NowPlayingStatus.PLAYING, mediaTitle = "Dune")
    private val colorCaps = LightCapabilities(setOf("color_temp", "hs"), minKelvin = 2700, maxKelvin = 6500)
    private val ctCaps = LightCapabilities(setOf("color_temp"), minKelvin = 2700, maxKelvin = 6500)
    private val onOffCaps = LightCapabilities(setOf("onoff"))

    @Test
    fun `phase follows the film, and loading changes nothing`() {
        assertEquals(AmbianceRules.Phase.PLAYING, AmbianceRules.phase(film, cinema))
        assertEquals(AmbianceRules.Phase.PAUSED, AmbianceRules.phase(film.copy(status = NowPlayingStatus.PAUSED), cinema))
        assertNull(AmbianceRules.phase(film.copy(status = NowPlayingStatus.LOADING), cinema))
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(NowPlaying.NOTHING, cinema))
    }

    @Test
    fun `content not chosen, ambiance off or no light means none`() {
        val live = film.copy(activity = NowPlayingActivity.LIVE)
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(live, cinema))
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(film, cinema.copy(enabled = false)))
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(film, cinema.copy(lights = emptyList())))
    }

    @Test
    fun `the Cinema button forces it on live, even switched off, and forces it off on a film`() {
        val live = film.copy(activity = NowPlayingActivity.LIVE)
        val off = cinema.copy(enabled = false)
        assertEquals(AmbianceRules.Phase.PLAYING, AmbianceRules.phase(live, off, AmbianceOverride.FORCED_ON))
        assertEquals(AmbianceRules.Phase.PAUSED, AmbianceRules.phase(live.copy(status = NowPlayingStatus.PAUSED), off, AmbianceOverride.FORCED_ON))
        assertNull(AmbianceRules.phase(live.copy(status = NowPlayingStatus.LOADING), off, AmbianceOverride.FORCED_ON))
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(film, cinema, AmbianceOverride.FORCED_OFF))
        // Nothing playing, or no light chosen: nothing to force.
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(NowPlaying.NOTHING, cinema, AmbianceOverride.FORCED_ON))
        assertEquals(AmbianceRules.Phase.NONE, AmbianceRules.phase(live, cinema.copy(lights = emptyList()), AmbianceOverride.FORCED_ON))
    }

    @Test
    fun `switched off with the button, the lights come back at once`() {
        assertEquals(0L, AmbianceRules.delayBefore(AmbianceRules.Phase.NONE, true, switchedOff = true))
        assertEquals(1_500L, AmbianceRules.delayBefore(AmbianceRules.Phase.PAUSED, true, switchedOff = true))
    }

    @Test
    fun `pause waits, end waits longer, but not once the app left the screen`() {
        assertEquals(0L, AmbianceRules.delayBefore(AmbianceRules.Phase.PLAYING, true))
        assertEquals(1_500L, AmbianceRules.delayBefore(AmbianceRules.Phase.PAUSED, true))
        assertEquals(30_000L, AmbianceRules.delayBefore(AmbianceRules.Phase.NONE, true))
        assertEquals(2_000L, AmbianceRules.delayBefore(AmbianceRules.Phase.NONE, false))
    }

    @Test
    fun `days and time range, including past midnight`() {
        val evening = cinema.copy(timeMode = AmbianceTimeMode.RANGE, startMinute = 19 * 60, endMinute = 2 * 60, days = setOf(5)) // Fridays
        val friday2130 = LocalDateTime.of(2026, 11, 20, 21, 30) // a Friday
        val saturday0100 = LocalDateTime.of(2026, 11, 21, 1, 0) // still Friday evening
        val saturday2130 = LocalDateTime.of(2026, 11, 21, 21, 30)
        val friday1500 = LocalDateTime.of(2026, 11, 20, 15, 0)
        assertTrue(AmbianceRules.timeAllows(evening, friday2130, null))
        assertTrue(AmbianceRules.timeAllows(evening, saturday0100, null))
        assertFalse(AmbianceRules.timeAllows(evening, saturday2130, null))
        assertFalse(AmbianceRules.timeAllows(evening, friday1500, null))
    }

    @Test
    fun `night only needs HA to say it is night`() {
        val night = cinema.copy(timeMode = AmbianceTimeMode.NIGHT)
        val at = LocalDateTime.of(2026, 11, 20, 21, 0)
        assertTrue(AmbianceRules.timeAllows(night, at, true))
        assertFalse(AmbianceRules.timeAllows(night, at, false))
        assertFalse(AmbianceRules.timeAllows(night, at, null))
    }

    @Test
    fun `turn on uses what the light can do`() {
        val on = LightAction(LightActionType.ON, 40, LightColor.WARM.key)
        val (service, data) = AmbianceRules.serviceCall("light.a", on, ctCaps)!!
        assertEquals("turn_on", service)
        assertEquals("""{"entity_id":"light.a","brightness_pct":40,"color_temp_kelvin":2700}""", data.toString())

        val blue = AmbianceRules.serviceCall("light.b", on.copy(color = LightColor.BLUE.key), colorCaps)!!.second
        assertEquals("""{"entity_id":"light.b","brightness_pct":40,"hs_color":[230.0,100.0]}""", blue.toString())

        // A white-only light can't do blue: brightness only.
        val noBlue = AmbianceRules.serviceCall("light.c", on.copy(color = LightColor.BLUE.key), ctCaps)!!.second
        assertEquals("""{"entity_id":"light.c","brightness_pct":40}""", noBlue.toString())

        // A plug: just on.
        val plug = AmbianceRules.serviceCall("light.d", on, onOffCaps)!!.second
        assertEquals("""{"entity_id":"light.d"}""", plug.toString())

        assertEquals("turn_off", AmbianceRules.serviceCall("light.a", LightAction(LightActionType.OFF), ctCaps)!!.first)
        assertNull(AmbianceRules.serviceCall("light.a", LightAction(LightActionType.NOTHING), ctCaps))
        assertNull(AmbianceRules.serviceCall("light.a", LightAction(LightActionType.RESTORE), ctCaps))
    }

    @Test
    fun `colours and whites are sent twice, plain on or off once`() {
        val red = AmbianceRules.serviceCall("light.a", LightAction(LightActionType.ON, 10, LightColor.RED.key), colorCaps)!!
        val dim = AmbianceRules.serviceCall("light.a", LightAction(LightActionType.ON, 10), colorCaps)!!
        val off = AmbianceRules.serviceCall("light.a", LightAction(LightActionType.OFF), colorCaps)!!
        assertTrue(AmbianceRules.needsColorResend(red.first, red.second))
        assertFalse(AmbianceRules.needsColorResend(dim.first, dim.second))
        assertFalse(AmbianceRules.needsColorResend(off.first, off.second))
    }

    @Test
    fun `snapshot is read from HA and put back as it was`() {
        val state = Json.parseToJsonElement(
            """{"entity_id":"light.p","state":"on","attributes":{"brightness":128,"color_mode":"color_temp",
               "color_temp_kelvin":3000,"hs_color":[30.0,40.0],"supported_color_modes":["color_temp"]}}"""
        ).jsonObject
        val snap = LightSnapshot.of("light.p", state)
        assertEquals(LightSnapshot("light.p", true, 128, "color_temp", 3000, 30.0, 40.0), snap)
        val (service, data) = AmbianceRules.restoreCall(snap)
        assertEquals("turn_on", service)
        assertEquals("""{"entity_id":"light.p","brightness":128,"color_temp_kelvin":3000}""", data.toString())

        val wasOff = AmbianceRules.restoreCall(snap.copy(on = false))
        assertEquals("turn_off" to """{"entity_id":"light.p"}""", wasOff.first to wasOff.second.toString())
    }

    @Test
    fun `a light changed by hand is left alone`() {
        val dimmed = AmbianceRules.expected(LightAction(LightActionType.ON, 40), ctCaps, null)
        val off = AmbianceRules.expected(LightAction(LightActionType.OFF), ctCaps, null)
        fun now(on: Boolean, brightness: Int?) = LightSnapshot("light.p", on, brightness)
        assertTrue(AmbianceRules.untouched(off, now(false, null)))
        assertFalse(AmbianceRules.untouched(off, now(true, 255)))          // someone switched it on
        assertTrue(AmbianceRules.untouched(dimmed, now(true, 102)))        // 40 %
        assertFalse(AmbianceRules.untouched(dimmed, now(true, 255)))       // turned up by hand
        assertTrue(AmbianceRules.untouched(null, now(true, 255)))          // never touched
    }

    @Test
    fun `defaults depend on what the light can do`() {
        val dimmable = HaLight("light.l", "Lampe", "Salon", ctCaps, available = true, isOn = false)
        val plug = HaLight("light.p", "Prise", null, onOffCaps, available = true, isOn = false)
        assertEquals(LightAction(LightActionType.ON, 40, "warm"), LightSetup.defaults(dimmable).pause)
        assertEquals(LightAction(LightActionType.NOTHING), LightSetup.defaults(plug).pause)
        assertEquals(LightActionType.OFF, LightSetup.defaults(plug).start.type)
        assertEquals(LightActionType.RESTORE, LightSetup.defaults(plug).end.type)
    }

    @Test
    fun `capabilities from HA attributes`() {
        fun caps(json: String) = LightCapabilities.of(Json.parseToJsonElement(json).jsonObject as JsonObject)
        assertTrue(caps("""{"supported_color_modes":["color_temp","hs"]}""").colors)
        assertFalse(caps("""{"supported_color_modes":["color_temp"]}""").colors)
        assertFalse(caps("""{"supported_color_modes":["onoff"]}""").brightness)
        assertTrue(caps("""{"brightness":10}""").brightness)
        assertEquals(LightColor.entries.filter { it.isWhite }, LightColor.available(caps("""{"supported_color_modes":["color_temp"]}""")))
    }
}
