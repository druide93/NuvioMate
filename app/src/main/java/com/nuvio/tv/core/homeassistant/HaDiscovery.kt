package com.nuvio.tv.core.homeassistant

import java.time.Instant

/**
 * MQTT topics and payloads for Home Assistant's MQTT discovery (device-based discovery: one
 * retained config message describes the whole "NuvioMate" device and its entities).
 * Plain Kotlin, no Android, so it can be unit tested.
 */
class HaDiscovery(
    private val deviceId: String,
    private val deviceName: String,
    private val discoveryPrefix: String,
    private val appVersion: String
) {
    private val nodeId = "nuviomate_$deviceId"
    private val base = "nuviomate/$deviceId"

    val availabilityTopic = "$base/availability"
    val stateTopic = "$base/state"
    val imageTopic = "$base/image"
    val discoveryTopic = "${discoveryPrefix.trimEnd('/')}/device/$nodeId/config"

    fun discoveryPayload(): String {
        fun component(platform: String, key: String, name: String, icon: String, extra: Map<String, Any?>): Pair<String, Map<String, Any?>> =
            key to (linkedMapOf<String, Any?>(
                "p" to platform,
                "name" to name,
                "uniq_id" to "${nodeId}_$key",
                // Entity ids like sensor.nuviomate_activite (replaces the old object_id option).
                "default_entity_id" to "$platform.nuviomate_$key",
                "ic" to icon,
                "avty_t" to availabilityTopic
            ) + extra)

        val components = linkedMapOf(
            component(
                "sensor", "activite", "Activité", "mdi:television-play", mapOf(
                    "stat_t" to stateTopic,
                    "val_tpl" to "{{ value_json.activite }}",
                    "dev_cla" to "enum",
                    "ops" to NowPlayingActivity.entries.map { it.haValue }
                )
            ),
            component(
                "sensor", "etat", "État", "mdi:play-pause", mapOf(
                    "stat_t" to stateTopic,
                    "val_tpl" to "{{ value_json.etat }}",
                    "dev_cla" to "enum",
                    "ops" to NowPlayingStatus.entries.map { it.haValue }
                )
            ),
            component(
                "sensor", "en_cours", "En cours", "mdi:movie-open-play", mapOf(
                    "stat_t" to stateTopic,
                    "val_tpl" to "{{ value_json.en_cours }}",
                    "json_attr_t" to stateTopic,
                    "json_attr_tpl" to "{{ value_json.details | tojson }}"
                )
            ),
            component(
                "sensor", "chaine", "Chaîne", "mdi:television-classic", mapOf(
                    "stat_t" to stateTopic,
                    "val_tpl" to "{{ value_json.chaine }}"
                )
            ),
            component(
                "image", "affiche", "Affiche", "mdi:image-frame", mapOf(
                    "url_t" to imageTopic
                )
            )
        )

        return Json.write(
            linkedMapOf(
                "dev" to linkedMapOf(
                    "ids" to listOf(nodeId),
                    "name" to deviceName,
                    "mf" to "NuvioMate",
                    "mdl" to "Android TV",
                    "sw" to appVersion
                ),
                "o" to linkedMapOf(
                    "name" to "NuvioMate",
                    "sw" to appVersion,
                    "url" to "https://github.com/druide93/NuvioMate"
                ),
                "cmps" to components,
                "qos" to 1
            )
        )
    }

    /**
     * The state message every entity reads. Missing values are JSON null, which the templates turn
     * into "None", and Home Assistant shows "None" from MQTT as unknown.
     */
    fun statePayload(now: NowPlaying, profileName: String?): String {
        val details = linkedMapOf<String, Any?>(
            "type" to now.activity.haValue,
            "titre" to now.mediaTitle,
            "chaine" to now.channelName,
            "numero" to now.channelNumber,
            "groupe" to now.channelGroup,
            "emission" to now.programTitle,
            "debut" to now.programStartMs?.let { Instant.ofEpochMilli(it).toString() },
            "fin" to now.programEndMs?.let { Instant.ofEpochMilli(it).toString() },
            "saison" to now.season,
            "episode" to now.episode,
            "titre_episode" to now.episodeTitle,
            "annee" to now.year,
            "image" to now.imageUrl,
            "profil" to profileName
        )
        return Json.write(
            linkedMapOf(
                "activite" to now.activity.haValue,
                "etat" to now.status.haValue,
                "en_cours" to now.summary.ifBlank { null },
                "chaine" to now.channelName,
                "details" to details
            )
        )
    }

    /** What the poster entity shows: the poster or channel logo, else the NuvioMate logo. */
    fun imageUrl(now: NowPlaying): String = now.imageUrl?.takeIf { it.startsWith("http") } ?: FALLBACK_IMAGE

    companion object {
        const val ONLINE = "online"
        const val OFFLINE = "offline"
        const val FALLBACK_IMAGE =
            "https://raw.githubusercontent.com/druide93/NuvioMate/main/app/src/full/res/mipmap-xxxhdpi/iptv_ic_launcher.png"
    }
}

/** Just enough JSON writing for these messages (maps, lists, strings, numbers, booleans, null). */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
