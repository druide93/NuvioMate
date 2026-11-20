package com.nuvio.tv.core.homeassistant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ambianceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_assistant_ambiance",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** The lights as they were before the ambiance, kept on disk so a crash mid-film doesn't lose them. */
@Serializable
data class StoredSnapshot(val takenAtMs: Long, val lights: List<LightSnapshot>)

/** Ambiance settings (a list, ready for more than "Cinéma") and the pending "as before" snapshot. */
@Singleton
class AmbianceStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.ambianceDataStore
    private val ambiancesKey = stringPreferencesKey("ambiances")
    private val snapshotKey = stringPreferencesKey("snapshot")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Only emits when the ambiance itself changes, not when the snapshot stored next to it does. */
    val cinema: Flow<Ambiance> = dataStore.data
        .map { prefs -> decode(prefs[ambiancesKey]).firstOrNull { it.id == Ambiance.CINEMA } ?: Ambiance() }
        .distinctUntilChanged()

    suspend fun updateCinema(change: (Ambiance) -> Ambiance) {
        dataStore.edit { prefs ->
            val all = decode(prefs[ambiancesKey])
            val current = all.firstOrNull { it.id == Ambiance.CINEMA } ?: Ambiance()
            val updated = change(current)
            prefs[ambiancesKey] = json.encodeToString(listOf(updated) + all.filter { it.id != Ambiance.CINEMA })
        }
    }

    suspend fun snapshot(): StoredSnapshot? = dataStore.data.first()[snapshotKey]
        ?.let { runCatching { json.decodeFromString<StoredSnapshot>(it) }.getOrNull() }

    suspend fun setSnapshot(value: StoredSnapshot?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(snapshotKey) else prefs[snapshotKey] = json.encodeToString(value)
        }
    }

    private fun decode(text: String?): List<Ambiance> =
        text?.let { runCatching { json.decodeFromString<List<Ambiance>>(it) }.getOrNull() }.orEmpty()
}
