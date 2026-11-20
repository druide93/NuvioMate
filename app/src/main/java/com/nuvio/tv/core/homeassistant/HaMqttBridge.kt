package com.nuvio.tv.core.homeassistant

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.inject.Inject
import javax.inject.Singleton

/** Where the link with Home Assistant stands, for the settings screen. */
sealed interface HaConnectionStatus {
    data object Off : HaConnectionStatus
    /** Switched on, but the broker address is missing. */
    data object Incomplete : HaConnectionStatus
    data object Connecting : HaConnectionStatus
    data object Connected : HaConnectionStatus
    data class Failed(val reason: HaFailure, val detail: String?) : HaConnectionStatus
}

enum class HaFailure { BAD_CREDENTIALS, UNREACHABLE, TIMEOUT, OTHER }

/**
 * Keeps NuvioMate connected to Home Assistant's MQTT broker while the option is on: announces the
 * device through MQTT discovery, keeps an "online/offline" availability topic (the broker sends
 * "offline" by itself when the app dies), and publishes what's playing.
 * Nothing runs, and the live TV / player state isn't even watched, while the option is off.
 */
@OptIn(FlowPreview::class)
@Singleton
class HaMqttBridge @Inject constructor(
    private val settingsStore: HomeAssistantSettingsDataStore,
    private val hub: dagger.Lazy<NowPlayingHub>,
    private val profileManager: ProfileManager,
    private val live: dagger.Lazy<com.nuvio.tv.livetv.player.LiveTvPlaybackController>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<HaConnectionStatus>(HaConnectionStatus.Off)
    val status: StateFlow<HaConnectionStatus> = _status.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settingsStore.settings.distinctUntilChanged().collectLatest { settings ->
                runCatching { run(settings) }.onFailure { Log.w(TAG, "Home Assistant bridge stopped", it) }
            }
        }
    }

    private suspend fun run(settings: HomeAssistantSettings) {
        if (!settings.enabled) {
            _status.value = HaConnectionStatus.Off
            return
        }
        if (!settings.isComplete) {
            _status.value = HaConnectionStatus.Incomplete
            return
        }
        val discovery = HaDiscovery(settings.deviceId, settings.deviceName, settings.discoveryPrefix, BuildConfig.VERSION_NAME)
        val player = HaMediaPlayer(settings.deviceId, settings.deviceName, settings.discoveryPrefix)
        val client = MqttAsyncClient("tcp://${settings.host}:${settings.port}", "nuviomate-${settings.deviceId}", MemoryPersistence())
        val connected = MutableStateFlow(false)
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // Discovery first, so the entities exist before their first state arrives.
                publish(client, discovery.discoveryTopic, discovery.discoveryPayload())
                // The media player card, through the "MQTT Media Player" integration (ignored without it).
                publish(client, player.discoveryTopic, player.discoveryPayload())
                runCatching { client.subscribe(player.commandFilter, 1) }
                    .onFailure { Log.w(TAG, "MQTT subscribe failed: ${it.message}") }
                publish(client, discovery.availabilityTopic, HaDiscovery.ONLINE)
                _status.value = HaConnectionStatus.Connected
                connected.value = true
            }

            override fun connectionLost(cause: Throwable?) {
                connected.value = false
                _status.value = HaConnectionStatus.Connecting
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val command = topic?.let(HaCommand::ofTopic) ?: return
                Log.i(TAG, "Command from Home Assistant: $command")
                scope.launch(Dispatchers.Main) { runCatching { handleCommand(command) } }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = KEEP_ALIVE_S
            connectionTimeout = CONNECT_TIMEOUT_S
            maxReconnectDelay = MAX_RETRY_MS.toInt()
            if (settings.username.isNotBlank()) userName = settings.username
            if (settings.password.isNotEmpty()) password = settings.password.toCharArray()
            setWill(discovery.availabilityTopic, HaDiscovery.OFFLINE.toByteArray(), 1, true)
        }
        try {
            // Automatic reconnection only starts after a first success, so retry the first one here.
            var wait = 5_000L
            while (true) {
                _status.value = HaConnectionStatus.Connecting
                try {
                    client.connect(options).waitForCompletion((CONNECT_TIMEOUT_S + 5) * 1_000L)
                    break
                } catch (e: MqttException) {
                    _status.value = failureOf(e)
                    Log.w(TAG, "MQTT connect to ${settings.host}:${settings.port} failed: ${e.reasonCode}")
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
            coroutineScope {
                // The media player's picture: Home Assistant wants the image itself (base64), so
                // it's fetched here, once per poster or logo.
                launch {
                    combine(hub.get().nowPlaying, connected) { now, up -> discovery.imageUrl(now) to up }
                        .filter { it.second }
                        .distinctUntilChanged()
                        .collectLatest { (url, _) ->
                            albumArt(url)?.let { publish(client, player.albumArtTopic, it) }
                        }
                }
                combine(hub.get().nowPlaying, profileManager.activeProfileId, profileManager.profiles, connected) { now, id, profiles, up ->
                    Snapshot(now, profiles.firstOrNull { it.id == id }?.name, up)
                }
                    .filter { it.connected }
                    .debounce(PUBLISH_DEBOUNCE_MS)
                    .collect { snapshot ->
                        publish(client, discovery.stateTopic, discovery.statePayload(snapshot.now, snapshot.profileName))
                        publish(client, discovery.imageTopic, discovery.imageUrl(snapshot.now))
                        player.statePayloads(snapshot.now).forEach { (topic, payload) -> publish(client, topic, payload) }
                    }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    if (client.isConnected) {
                        client.publish(discovery.availabilityTopic, HaDiscovery.OFFLINE.toByteArray(), 1, true).waitForCompletion(2_000)
                        client.disconnect().waitForCompletion(2_000)
                    }
                }
                runCatching { client.close(true) }
            }
        }
    }

    /** Runs a command from Home Assistant's media player on whatever is playing (main thread). */
    private fun handleCommand(command: HaCommand) {
        val now = hub.get().nowPlaying.value
        if (now.activity == NowPlayingActivity.NONE) return
        val isVod = VodNowPlaying.state.value != null
        val playing = now.status == NowPlayingStatus.PLAYING || now.status == NowPlayingStatus.LOADING
        val toggle = when (command) {
            HaCommand.PLAY_PAUSE -> true
            HaCommand.PLAY -> !playing
            HaCommand.PAUSE -> playing
            HaCommand.NEXT, HaCommand.PREVIOUS -> false
        }
        when {
            command == HaCommand.NEXT -> if (!isVod) HaRemoteCommands.zapLive(1)
            command == HaCommand.PREVIOUS -> if (!isVod) HaRemoteCommands.zapLive(-1)
            !toggle -> Unit
            isVod -> HaRemoteCommands.toggleVod()
            else -> live.get().togglePause()
        }
    }

    /** The picture at [url] as base64, or null if it can't be fetched. Kept small: posters and logos. */
    private suspend fun albumArt(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > MAX_ART_BYTES) null
                else android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "Poster fetch failed: ${it.message}") }.getOrNull()
    }

    private data class Snapshot(val now: NowPlaying, val profileName: String?, val connected: Boolean)

    private fun publish(client: MqttAsyncClient, topic: String, payload: String) {
        runCatching { client.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, true) }
            .onFailure { Log.w(TAG, "MQTT publish to $topic failed: ${it.message}") }
    }

    private fun failureOf(e: MqttException): HaConnectionStatus.Failed {
        val reason = when (e.reasonCode.toShort()) {
            MqttException.REASON_CODE_FAILED_AUTHENTICATION,
            MqttException.REASON_CODE_NOT_AUTHORIZED -> HaFailure.BAD_CREDENTIALS
            MqttException.REASON_CODE_SERVER_CONNECT_ERROR,
            MqttException.REASON_CODE_BROKER_UNAVAILABLE -> HaFailure.UNREACHABLE
            MqttException.REASON_CODE_CLIENT_TIMEOUT -> HaFailure.TIMEOUT
            else -> HaFailure.OTHER
        }
        return HaConnectionStatus.Failed(reason, e.cause?.message ?: e.message)
    }

    private companion object {
        const val TAG = "HaMqttBridge"
        const val KEEP_ALIVE_S = 30
        const val CONNECT_TIMEOUT_S = 10
        const val MAX_RETRY_MS = 60_000L
        const val PUBLISH_DEBOUNCE_MS = 400L
        const val MAX_ART_BYTES = 1_500_000
    }
}
