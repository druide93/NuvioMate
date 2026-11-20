package com.nuvio.tv.core.homeassistant

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One place that knows what the app is playing, for Home Assistant: the films/series player
 * (through [VodNowPlaying]) wins over live TV, and nothing counts while the app isn't on screen.
 * Only runs while someone collects [nowPlaying] (the bridge, when it's switched on).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NowPlayingHub @Inject constructor(
    private val live: LiveTvPlaybackController,
    private val epgDatabase: EpgDatabase,
    private val liveTvPreferences: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True while the app is on screen. */
    val onScreen: Flow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .distinctUntilChanged()

    /** The programme on air for the live channel, read again when it ends. */
    private val liveProgram: Flow<EpgProgram?> = live.state
        .map { it.channelKey }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            if (key == null) flowOf(null) else flow {
                while (true) {
                    val program = runCatching { programOnAir(key) }.getOrNull()
                    emit(program)
                    val untilEnd = program?.let { it.stopMs - System.currentTimeMillis() } ?: NO_GUIDE_RETRY_MS
                    delay(untilEnd.coerceIn(MIN_WAIT_MS, NO_GUIDE_RETRY_MS) + 1_000L)
                }
            }.flowOn(Dispatchers.IO)
        }

    private val liveNowPlaying: Flow<NowPlaying?> = combine(live.state, liveProgram) { state, program ->
        liveSnapshot(state, program)
    }

    val nowPlaying: StateFlow<NowPlaying> = combine(onScreen, VodNowPlaying.state, liveNowPlaying) { onScreen, vod, liveNow ->
        if (!onScreen) NowPlaying.NOTHING else vod ?: liveNow ?: NowPlaying.NOTHING
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), NowPlaying.NOTHING)

    private fun liveSnapshot(state: LivePlaybackState, program: EpgProgram?): NowPlaying? {
        if (state.channelKey == null) return null
        val channel = live.playingChannel?.takeIf { it.key == state.channelKey }
        val catchup = state.catchupTitle
        val status = when {
            state.isPlaying -> NowPlayingStatus.PLAYING
            state.userPaused -> NowPlayingStatus.PAUSED
            else -> NowPlayingStatus.LOADING
        }
        return NowPlaying(
            activity = if (catchup != null) NowPlayingActivity.CATCHUP else NowPlayingActivity.LIVE,
            status = status,
            mediaTitle = catchup ?: program?.title,
            channelName = channel?.name,
            channelNumber = channel?.number,
            channelGroup = channel?.group,
            programTitle = catchup ?: program?.title,
            programStartMs = if (catchup == null) program?.startMs else null,
            programEndMs = if (catchup == null) program?.stopMs else null,
            year = if (catchup == null) program?.year?.toString() else null,
            imageUrl = channel?.logo
        )
    }

    /** Same reading as the launcher rows: straight from the stored guide, with the guide offset applied. */
    private suspend fun programOnAir(channelKey: String): EpgProgram? {
        val shift = liveTvPreferences.settings.first().epgOffsetMinutes * 60_000L
        val now = System.currentTimeMillis()
        val listings = epgDatabase.range(now - shift, now - shift + 1, channels = listOf(channelKey))
        val program = listings[channelKey]?.firstOrNull { it.isLive(now - shift) } ?: return null
        return if (shift == 0L) program else program.copy(startMs = program.startMs + shift, stopMs = program.stopMs + shift)
    }

    private companion object {
        const val MIN_WAIT_MS = 15_000L
        const val NO_GUIDE_RETRY_MS = 15 * 60_000L
    }
}
