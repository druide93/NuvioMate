package com.nuvio.tv.core.homeassistant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.homeAssistantDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_assistant_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Connection settings for the Home Assistant MQTT bridge. One per app, not per profile: one TV is one HA device. */
data class HomeAssistantSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val discoveryPrefix: String = DEFAULT_DISCOVERY_PREFIX,
    /** Stable id for this install, used in MQTT topics and HA unique ids. */
    val deviceId: String = "",
    /** Home Assistant's own address for its API (lights). Blank means the broker's host on port 8123. */
    val apiUrl: String = ""
) {
    val isComplete: Boolean get() = host.isNotBlank() && port in 1..65535 && deviceId.isNotBlank()

    /** "http://10.0.0.250:8123", without a trailing slash; blank when nothing is set yet. */
    val apiBase: String get() {
        val typed = apiUrl.trim().trimEnd('/')
        if (typed.isNotEmpty()) return if (typed.contains("://")) typed else "http://$typed"
        return if (host.isBlank()) "" else "http://${host.trim()}:$DEFAULT_API_PORT"
    }

    companion object {
        const val DEFAULT_PORT = 1883
        const val DEFAULT_DEVICE_NAME = "NuvioMate"
        const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
        const val DEFAULT_API_PORT = 8123
    }
}

@Singleton
class HomeAssistantSettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.homeAssistantDataStore
    /** The password lives outside DataStore, encrypted with a key held by the Android Keystore. */
    private val secrets = ProtectedProfilePreferences(context, "home_assistant_secrets", "com.nuviomate.homeassistant.v1")
    /** Bumped on each password change so [settings] emits again. */
    private val passwordVersion = MutableStateFlow(0)

    private val enabledKey = booleanPreferencesKey("enabled")
    private val hostKey = stringPreferencesKey("host")
    private val portKey = intPreferencesKey("port")
    private val usernameKey = stringPreferencesKey("username")
    private val deviceNameKey = stringPreferencesKey("device_name")
    private val prefixKey = stringPreferencesKey("discovery_prefix")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val apiUrlKey = stringPreferencesKey("api_url")

    val settings: Flow<HomeAssistantSettings> = combine(dataStore.data, passwordVersion) { prefs, _ ->
        HomeAssistantSettings(
            enabled = prefs[enabledKey] ?: false,
            host = prefs[hostKey].orEmpty(),
            port = prefs[portKey] ?: HomeAssistantSettings.DEFAULT_PORT,
            username = prefs[usernameKey].orEmpty(),
            password = readPassword(),
            deviceName = prefs[deviceNameKey]?.takeIf { it.isNotBlank() } ?: HomeAssistantSettings.DEFAULT_DEVICE_NAME,
            discoveryPrefix = prefs[prefixKey]?.takeIf { it.isNotBlank() } ?: HomeAssistantSettings.DEFAULT_DISCOVERY_PREFIX,
            deviceId = prefs[deviceIdKey].orEmpty(),
            apiUrl = prefs[apiUrlKey].orEmpty()
        )
    }

    suspend fun current(): HomeAssistantSettings = settings.first()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit {
            it[enabledKey] = value
            if (value && it[deviceIdKey].isNullOrBlank()) it[deviceIdKey] = newDeviceId()
        }
    }

    suspend fun setConnection(host: String, port: Int, username: String) {
        dataStore.edit {
            it[hostKey] = host.trim()
            it[portKey] = port
            it[usernameKey] = username.trim()
            if (it[deviceIdKey].isNullOrBlank()) it[deviceIdKey] = newDeviceId()
        }
    }

    suspend fun setDeviceName(name: String) {
        dataStore.edit { it[deviceNameKey] = name.trim() }
    }

    suspend fun setPassword(password: String?) {
        withContext(Dispatchers.IO) { secrets.write(PASSWORD_SLOT, password?.takeIf { it.isNotEmpty() }) }
        passwordVersion.value++
    }

    suspend fun setApiUrl(url: String) {
        dataStore.edit { it[apiUrlKey] = url.trim() }
    }

    /** The HA refresh token, tied to the address and user it was made for ("<address> <user>"). */
    suspend fun refreshToken(owner: String): String? = withContext(Dispatchers.IO) {
        val stored = runCatching { secrets.read(REFRESH_SLOT) }.getOrNull() ?: return@withContext null
        stored.substringAfter('\n', "").takeIf { stored.substringBefore('\n') == owner && it.isNotBlank() }
    }

    suspend fun setRefreshToken(owner: String, token: String?) = withContext(Dispatchers.IO) {
        secrets.write(REFRESH_SLOT, token?.let { "$owner\n$it" })
    }

    /** A long-lived access token typed by the user, used instead of signing in when set. */
    suspend fun longLivedToken(): String? = withContext(Dispatchers.IO) {
        runCatching { secrets.read(LONG_LIVED_SLOT) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun setLongLivedToken(token: String?) {
        withContext(Dispatchers.IO) { secrets.write(LONG_LIVED_SLOT, token?.trim()?.takeIf { it.isNotEmpty() }) }
        passwordVersion.value++
    }

    private fun readPassword(): String = runCatching { secrets.read(PASSWORD_SLOT) }.getOrNull().orEmpty()

    private fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    private companion object {
        const val PASSWORD_SLOT = 0
        const val REFRESH_SLOT = 1
        const val LONG_LIVED_SLOT = 2
    }
}
