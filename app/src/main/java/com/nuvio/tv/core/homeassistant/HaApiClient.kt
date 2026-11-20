package com.nuvio.tv.core.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Who NuvioMate is signed in as, to warn when it's an administrator. */
data class HaUser(val name: String, val isAdmin: Boolean)

/**
 * Home Assistant's API for the lights: REST for states and service calls (what a non-admin user
 * may do), and a short WebSocket session only to read the room of each light.
 */
@Singleton
class HaApiClient @Inject constructor(
    private val store: HomeAssistantSettingsDataStore,
    private val auth: HaAuth
) {
    /** Every state HA knows, as raw JSON objects. */
    suspend fun states(): List<JsonObject> = get("/api/states").jsonArray.map { it.jsonObject }

    suspend fun state(entityId: String): JsonObject? = runCatching { get("/api/states/$entityId").jsonObject }
        .getOrElse { if (it is HaApiException && it.message == "HTTP 404") null else throw it }

    suspend fun callService(domain: String, service: String, data: JsonObject) {
        post("/api/services/$domain/$service", data)
    }

    /** Signs in (if needed) and says who we are. */
    suspend fun currentUser(): HaUser {
        val result = webSocket(listOf(buildJsonObject { put("type", "auth/current_user") })).single()
            ?.jsonObject ?: throw HaApiException(HaApiFailure.OTHER, "current_user")
        return HaUser(
            name = result["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            isAdmin = result["is_admin"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    /** entity_id → room name, from the entity's own room or else its device's. Empty if HA won't say. */
    suspend fun roomsByEntity(): Map<String, String> {
        val (entities, devices, areas) = webSocket(
            listOf(
                buildJsonObject { put("type", "config/entity_registry/list_for_display") },
                buildJsonObject { put("type", "config/device_registry/list") },
                buildJsonObject { put("type", "config/area_registry/list") }
            )
        )
        val areaNames = (areas as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a.jsonObject
            val id = o.str("area_id") ?: return@mapNotNull null
            id to (o.str("name") ?: id)
        }.toMap()
        val deviceArea = (devices as? JsonArray).orEmpty().mapNotNull { d ->
            val o = d.jsonObject
            val id = o.str("id") ?: return@mapNotNull null
            o.str("area_id")?.let { id to it }
        }.toMap()
        // list_for_display uses short keys: ei = entity_id, ai = area_id, di = device_id.
        val list = (entities as? JsonObject)?.get("entities") as? JsonArray ?: return emptyMap()
        return list.mapNotNull { e ->
            val o = e.jsonObject
            val id = o.str("ei") ?: return@mapNotNull null
            val area = o.str("ai") ?: o.str("di")?.let { deviceArea[it] } ?: return@mapNotNull null
            areaNames[area]?.let { id to it }
        }.toMap()
    }

    private suspend fun settings(): HomeAssistantSettings = store.current()

    private suspend fun get(path: String): JsonElement = withAuth { base, token ->
        Request.Builder().url(base + path).header("Authorization", "Bearer $token").get().build()
    }

    private suspend fun post(path: String, body: JsonObject): JsonElement = withAuth { base, token ->
        Request.Builder().url(base + path).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON)).build()
    }

    /** Sends the request, and on 401 renews the token once and tries again. */
    private suspend fun withAuth(build: (String, String) -> Request): JsonElement {
        val s = settings()
        repeat(2) { attempt ->
            val token = auth.accessToken(s)
            val (code, text) = execute(build(s.apiBase, token))
            when {
                code == 401 && attempt == 0 -> auth.invalidate()
                code == 401 || code == 403 -> throw HaApiException(HaApiFailure.BAD_CREDENTIALS)
                code !in 200..299 -> throw HaApiException(HaApiFailure.OTHER, "HTTP $code")
                else -> return withContext(Dispatchers.Default) {
                    runCatching { Json.parseToJsonElement(text.ifBlank { "null" }) }
                        .getOrElse { throw HaApiException(HaApiFailure.OTHER, "bad answer") }
                }
            }
        }
        throw HaApiException(HaApiFailure.BAD_CREDENTIALS)
    }

    /**
     * Sends [request] and reads the whole answer, both off the main thread: reading the body is
     * network work too. Returns the HTTP code and the body text.
     */
    private suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            auth.http.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw HaApiException(HaApiFailure.UNREACHABLE, e.message)
        } catch (e: IllegalArgumentException) {
            throw HaApiException(HaApiFailure.NOT_CONFIGURED, e.message)
        }
    }

    /**
     * Opens a WebSocket, signs in, sends [commands] (ids 1, 2, …) and returns their results in
     * the same order (null for a command HA refused), then closes.
     */
    private suspend fun webSocket(commands: List<JsonObject>): List<JsonElement?> {
        val s = settings()
        val token = auth.accessToken(s)
        val url = s.apiBase.replaceFirst(Regex("^http"), "ws") + "/api/websocket"
        val messages = Channel<String>(Channel.UNLIMITED)
        val failure = Channel<Throwable>(1)
        val socket = auth.http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { messages.trySend(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.trySend(t) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failure.trySend(IOException("closed $code")) }
            }
        )
        try {
            return withTimeout(12_000) {
                suspend fun next(): JsonObject {
                    while (true) {
                        val text = kotlinx.coroutines.selects.select {
                            messages.onReceive { it }
                            failure.onReceive { throw HaApiException(HaApiFailure.UNREACHABLE, it.message) }
                        }
                        // HA may batch several messages in one JSON array.
                        val element = Json.parseToJsonElement(text)
                        if (element is JsonArray) {
                            element.drop(1).forEach { messages.trySend(it.toString()) }
                            return element.first().jsonObject
                        }
                        return element.jsonObject
                    }
                }
                if (next().str("type") != "auth_required") throw HaApiException(HaApiFailure.OTHER, "websocket")
                socket.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString())
                when (next().str("type")) {
                    "auth_ok" -> Unit
                    "auth_invalid" -> { auth.invalidate(); throw HaApiException(HaApiFailure.BAD_CREDENTIALS) }
                    else -> throw HaApiException(HaApiFailure.OTHER, "websocket auth")
                }
                commands.forEachIndexed { i, c ->
                    socket.send(JsonObject(c + ("id" to kotlinx.serialization.json.JsonPrimitive(i + 1))).toString())
                }
                val results = arrayOfNulls<JsonElement>(commands.size)
                var answered = 0
                while (answered < commands.size) {
                    val msg = next()
                    if (msg.str("type") != "result") continue
                    val id = msg["id"]?.jsonPrimitive?.intOrNull ?: continue
                    if (id !in 1..commands.size) continue
                    if (msg["success"]?.jsonPrimitive?.booleanOrNull == true) results[id - 1] = msg["result"]
                    answered++
                }
                results.toList()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw HaApiException(HaApiFailure.UNREACHABLE, "timeout")
        } finally {
            socket.close(1000, null)
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
