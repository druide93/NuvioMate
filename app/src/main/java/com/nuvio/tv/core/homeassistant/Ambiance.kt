package com.nuvio.tv.core.homeassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a light does at one moment of the ambiance. */
@Serializable
enum class LightActionType { NOTHING, OFF, ON, RESTORE }

@Serializable
data class LightAction(
    val type: LightActionType,
    /** 1–100 %, used with [LightActionType.ON] on lights that dim. */
    val brightness: Int = 100,
    /** A [LightColor] key, or null to keep the light's colour. */
    val color: String? = null
)

/** One chosen light and what it does at the start, on pause and at the end. */
@Serializable
data class LightSetup(
    val entityId: String,
    val name: String,
    val start: LightAction,
    val pause: LightAction,
    val end: LightAction
) {
    fun action(moment: AmbianceMoment): LightAction = when (moment) {
        AmbianceMoment.START -> start
        AmbianceMoment.PAUSE -> pause
        AmbianceMoment.END -> end
    }

    companion object {
        /** Off for the film, a soft warm light on pause, back as it was at the end. */
        fun defaults(light: HaLight): LightSetup {
            val caps = light.capabilities
            val pause = when {
                caps.brightness -> LightAction(
                    LightActionType.ON,
                    brightness = 40,
                    color = if (caps.whites || caps.colors) LightColor.WARM.key else null
                )
                else -> LightAction(LightActionType.NOTHING)
            }
            return LightSetup(
                entityId = light.entityId,
                name = light.name,
                start = LightAction(LightActionType.OFF),
                pause = pause,
                end = LightAction(LightActionType.RESTORE)
            )
        }
    }
}

enum class AmbianceMoment { START, PAUSE, END }

@Serializable
enum class AmbianceTimeMode { ALWAYS, NIGHT, RANGE }

/** An ambiance: when it applies, and what each chosen light does. Only "Cinéma" for now. */
@Serializable
data class Ambiance(
    val id: String = CINEMA,
    val enabled: Boolean = false,
    /** [NowPlayingActivity.haValue]s it applies to. */
    val kinds: Set<String> = setOf(NowPlayingActivity.MOVIE.haValue, NowPlayingActivity.EPISODE.haValue),
    /** ISO days, Monday = 1. */
    val days: Set<Int> = (1..7).toSet(),
    val timeMode: AmbianceTimeMode = AmbianceTimeMode.ALWAYS,
    /** Minutes after midnight; the range may cross midnight (19:00 → 02:00). */
    val startMinute: Int = 19 * 60,
    val endMinute: Int = 2 * 60,
    val lights: List<LightSetup> = emptyList()
) {
    companion object {
        const val CINEMA = "cinema"
    }
}

/** Pure decisions of the ambiance engine, kept apart so they can be unit tested. */
object AmbianceRules {

    /** Wait before acting on a pause, so a short pause doesn't light the room. */
    const val PAUSE_DELAY_MS = 1_500L
    /** Wait before the end, so the next episode or another source doesn't light everything in between. */
    const val END_DELAY_MS = 30_000L
    /** When the app leaves the screen (standby, home button), there's little time left: act almost at once. */
    const val END_OFF_SCREEN_DELAY_MS = 2_000L

    /** Where playback stands for the ambiance; null when it shouldn't change anything (loading). */
    enum class Phase { NONE, PLAYING, PAUSED }

    fun phase(now: NowPlaying, ambiance: Ambiance): Phase? {
        if (!ambiance.enabled || ambiance.lights.isEmpty()) return Phase.NONE
        if (now.activity == NowPlayingActivity.NONE || now.activity.haValue !in ambiance.kinds) return Phase.NONE
        return when (now.status) {
            NowPlayingStatus.PLAYING -> Phase.PLAYING
            NowPlayingStatus.PAUSED -> Phase.PAUSED
            NowPlayingStatus.STOPPED -> Phase.NONE
            NowPlayingStatus.LOADING -> null
        }
    }

    fun delayBefore(phase: Phase, onScreen: Boolean): Long = when (phase) {
        Phase.PLAYING -> 0L
        Phase.PAUSED -> PAUSE_DELAY_MS
        Phase.NONE -> if (onScreen) END_DELAY_MS else END_OFF_SCREEN_DELAY_MS
    }

    /** Day and time conditions; [isNight] is null when HA couldn't say (then night-only doesn't apply). */
    fun timeAllows(ambiance: Ambiance, at: LocalDateTime, isNight: Boolean?): Boolean {
        val minute = at.hour * 60 + at.minute
        // Past midnight inside a range that started the evening before, the day is the evening's.
        val crossesMidnight = ambiance.timeMode == AmbianceTimeMode.RANGE && ambiance.endMinute < ambiance.startMinute
        val day = if (crossesMidnight && minute < ambiance.endMinute) at.dayOfWeek.minus(1) else at.dayOfWeek
        if (day.value !in ambiance.days) return false
        return when (ambiance.timeMode) {
            AmbianceTimeMode.ALWAYS -> true
            AmbianceTimeMode.NIGHT -> isNight == true
            AmbianceTimeMode.RANGE -> inRange(minute, ambiance.startMinute, ambiance.endMinute)
        }
    }

    fun inRange(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** The service call that applies [action], or null when there's nothing to do (NOTHING, RESTORE). */
    fun serviceCall(entityId: String, action: LightAction, caps: LightCapabilities): Pair<String, JsonObject>? = when (action.type) {
        LightActionType.NOTHING, LightActionType.RESTORE -> null
        LightActionType.OFF -> "turn_off" to buildJsonObject { put("entity_id", entityId) }
        LightActionType.ON -> "turn_on" to buildJsonObject {
            put("entity_id", entityId)
            if (caps.brightness) put("brightness_pct", action.brightness.coerceIn(1, 100))
            val color = LightColor.of(action.color)
            when {
                color == null -> Unit
                color.isWhite && caps.whites -> put("color_temp_kelvin", clampKelvin(color.kelvin!!, caps))
                caps.colors -> put("hs_color", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(color.hue)); add(kotlinx.serialization.json.JsonPrimitive(color.saturation)) })
            }
        }
    }

    /** True for a turn_on that sets a colour or a white: those get sent twice (see the engine). */
    fun needsColorResend(service: String, data: JsonObject): Boolean =
        service == "turn_on" && ("hs_color" in data || "color_temp_kelvin" in data)

    /** The service call that puts a light back as [before]. */
    fun restoreCall(before: LightSnapshot): Pair<String, JsonObject> =
        if (!before.on) "turn_off" to buildJsonObject { put("entity_id", before.entityId) }
        else "turn_on" to buildJsonObject {
            put("entity_id", before.entityId)
            before.brightness?.let { put("brightness", it) }
            when {
                before.colorMode == "color_temp" && before.kelvin != null -> put("color_temp_kelvin", before.kelvin)
                before.colorMode in LightCapabilities.COLOR_MODES && before.hue != null && before.saturation != null ->
                    put("hs_color", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(before.hue)); add(kotlinx.serialization.json.JsonPrimitive(before.saturation)) })
            }
        }

    /** What the light should look like once [action] is applied: on/off, and brightness if set. */
    data class Expected(val on: Boolean, val brightnessPct: Int?)

    fun expected(action: LightAction, caps: LightCapabilities, before: LightSnapshot?): Expected? = when (action.type) {
        LightActionType.NOTHING -> null
        LightActionType.OFF -> Expected(on = false, brightnessPct = null)
        LightActionType.ON -> Expected(on = true, brightnessPct = if (caps.brightness) action.brightness.coerceIn(1, 100) else null)
        LightActionType.RESTORE -> before?.let { Expected(it.on, it.brightness?.let(::toPct)) }
    }

    /**
     * True when the light is still as the ambiance left it, so putting it back is safe. If someone
     * switched it or changed its brightness by hand meanwhile, it's left alone.
     */
    fun untouched(expected: Expected?, now: LightSnapshot): Boolean {
        if (expected == null) return true
        if (expected.on != now.on) return false
        val want = expected.brightnessPct ?: return true
        val have = now.brightness?.let(::toPct) ?: return true
        return abs(want - have) <= BRIGHTNESS_TOLERANCE_PCT
    }

    fun toPct(brightness255: Int): Int = (brightness255 * 100 / 255.0).roundToInt()

    private fun clampKelvin(kelvin: Int, caps: LightCapabilities): Int {
        val min = caps.minKelvin ?: return kelvin
        val max = caps.maxKelvin ?: return kelvin
        return kelvin.coerceIn(min, max)
    }

    private const val BRIGHTNESS_TOLERANCE_PCT = 6
}
