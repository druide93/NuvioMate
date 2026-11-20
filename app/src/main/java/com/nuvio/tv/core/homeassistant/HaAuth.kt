package com.nuvio.tv.core.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Why talking to Home Assistant's API failed, in words the settings screen can show. */
enum class HaApiFailure { NOT_CONFIGURED, UNREACHABLE, BAD_CREDENTIALS, TWO_FACTOR, OTHER }

class HaApiException(val reason: HaApiFailure, detail: String? = null) : Exception(detail ?: reason.name)

/**
 * Signs in to Home Assistant the way its own apps do (login flow, then a refresh token), with the
 * user and password already saved for MQTT, so there's nothing more to type. A long-lived token
 * pasted by the user wins when there is one (two-factor accounts). Tokens are kept encrypted by
 * [HomeAssistantSettingsDataStore]; the short-lived access token only lives in memory.
 */
@Singleton
class HaAuth @Inject constructor(
    private val store: HomeAssistantSettingsDataStore
) {
    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessOwner: String? = null
    private var accessValidUntil = 0L

    /** A token for [settings]'s address, signing in again if needed. Throws [HaApiException]. */
    suspend fun accessToken(settings: HomeAssistantSettings): String = mutex.withLock {
        val base = settings.apiBase
        if (base.isBlank()) throw HaApiException(HaApiFailure.NOT_CONFIGURED)
        store.longLivedToken()?.let { return@withLock it }
        val owner = "$base ${settings.username}"
        if (accessOwner == owner && accessToken != null && System.currentTimeMillis() < accessValidUntil) {
            return@withLock accessToken!!
        }
        val refresh = store.refreshToken(owner)
        if (refresh != null) {
            val renewed = runCatching { refreshAccess(base, refresh) }
            renewed.getOrNull()?.let { (token, expiresIn) ->
                remember(owner, token, expiresIn)
                return@withLock token
            }
            val error = renewed.exceptionOrNull()
            if (error is HaApiException && error.reason != HaApiFailure.BAD_CREDENTIALS) throw error
            store.setRefreshToken(owner, null)
        }
        if (settings.username.isBlank() || settings.password.isEmpty()) throw HaApiException(HaApiFailure.NOT_CONFIGURED)
        val tokens = signIn(base, settings.username, settings.password)
        store.setRefreshToken(owner, tokens.refreshToken)
        remember(owner, tokens.accessToken, tokens.expiresIn)
        tokens.accessToken
    }

    /** Forget the access token (HA said 401), so the next call renews it. */
    fun invalidate() {
        accessToken = null
        accessValidUntil = 0L
    }

    private fun remember(owner: String, token: String, expiresInSeconds: Long) {
        accessOwner = owner
        accessToken = token
        // Renew a minute early so a call never goes out with a token about to expire.
        accessValidUntil = System.currentTimeMillis() + (expiresInSeconds - 60).coerceAtLeast(30) * 1000
    }

    private class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long)

    private suspend fun signIn(base: String, username: String, password: String): Tokens {
        val start = postJson(
            "$base/auth/login_flow",
            buildJsonObject {
                put("client_id", CLIENT_ID)
                put("handler", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("homeassistant")); add(kotlinx.serialization.json.JsonNull) })
                put("redirect_uri", REDIRECT_URI)
            }
        )
        val flowId = start.string("flow_id") ?: throw HaApiException(HaApiFailure.OTHER, "login flow")
        val step = postJson(
            "$base/auth/login_flow/$flowId",
            buildJsonObject {
                put("client_id", CLIENT_ID)
                put("username", username)
                put("password", password)
            }
        )
        when (step.string("type")) {
            "create_entry" -> Unit
            "form" -> throw HaApiException(
                if (step.string("step_id") == "mfa") HaApiFailure.TWO_FACTOR else HaApiFailure.BAD_CREDENTIALS
            )
            "abort" -> throw HaApiException(HaApiFailure.BAD_CREDENTIALS, step.string("reason"))
            else -> throw HaApiException(HaApiFailure.OTHER, step.string("type"))
        }
        val code = step.string("result") ?: throw HaApiException(HaApiFailure.OTHER, "no code")
        val tokens = postForm(
            "$base/auth/token",
            mapOf("grant_type" to "authorization_code", "code" to code, "client_id" to CLIENT_ID)
        )
        return Tokens(
            accessToken = tokens.string("access_token") ?: throw HaApiException(HaApiFailure.OTHER, "no token"),
            refreshToken = tokens.string("refresh_token") ?: throw HaApiException(HaApiFailure.OTHER, "no refresh token"),
            expiresIn = tokens["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800L
        )
    }

    private suspend fun refreshAccess(base: String, refreshToken: String): Pair<String, Long> {
        val tokens = postForm(
            "$base/auth/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to CLIENT_ID)
        )
        val token = tokens.string("access_token") ?: throw HaApiException(HaApiFailure.OTHER, "no token")
        return token to (tokens["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800L)
    }

    private suspend fun postJson(url: String, body: JsonObject): JsonObject =
        send(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build())

    private suspend fun postForm(url: String, fields: Map<String, String>): JsonObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return send(Request.Builder().url(url).post(form).build())
    }

    private suspend fun send(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw HaApiException(HaApiFailure.UNREACHABLE, e.message)
        } catch (e: IllegalArgumentException) {
            throw HaApiException(HaApiFailure.NOT_CONFIGURED, e.message)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                // HA answers 400 "invalid_grant" for a refresh token it no longer knows, 401/403 for a refused sign-in.
                r.code == 400 && text.contains("invalid_grant") -> throw HaApiException(HaApiFailure.BAD_CREDENTIALS)
                r.code == 401 || r.code == 403 -> throw HaApiException(HaApiFailure.BAD_CREDENTIALS)
                !r.isSuccessful -> throw HaApiException(HaApiFailure.OTHER, "HTTP ${r.code}")
            }
            runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw HaApiException(HaApiFailure.OTHER, "bad answer") }
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    companion object {
        /**
         * Home Assistant names apps by a web address. With the redirect on the same host, HA
         * accepts it without fetching anything, and the user sees "github.com/druide93/NuvioMate"
         * next to the token in their HA profile, which says plainly what it is.
         */
        const val CLIENT_ID = "https://github.com/druide93/NuvioMate"
        const val REDIRECT_URI = "https://github.com/druide93/NuvioMate/ha-auth"
        private val JSON = "application/json".toMediaType()
    }
}
