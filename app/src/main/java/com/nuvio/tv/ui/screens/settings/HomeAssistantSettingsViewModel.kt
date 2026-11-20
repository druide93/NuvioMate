package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.homeassistant.Ambiance
import com.nuvio.tv.core.homeassistant.AmbianceEngine
import com.nuvio.tv.core.homeassistant.AmbianceMoment
import com.nuvio.tv.core.homeassistant.AmbianceStatus
import com.nuvio.tv.core.homeassistant.AmbianceStore
import com.nuvio.tv.core.homeassistant.AmbianceTimeMode
import com.nuvio.tv.core.homeassistant.HaApiClient
import com.nuvio.tv.core.homeassistant.HaApiException
import com.nuvio.tv.core.homeassistant.HaApiFailure
import com.nuvio.tv.core.homeassistant.HaConnectionStatus
import com.nuvio.tv.core.homeassistant.HaLight
import com.nuvio.tv.core.homeassistant.HaLightsRepository
import com.nuvio.tv.core.homeassistant.HaMqttBridge
import com.nuvio.tv.core.homeassistant.HaPhoneSetupServer
import com.nuvio.tv.core.homeassistant.HaUser
import com.nuvio.tv.core.homeassistant.HomeAssistantSettings
import com.nuvio.tv.core.homeassistant.HomeAssistantSettingsDataStore
import com.nuvio.tv.core.homeassistant.LightAction
import com.nuvio.tv.core.homeassistant.LightSetup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The lights list as the screen shows it. */
sealed interface HaLightsState {
    data object Loading : HaLightsState
    data class Loaded(val lights: List<HaLight>) : HaLightsState
    data class Failed(val reason: HaApiFailure) : HaLightsState
}

/** Result of "Tester" on the lights connection. */
sealed interface HaApiCheck {
    data object Unknown : HaApiCheck
    data object Checking : HaApiCheck
    data class Ok(val user: HaUser) : HaApiCheck
    data class Failed(val reason: HaApiFailure) : HaApiCheck
}

@HiltViewModel
class HomeAssistantSettingsViewModel @Inject constructor(
    private val store: HomeAssistantSettingsDataStore,
    bridge: HaMqttBridge,
    private val ambianceStore: AmbianceStore,
    private val engine: AmbianceEngine,
    private val lightsRepository: HaLightsRepository,
    private val api: HaApiClient
) : ViewModel() {

    val settings: StateFlow<HomeAssistantSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, HomeAssistantSettings())
    val status: StateFlow<HaConnectionStatus> = bridge.status

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }

    /** "host", "host:port", or "mqtt://host:port". */
    fun setBroker(text: String) {
        val cleaned = text.trim().removePrefix("mqtt://").removePrefix("tcp://").trimEnd('/')
        val host = cleaned.substringBeforeLast(':').takeIf { cleaned.contains(':') } ?: cleaned
        val port = cleaned.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: HomeAssistantSettings.DEFAULT_PORT
        val current = settings.value
        viewModelScope.launch { store.setConnection(host, port, current.username) }
    }

    fun setUsername(value: String) {
        val current = settings.value
        viewModelScope.launch { store.setConnection(current.host, current.port, value) }
    }

    fun setPassword(value: String) {
        viewModelScope.launch { store.setPassword(value) }
    }

    fun setDeviceName(value: String) {
        viewModelScope.launch { store.setDeviceName(value) }
    }

    /** What the phone page sent: an empty password keeps the current one. */
    fun applyFromPhone(values: HaPhoneSetupServer.Values) {
        viewModelScope.launch {
            store.setConnection(values.host, values.port ?: HomeAssistantSettings.DEFAULT_PORT, values.username)
            values.password?.let { store.setPassword(it) }
            if (values.deviceName.isNotBlank()) store.setDeviceName(values.deviceName)
            store.setEnabled(true)
        }
    }

    // ---- Lights (phase 4) ----

    val ambiance: StateFlow<Ambiance> = ambianceStore.cinema.stateIn(viewModelScope, SharingStarted.Eagerly, Ambiance())
    val ambianceStatus: StateFlow<AmbianceStatus> = engine.status

    private val _lights = MutableStateFlow<HaLightsState>(HaLightsState.Loading)
    val lights: StateFlow<HaLightsState> = _lights.asStateFlow()
    private var lightsLoaded = false

    private val _hasLongLivedToken = MutableStateFlow(false)
    val hasLongLivedToken: StateFlow<Boolean> = _hasLongLivedToken.asStateFlow()

    init {
        viewModelScope.launch { _hasLongLivedToken.value = store.longLivedToken() != null }
    }

    private val _apiCheck = MutableStateFlow<HaApiCheck>(HaApiCheck.Unknown)
    val apiCheck: StateFlow<HaApiCheck> = _apiCheck.asStateFlow()

    fun loadLights(force: Boolean = false) {
        if (lightsLoaded && !force) return
        lightsLoaded = true
        _lights.value = HaLightsState.Loading
        viewModelScope.launch {
            _lights.value = try {
                HaLightsState.Loaded(lightsRepository.lights())
            } catch (e: HaApiException) {
                lightsLoaded = false
                HaLightsState.Failed(e.reason)
            }
        }
    }

    fun checkApi() {
        _apiCheck.value = HaApiCheck.Checking
        viewModelScope.launch {
            _apiCheck.value = try {
                HaApiCheck.Ok(api.currentUser())
            } catch (e: HaApiException) {
                HaApiCheck.Failed(e.reason)
            }
        }
    }

    fun setApiUrl(value: String) {
        viewModelScope.launch { store.setApiUrl(value); resetApiState() }
    }

    fun setLongLivedToken(value: String) {
        viewModelScope.launch {
            store.setLongLivedToken(value)
            _hasLongLivedToken.value = store.longLivedToken() != null
            resetApiState()
        }
    }

    private fun resetApiState() {
        _apiCheck.value = HaApiCheck.Unknown
        lightsLoaded = false
    }

    fun toggleLight(light: HaLight) = updateAmbiance { a ->
        if (a.lights.any { it.entityId == light.entityId }) a.copy(lights = a.lights.filter { it.entityId != light.entityId })
        else a.copy(lights = a.lights + LightSetup.defaults(light))
    }

    fun setLightAction(entityId: String, moment: AmbianceMoment, action: LightAction) = updateAmbiance { a ->
        a.copy(lights = a.lights.map { l ->
            if (l.entityId != entityId) l else when (moment) {
                AmbianceMoment.START -> l.copy(start = action)
                AmbianceMoment.PAUSE -> l.copy(pause = action)
                AmbianceMoment.END -> l.copy(end = action)
            }
        })
    }

    fun setAmbianceEnabled(value: Boolean) = updateAmbiance { it.copy(enabled = value) }

    fun toggleKind(kind: String) = updateAmbiance { a ->
        a.copy(kinds = if (kind in a.kinds) a.kinds - kind else a.kinds + kind)
    }

    fun toggleDay(day: Int) = updateAmbiance { a ->
        a.copy(days = if (day in a.days) a.days - day else a.days + day)
    }

    fun setTimeMode(mode: AmbianceTimeMode) = updateAmbiance { it.copy(timeMode = mode) }
    fun setStartMinute(minute: Int) = updateAmbiance { it.copy(startMinute = minute) }
    fun setEndMinute(minute: Int) = updateAmbiance { it.copy(endMinute = minute) }

    fun preview(moment: AmbianceMoment) = engine.preview(ambiance.value, moment)

    private fun updateAmbiance(change: (Ambiance) -> Ambiance) {
        viewModelScope.launch { ambianceStore.updateCinema(change) }
    }
}
