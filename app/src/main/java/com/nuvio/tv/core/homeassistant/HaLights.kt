package com.nuvio.tv.core.homeassistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** What a light can do, from HA's supported_color_modes. */
data class LightCapabilities(
    val modes: Set<String>,
    val minKelvin: Int? = null,
    val maxKelvin: Int? = null
) {
    val brightness: Boolean get() = modes.any { it != "onoff" }
    val whites: Boolean get() = "color_temp" in modes
    val colors: Boolean get() = modes.any { it in COLOR_MODES }

    companion object {
        val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

        fun of(attributes: JsonObject?): LightCapabilities {
            val modes = (attributes?.get("supported_color_modes") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                // Old integrations without color modes: a brightness attribute still means it dims.
                ?: if (attributes?.containsKey("brightness") == true) setOf("brightness") else setOf("onoff")
            return LightCapabilities(
                modes = modes,
                minKelvin = attributes?.get("min_color_temp_kelvin")?.jsonPrimitive?.intOrNull,
                maxKelvin = attributes?.get("max_color_temp_kelvin")?.jsonPrimitive?.intOrNull
            )
        }
    }
}

/** A light as the lights list shows it. */
data class HaLight(
    val entityId: String,
    val name: String,
    val room: String?,
    val capabilities: LightCapabilities,
    val available: Boolean,
    val isOn: Boolean,
    /** Lights inside it, when it's a group (the Plafonnier with its three bulbs). */
    val members: List<String> = emptyList()
)

/**
 * The colours offered in the app: a short ready-made palette is far easier with a remote than a
 * free colour picker. Whites use colour temperature; lights without it get an hs approximation.
 */
enum class LightColor(val key: String, val kelvin: Int?, val hue: Double, val saturation: Double) {
    VERY_WARM("very_warm", 2200, 30.0, 70.0),
    WARM("warm", 2700, 32.0, 50.0),
    NEUTRAL("neutral", 4000, 36.0, 18.0),
    COLD("cold", 6000, 220.0, 12.0),
    AMBER("amber", null, 38.0, 100.0),
    ORANGE("orange", null, 25.0, 100.0),
    RED("red", null, 0.0, 100.0),
    PINK("pink", null, 330.0, 70.0),
    PURPLE("purple", null, 275.0, 90.0),
    BLUE("blue", null, 230.0, 100.0),
    CYAN("cyan", null, 185.0, 100.0),
    GREEN("green", null, 120.0, 90.0);

    val isWhite: Boolean get() = kelvin != null

    companion object {
        fun of(key: String?): LightColor? = entries.firstOrNull { it.key == key }

        /** The colours that make sense for a light, whites first. */
        fun available(caps: LightCapabilities): List<LightColor> = when {
            caps.colors -> entries.toList()
            caps.whites -> entries.filter { it.isWhite }
            else -> emptyList()
        }
    }
}

/** The lights HA knows, with their room, for the lights list. */
@Singleton
class HaLightsRepository @Inject constructor(
    private val api: HaApiClient
) {
    suspend fun lights(): List<HaLight> {
        val states = api.states().filter { it.str("entity_id")?.startsWith("light.") == true }
        // Rooms are a bonus: if HA won't tell a non-admin user, the list is simply alphabetical.
        val rooms = runCatching { api.roomsByEntity() }.getOrDefault(emptyMap())
        return states.map { s ->
            val id = s.str("entity_id").orEmpty()
            val attributes = s["attributes"] as? JsonObject
            val state = s.str("state")
            HaLight(
                entityId = id,
                name = attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: id,
                room = rooms[id],
                capabilities = LightCapabilities.of(attributes),
                available = state != "unavailable" && state != "unknown",
                isOn = state == "on",
                members = (attributes?.get("entity_id") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            )
        }.sortedWith(compareBy({ it.room == null }, { it.room.orEmpty().lowercase() }, { it.name.lowercase() }))
    }
}

/** How a light was before the ambiance touched it, to put it back. */
@kotlinx.serialization.Serializable
data class LightSnapshot(
    val entityId: String,
    val on: Boolean,
    val brightness: Int? = null,
    val colorMode: String? = null,
    val kelvin: Int? = null,
    val hue: Double? = null,
    val saturation: Double? = null
) {
    companion object {
        fun of(entityId: String, state: JsonObject): LightSnapshot {
            val a = state["attributes"] as? JsonObject
            val hs = a?.get("hs_color") as? JsonArray
            return LightSnapshot(
                entityId = entityId,
                on = state.str("state") == "on",
                brightness = a?.get("brightness")?.jsonPrimitive?.intOrNull,
                colorMode = a?.get("color_mode")?.jsonPrimitive?.contentOrNull,
                kelvin = a?.get("color_temp_kelvin")?.jsonPrimitive?.intOrNull,
                hue = hs?.getOrNull(0)?.jsonPrimitive?.doubleOrNull,
                saturation = hs?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            )
        }
    }
}

internal fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
