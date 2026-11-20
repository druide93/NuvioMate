package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.homeassistant.HaConnectionStatus
import com.nuvio.tv.core.homeassistant.HaMqttBridge
import com.nuvio.tv.core.homeassistant.HaPhoneSetupServer
import com.nuvio.tv.core.homeassistant.HomeAssistantSettings
import com.nuvio.tv.core.homeassistant.HomeAssistantSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeAssistantSettingsViewModel @Inject constructor(
    private val store: HomeAssistantSettingsDataStore,
    bridge: HaMqttBridge
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
}
