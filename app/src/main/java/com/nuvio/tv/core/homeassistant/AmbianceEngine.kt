package com.nuvio.tv.core.homeassistant

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** What the ambiance is doing, for the settings screen. */
sealed interface AmbianceStatus {
    data object Idle : AmbianceStatus
    data class Active(val moment: AmbianceMoment) : AmbianceStatus
    data object Previewing : AmbianceStatus
    data class Failed(val reason: HaApiFailure) : AmbianceStatus
}

/**
 * Runs the ambiance inside the app: follows what's playing, and at the start, on pause and at the
 * end sends the chosen light actions straight to Home Assistant. Nothing is written into HA.
 * Before the first change it notes how each light was, to put it back at the end, and leaves
 * alone any light someone changed by hand in the meantime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AmbianceEngine @Inject constructor(
    private val store: AmbianceStore,
    private val hub: dagger.Lazy<NowPlayingHub>,
    private val api: HaApiClient
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "Ambiance engine stopped", e) }
    )
    private val mutex = Mutex()
    private var started = false

    private val _status = MutableStateFlow<AmbianceStatus>(AmbianceStatus.Idle)
    val status: StateFlow<AmbianceStatus> = _status.asStateFlow()

    /** A running ambiance: how the lights were, what we set them to, and what they can do. */
    private class Session(
        val before: Map<String, LightSnapshot>,
        val caps: Map<String, LightCapabilities>,
        // Lights are driven in parallel, so this map is written from several coroutines.
        val expected: MutableMap<String, AmbianceRules.Expected?> = java.util.Collections.synchronizedMap(mutableMapOf()),
        var paused: Boolean = false
    )

    private var session: Session? = null
        set(value) { field = value; _running.value = value != null }
    private var previewJob: Job? = null

    /** The player's Cinéma button (see [AmbianceOverride]); back to AUTO when playback stops. */
    private val override = MutableStateFlow(AmbianceOverride.AUTO)

    private val _running = MutableStateFlow(false)
    /** True while the lights are in the ambiance (the player's Cinéma button shows lit). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** The player shows its Cinéma button once at least one light is chosen. */
    val available: Flow<Boolean> = store.cinema.map { it.lights.isNotEmpty() }.distinctUntilChanged()

    /** One step of playback for the ambiance; [stopped] when nothing plays any more. */
    private data class Step(val phase: AmbianceRules.Phase?, val onScreen: Boolean, val stopped: Boolean)

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Ambiance engine started")
        scope.launch {
            combine(store.cinema, override) { ambiance, manual -> ambiance to manual }.collectLatest { (ambiance, manual) ->
                Log.i(TAG, "Ambiance: enabled=${ambiance.enabled} manual=$manual lights=${ambiance.lights.size} kinds=${ambiance.kinds} time=${ambiance.timeMode} days=${ambiance.days}")
                if (ambiance.lights.isEmpty() || (!ambiance.enabled && manual != AmbianceOverride.FORCED_ON)) {
                    withContext(NonCancellable) { mutex.withLock { endSession(ambiance) } }
                    // Switched off by hand while it can't start by itself anyway: nothing left to hold.
                    if (manual == AmbianceOverride.FORCED_OFF) override.value = AmbianceOverride.AUTO
                    return@collectLatest
                }
                val nowPlayingHub = hub.get()
                combine(nowPlayingHub.nowPlaying, nowPlayingHub.onScreen) { now, onScreen ->
                    val phase = AmbianceRules.phase(now, ambiance, manual)
                    Log.d(TAG, "Now: ${now.activity}/${now.status} onScreen=$onScreen manual=$manual -> $phase")
                    Step(phase, onScreen, stopped = now.activity == NowPlayingActivity.NONE)
                }
                    .filter { it.phase != null }
                    .distinctUntilChanged()
                    .collectLatest { step ->
                        val phase = step.phase!!
                        val wait = AmbianceRules.delayBefore(phase, step.onScreen, switchedOff = manual == AmbianceOverride.FORCED_OFF && !step.stopped)
                        Log.i(TAG, "Phase $phase (onScreen=${step.onScreen}, stopped=${step.stopped}), acting in $wait ms")
                        delay(wait)
                        // Once started, a step finishes: half the lights changed would be worse than a late change.
                        withContext(NonCancellable) {
                            mutex.withLock { handle(phase, ambiance, manual) }
                            // Playback is over: the button's choice was for what played.
                            if (step.stopped && manual != AmbianceOverride.AUTO) override.compareAndSet(manual, AmbianceOverride.AUTO)
                        }
                    }
            }
        }
    }

    /**
     * The player's Cinéma button: ends the ambiance now if it runs, else starts it now on what's
     * playing (live included, whatever the conditions). Returns true when it's being switched on.
     */
    fun toggle(): Boolean {
        val on = !_running.value
        override.value = if (on) AmbianceOverride.FORCED_ON else AmbianceOverride.FORCED_OFF
        Log.i(TAG, "Cinéma button: ${override.value}")
        return on
    }

    /**
     * "Essayer": applies [moment] to the chosen lights now, then puts them back after a few
     * seconds. Does nothing while a real ambiance is running.
     */
    fun preview(ambiance: Ambiance, moment: AmbianceMoment) {
        if (session != null || previewJob?.isActive == true) return
        previewJob = scope.launch {
            _status.value = AmbianceStatus.Previewing
            val trial = try {
                mutex.withLock { open(ambiance, reuseStored = false) }
            } catch (e: HaApiException) {
                fail(e); return@launch
            }
            try {
                eachLight(ambiance) { apply(trial, it, it.action(moment)) }
                delay(PREVIEW_MS)
                eachLight(ambiance) { restore(trial, it.entityId) }
                _status.value = AmbianceStatus.Idle
            } catch (e: HaApiException) {
                fail(e)
            }
        }
    }

    private suspend fun handle(phase: AmbianceRules.Phase, ambiance: Ambiance, manual: AmbianceOverride) {
        Log.i(TAG, "Handle $phase, session=${session != null}")
        try {
            when (phase) {
                AmbianceRules.Phase.PLAYING -> {
                    val current = session
                    if (current == null) {
                        // Started by hand: now, whatever the day and time.
                        val night = if (manual == AmbianceOverride.FORCED_ON) null else isNight(ambiance)
                        if (manual != AmbianceOverride.FORCED_ON && !AmbianceRules.timeAllows(ambiance, LocalDateTime.now(), night)) {
                            Log.i(TAG, "Not now: day/time conditions not met (night=$night)")
                            return
                        }
                        val fresh = open(ambiance, reuseStored = true)
                        session = fresh
                        store.setSnapshot(StoredSnapshot(System.currentTimeMillis(), fresh.before.values.toList()))
                        eachLight(ambiance) { apply(fresh, it, it.start) }
                        _status.value = AmbianceStatus.Active(AmbianceMoment.START)
                    } else if (current.paused) {
                        current.paused = false
                        eachLight(ambiance) { light ->
                            when {
                                light.pause.type == LightActionType.NOTHING -> Unit
                                light.start.type != LightActionType.NOTHING -> apply(current, light, light.start)
                                else -> restore(current, light.entityId)
                            }
                        }
                        _status.value = AmbianceStatus.Active(AmbianceMoment.START)
                    }
                }
                AmbianceRules.Phase.PAUSED -> {
                    // Started by hand on a paused show: the start first, then the pause.
                    if (session == null && manual == AmbianceOverride.FORCED_ON) handle(AmbianceRules.Phase.PLAYING, ambiance, manual)
                    val current = session ?: return
                    if (current.paused) return
                    current.paused = true
                    eachLight(ambiance) { apply(current, it, it.pause) }
                    _status.value = AmbianceStatus.Active(AmbianceMoment.PAUSE)
                }
                AmbianceRules.Phase.NONE -> endSession(ambiance)
            }
        } catch (e: HaApiException) {
            fail(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Ambiance failed on $phase", e)
            _status.value = AmbianceStatus.Failed(HaApiFailure.OTHER)
        }
    }

    private suspend fun endSession(ambiance: Ambiance) {
        val current = session ?: return
        session = null
        try {
            eachLight(ambiance) { light ->
                val action = light.end
                // Never touched by the ambiance: nothing to put back, and a hand-made change stays.
                val skip = action.type == LightActionType.NOTHING ||
                    (action.type == LightActionType.RESTORE && !current.expected.containsKey(light.entityId)) ||
                    !stillOurs(current, light.entityId)
                if (!skip) {
                    if (action.type == LightActionType.RESTORE) restore(current, light.entityId, checked = true)
                    else apply(current, light, action)
                }
            }
            _status.value = AmbianceStatus.Idle
        } catch (e: HaApiException) {
            fail(e)
        } finally {
            store.setSnapshot(null)
        }
    }

    /** Reads each chosen light once: its capabilities, and how it is now (or how it was before a crash). */
    private suspend fun open(ambiance: Ambiance, reuseStored: Boolean): Session {
        val stored = if (reuseStored) store.snapshot()?.takeIf { System.currentTimeMillis() - it.takenAtMs < STORED_SNAPSHOT_MAX_AGE_MS } else null
        val storedById = stored?.lights?.associateBy { it.entityId }.orEmpty()
        val before = mutableMapOf<String, LightSnapshot>()
        val caps = mutableMapOf<String, LightCapabilities>()
        for (light in ambiance.lights) {
            val state = api.state(light.entityId) ?: continue
            caps[light.entityId] = LightCapabilities.of(state["attributes"] as? kotlinx.serialization.json.JsonObject)
            before[light.entityId] = storedById[light.entityId] ?: LightSnapshot.of(light.entityId, state)
        }
        return Session(before, caps)
    }

    private suspend fun apply(session: Session, light: LightSetup, action: LightAction) {
        val caps = session.caps[light.entityId] ?: return
        if (action.type == LightActionType.RESTORE) {
            restore(session, light.entityId)
            return
        }
        val (service, data) = AmbianceRules.serviceCall(light.entityId, action, caps) ?: return
        Log.i(TAG, "light.$service $data")
        api.callService("light", service, data)
        session.expected[light.entityId] = AmbianceRules.expected(action, caps, session.before[light.entityId])
        // Some bulbs (Yeelight) wake up in their last mode and drop a colour sent while they fade
        // in: send the colour again once the light is surely on.
        if (AmbianceRules.needsColorResend(service, data)) {
            delay(COLOR_RESEND_MS)
            Log.i(TAG, "light.$service (again) ${light.entityId}")
            api.callService("light", service, data)
        }
    }

    /** Runs [block] for every chosen light at the same time, so the last one isn't late. */
    private suspend fun eachLight(ambiance: Ambiance, block: suspend (LightSetup) -> Unit) = coroutineScope {
        ambiance.lights.map { light -> async { block(light) } }.awaitAll()
    }

    private suspend fun restore(session: Session, entityId: String, checked: Boolean = false) {
        val before = session.before[entityId] ?: return
        if (!checked && !stillOurs(session, entityId)) return
        val (service, data) = AmbianceRules.restoreCall(before)
        Log.i(TAG, "light.$service (restore) $data")
        api.callService("light", service, data)
        session.expected[entityId] = AmbianceRules.Expected(before.on, before.brightness?.let(AmbianceRules::toPct))
        if (AmbianceRules.needsColorResend(service, data)) {
            delay(COLOR_RESEND_MS)
            api.callService("light", service, data)
        }
    }

    /** True if nobody changed the light by hand since we last set it. */
    private suspend fun stillOurs(session: Session, entityId: String): Boolean {
        if (!session.expected.containsKey(entityId)) return true
        val now = api.state(entityId) ?: return false
        return AmbianceRules.untouched(session.expected[entityId], LightSnapshot.of(entityId, now))
    }

    private suspend fun isNight(ambiance: Ambiance): Boolean? {
        if (ambiance.timeMode != AmbianceTimeMode.NIGHT) return null
        return runCatching { api.state("sun.sun")?.str("state") }.getOrNull()?.let { it == "below_horizon" }
    }

    private fun fail(e: HaApiException) {
        Log.w(TAG, "Ambiance: ${e.reason} ${e.message}")
        _status.value = AmbianceStatus.Failed(e.reason)
    }

    private companion object {
        const val TAG = "HaAmbiance"
        const val PREVIEW_MS = 10_000L
        const val COLOR_RESEND_MS = 1_000L
        /** A stored "as before" older than this is from another evening: take a fresh one. */
        const val STORED_SNAPSHOT_MAX_AGE_MS = 12 * 60 * 60_000L
    }
}
