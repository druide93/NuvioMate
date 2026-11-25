package com.nuvio.tv.livetv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.tv.livetv.stalker.StalkerGateway
import com.nuvio.tv.livetv.stalker.StalkerMediaPolicy
import com.nuvio.tv.livetv.stalker.StalkerPlayback
import com.nuvio.tv.livetv.stalker.StalkerMimeProbe
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class LivePlaybackState(
    val channelKey: String? = null,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** The stream's pixel-shape flag (1 = square pixels). */
    val pixelRatio: Float = 1f,
    /** Non-null while an archive (catch-up) program is playing instead of the live stream. */
    val catchupTitle: String? = null,
    /**
     * You paused it (play/pause), as opposed to it just not playing yet: between channels and
     * while reconnecting it isn't playing either, and that must not count as paused.
     */
    val userPaused: Boolean = false,
    /** What's playing has shown its first picture (since it was asked for). */
    val pictureShown: Boolean = false,
    /** What's playing has started (played or shown a picture) since it was asked for. */
    val started: Boolean = false
)

data class LiveTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
    /** Closed captions inside the video: which kind ([LiveCaptions.CC1]…), or null for subtitles. */
    val kind: String? = null,
    /** The track's language code ("en", "es"…), if it says. */
    val language: String? = null,
    /** Audio channels (2 stereo, 6 for 5.1), 0 if unknown. */
    val channels: Int = 0
)

/**
 * One ExoPlayer shared by the guide preview window and the full-screen player, so going
 * full screen (and back) never re-buffers the stream — just like TiviMate.
 */
@OptIn(UnstableApi::class)
@Singleton
class LiveTvPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stalker: StalkerGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adaptiveBuffer = AdaptiveLiveBuffer(scope, ::isAdaptiveRemoteLiveSource)

    var adaptiveLiveBufferEnabled: Boolean = false
        set(value) { field = value; adaptiveBuffer.enabled = value }

    private var _player: ExoPlayer? = null
    val player: ExoPlayer? get() = _player

    private val _state = MutableStateFlow(LivePlaybackState())
    val state: StateFlow<LivePlaybackState> = _state.asStateFlow()

    /**
     * Counts pictures that appeared after a wait: the first one of each stream, and the first one
     * after each skip inside a stream. Only ever goes up, so "a picture since then" is a bigger number.
     */
    private val _pictures = MutableStateFlow(0)
    val pictures: StateFlow<Int> = _pictures.asStateFlow()

    /** Counts streams asked for (each one starts a fresh [state]); a request that changed nothing doesn't count. */
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests.asStateFlow()

    /** Counts times Live TV was told to stop playing (or to let go of the player for Nuvio's own). */
    private val _stops = MutableStateFlow(0)
    val stops: StateFlow<Int> = _stops.asStateFlow()

    /** Playing with sound and no picture at all (a radio-like channel): no picture will come. */
    fun playingWithoutPicture(): Boolean {
        val p = _player ?: return false
        val tracks = p.currentTracks
        // No video selected: none in the stream, or one this TV can't decode (sound only then).
        return p.isPlaying && !tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
    }

    /** Where [positionMs] in the stream playing is already loaded, so a skip there shows a picture straight away. */
    fun loadedAt(positionMs: Long): Boolean {
        val p = _player ?: return false
        return positionMs >= p.currentPosition - 1_000 && positionMs <= p.bufferedPosition
    }

    private var currentChannel: LiveChannel? = null
    private var stalkerResolveJob: Job? = null
    private var stalkerGeneration = 0L
    private var activeStalkerRef: StalkerStreamRef? = null
    private var stalkerMediaPolicy: StalkerMediaPolicy? = null
    private var stalkerMimeType: String? = null

    // ---------------------------------------------------------------- pause and rewind

    private val timeshift = LiveTvTimeshift(context).also {
        // Nothing is recording when the app starts: whatever's left from last time (the app
        // closed or the box switched off while paused) goes.
        it.cleanLeftovers(listOf(java.io.File(context.cacheDir, "timeshift"), it.baseDir))
    }
    /**
     * Catch-up replays (TS) saved to the device while they play, and played from there: skipping
     * back is instant, and so is skipping ahead as far as the replay has come in. Before, every
     * skip asked the provider for the replay again from the new point. HLS replays can already
     * skip within themselves and are played as before.
     */
    private val replayCache = LiveTvTimeshift(context)
    /** Settings: save catch-up replays while they play (see [replayCache]). */
    var catchupCache: Boolean = true
    @Volatile private var replayLocal = false
    private var replayJob: Job? = null

    /** A provider link waiting for the line a recording just let go of (see [openWhenLineFree]). */
    private var lineWaitJob: Job? = null

    /**
     * Gives the player a provider link with [open] once the connection of a recording just
     * stopped (the replay's copy, the pause buffer) is really closed, and a moment more; at once
     * when there's none. Accounts with one connection held a request that came while the provider
     * still counted the copy's (9 s), or refused it: a live channel picked in the guide during a
     * replay showed "Provider refused the stream (HTTP 403)". Dropped if something else is asked
     * for meanwhile.
     */
    private fun openWhenLineFree(p: ExoPlayer, open: (afterWait: Boolean) -> Unit) {
        lineWaitJob?.cancel()
        lineWaitJob = null
        if (!ProviderLine.busy()) { open(false); return }
        val request = _requests.value
        val url = currentUrl
        // Off the copy (or what played) meanwhile: it isn't what was asked for any more. Still
        // loading (the stop says idle): "Loading…" stays up.
        p.stop()
        showLoading()
        lineWaitJob = scope.launch {
            ProviderLine.awaitFree()
            // Something else asked for meanwhile, a recording playing included.
            if (_requests.value != request || currentUrl != url || _player !== p ||
                _recordingPlayback.value != null || savedRecordingId != null
            ) return@launch
            lineWaitJob = null
            open(true)
        }
    }
    /** Still loading, though the player itself is stopped meanwhile (it says idle). */
    private fun showLoading() {
        if (!_state.value.isBuffering) _state.value = _state.value.copy(isBuffering = true)
    }

    /** Waiting at the end of the saved part of a replay: how much was saved, and since when. */
    private var replayWaitSavedMs = -1L
    private var replayWaitSince = 0L
    /**
     * A replay's copy is on its way (the player has let go of the stream until it plays from the
     * copy): still loading, though the player itself is stopped meanwhile.
     */
    @Volatile private var copyStarting = false
    /** A skip moved where that copy starts ([retargetCopy]): the wait for it counts from then. */
    @Volatile private var copyWaitFrom = 0L
    /**
     * Where that copy starts is a point chosen in it (a skip), not where a whole-minute link was
     * asked to be: if the wait runs out, it still starts there (see [copyStartPoint]).
     */
    @Volatile private var copyExact = false
    /**
     * The copy on its way carries a replay on by itself from where it froze (see [copyStarved]):
     * it starts at that very point, waiting for it under the picture kept, rather than on the
     * whole minute its link starts at. PureTv sends the first seconds at about real time, so the
     * usual 10 s wait started it up to a minute back, and each restart played again what had just
     * been watched (and could come back to the same minute, over and over).
     */
    @Volatile private var copyKeepsPoint = false
    /** Until when the next copy started keeps its point (see [copyKeepsPoint]); 0: none. */
    @Volatile private var keepPointUntil = 0L
    /** A catch-up's link: the time it starts at, by the guide's clock (null: not a replay). */
    @Volatile private var linkStartWallMs: Long? = null
    /**
     * What's starting plays once in (false: it's there paused). Set by each start of a replay
     * (see [playResolved]) and by Play/Pause meanwhile; the copy plays by it once in.
     */
    @Volatile private var copyPlays = true

    /**
     * Whether what's started again now plays once in: as the player is set (a stopped player
     * waiting for a replay's copy or a Stalker link says so too), or to play on coming back when
     * Home paused it.
     */
    private fun wantsToPlay(): Boolean = _player?.let { it.playWhenReady || pausedForBackground || displaySwitchHold } ?: true

    /**
     * Match Frame Rate holds the picture while the TV switches mode: not a pause of yours. Before,
     * a skip asked of the provider during the switch took it for one, and the replay stayed paused.
     */
    @Volatile var displaySwitchHold = false

    /** This replay's link plays straight from the provider only because its point is far in (see [startReplayCache]). */
    @Volatile private var farLeadDirect = false
    /** A replay played straight from the provider far into its link failed before it ever played: the copy is used again. */
    @Volatile private var farDirectFailed = false

    /**
     * The replay playing shows paused: you paused it (not Home, where it played until then, and
     * not over a problem shown). A skip that asks the provider for it again keeps it paused.
     */
    private fun replayPausedNow(): Boolean =
        _state.value.let { it.catchupTitle != null && it.error == null } && _player != null && !wantsToPlay()

    private fun stopReplayCache() {
        replayJob?.cancel()
        replayJob = null
        replayLocal = false
        copyStarting = false
        copyKeepsPoint = false
        replayWaitSavedMs = -1L
        if (replayCache.session != null) replayCache.stop()
    }

    /**
     * Starts saving the catch-up at [url] and plays it from the device once enough of it is in
     * (see [copyStartPoint]). [plays] false: it's there paused once in (a paused replay picked up
     * again after the background, or skipped in while paused).
     */
    private fun startReplayCache(
        channel: LiveChannel,
        url: String,
        plays: Boolean = true,
        /** Saved whatever the point (it couldn't be reached straight from the provider). */
        ignoreFarLead: Boolean = false,
    ): Boolean {
        val p = _player ?: return false
        // The point asked for is further into the link than even the fastest provider could save
        // in the time a copy waits (see copyStartPoint): a Stalker replay asked minutes into the
        // show (its link always starts at the show's start), or one picked up after the background
        // on its old link. It's played straight from the provider, which moves to that point in
        // the stream once it's in (see tryLeadSeek), as before copies. Before, the copy started
        // near the link's start: the replay went back to the show's start, or, picked up after
        // the background, was then moved past what was saved ("Loading" for ever). A Stalker link
        // can't be asked from a point, so it goes this way from a minute in (a copy reaches that
        // only from a provider sending over 7 times faster than it plays). When that link can't be
        // moved to the point, or never started, the copy is used as before.
        val farIn = leadSeekMs + REPLAY_COPY_AHEAD_MS > REPLAY_COPY_WAIT_MS * REPLAY_FASTEST_SAVE ||
            (activeStalkerRef != null && leadSeekMs > STALKER_DIRECT_FROM_MS)
        if (farIn && !ignoreFarLead && !farDirectFailed) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Catch-up: the point asked for is ${leadSeekMs / 1000} s into the link, more than a copy could save in time; playing it straight from the provider"
            )
            farLeadDirect = true
            return false
        }
        replayCache.baseDir = timeshift.baseDir
        if (!replayCache.hasRoomFor(120)) {
            // Said, as it changes how replays behave (no instant skips, and the provider's own
            // hiccups reach playback).
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: not enough room on the device to save the replay (about 4.5 GB needed); playing it straight from the provider")
            return false
        }
        val session = replayCache.start(url, channel.headers, keepMinutes = null, reconnect = false, label = "Catch-up save", refreshStream = bufferResolver(channel), policy = stalkerMediaPolicy, mimeType = stalkerMimeType,
            linkStartMs = archiveExpectWallMs) ?: return false
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: saving the replay while it plays, for instant skipping")
        p.stop()
        // Loading until the copy plays, though the stopped player says it's idle: the small
        // "Loading…" shows where there's no waiting screen, and the waiting screen doesn't take
        // the stopped player for the replay being ready.
        copyStarting = true
        copyKeepsPoint = android.os.SystemClock.elapsedRealtime() < keepPointUntil
        keepPointUntil = 0L
        copyExact = copyKeepsPoint
        copyWaitFrom = 0L
        copyPlays = plays
        // The stopped player says whether it plays once in, like the screen: kept paused from what
        // played before, Play/Pause meanwhile did the opposite of what showed, and Home didn't
        // count it as playing. Before, it always said it plays: a paused replay picked up again
        // after the background, or skipped in so far the provider was asked again, then played.
        if (backgroundJob?.isActive != true) p.playWhenReady = plays
        showLoading()
        replayJob?.cancel()
        replayJob = scope.launch {
            val f = java.io.File(session.playUri.path ?: "")
            var waited = 0
            val askedAt = System.currentTimeMillis()
            // Not past the save giving up (the provider sent nothing, or refused twice): it then
            // goes straight to playing it directly or the next link, instead of waiting out the
            // time for a copy that won't come. That time counts from the provider's answer, not
            // from asking: PureTv answered a replay 14.9 s after it was asked, and the 15 s from
            // asking threw the save away 0.1 s later, just before its first piece (then 90 s
            // before the replay played, through the other links). 15 s as before, and up to 10 s
            // after a late answer; 20 s at most with no answer at all.
            while (!(f.exists() && f.length() > 0) && replayCache.isRecording) {
                val t = System.currentTimeMillis()
                val answered = replayCache.answeredAtMs
                if (if (answered > 0L) t - askedAt >= 15_000 && t - answered >= 10_000 else t - askedAt >= 20_000) break
                delay(200); waited += 200
            }
            if (replayCache.session != session || currentUrl != url) return@launch
            archiveStartPending = true
            if (!f.exists() || f.length() == 0L) {
                // The save was still waiting after 15 s (the provider sent nothing, as opposed to
                // refusing): the same link played straight would wait for the same nothing. The
                // next link goes at once instead. Seen: playing it straight waited 15 s more, and
                // with the links after it, the replay took 90 s to play again (the last link, a
                // different kind, answered in 0.2 s).
                val timedOut = replayCache.isRecording
                val next = if (timedOut) fallbackUrls.firstOrNull() else null
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    if (next != null) "Catch-up: nothing came in to save for ${waited / 1000} s; trying ${redact(next)}"
                    else "Catch-up: nothing came in to save; playing it directly"
                )
                replayCache.stop()
                // Once the save has let go of its connection (see openWhenLineFree).
                ProviderLine.awaitFree()
                if (currentUrl != url) return@launch
                copyStarting = false
                if (next != null && fallbackUrls.firstOrNull() == next) {
                    fallbackUrls.removeFirst()
                    hlsCatchupFailedOn = null
                    // A TS link: saved while it plays, like the one before (this job is replaced).
                    if (playCatchupLink(p, channel, next)) return@launch
                    lastProgressAt = android.os.SystemClock.elapsedRealtime()
                } else {
                    p.setMediaSource(buildMediaSource(url, channel.headers, isLive = false))
                }
            } else {
                val startAt = copyStartPoint()
                if (replayCache.session != session || currentUrl != url) return@launch
                replayLocal = true
                copyStarting = false
                playCopyFrom(p, session, startAt)
            }
            p.prepare()
            if (backgroundJob?.isActive == true) {
                // Home pressed while the copy was on its way: it stays paused (it played behind
                // the launcher until the line was let go), and plays on coming back if it was to.
                if (copyPlays) pausedForBackground = true
                p.playWhenReady = false
            } else {
                p.playWhenReady = copyPlays
            }
        }
        return true
    }

    /** A replay's copy is on its way: the player is stopped until it plays from the copy. */
    fun copyOnItsWay(): Boolean = copyStarting && replayJob?.isActive == true

    /** Counts skips in replays ([noteSkip]): a resume after the background gives way to one made since. */
    @Volatile private var skipSerial = 0

    /** You skipped in the replay (see [skipSerial]). */
    fun noteSkip() {
        skipSerial++
    }

    /** Gives the player the replay's copy, starting [startAt] into it. */
    private fun playCopyFrom(p: ExoPlayer, session: LiveTvTimeshift.Session, startAt: Long) {
        // The player keeps a start point it's given, except 0, which it reads as "where you
        // like" (the newest part of a growing list): then the timeline goes to the start.
        archiveStartPending = startAt <= 0L
        p.setMediaSource(localRecordingSource(session.playUri), startAt.coerceAtLeast(0L))
        p.prepare()
    }

    /**
     * Where to start the replay's copy, once it holds [REPLAY_COPY_AHEAD_MS] past the point
     * asked for ([leadSeekMs] into it: catch-up links start on the whole minute, a skip picks a
     * point in it). The player reads the copy's list of pieces only every
     * [REPLAY_LIST_TARGET_SEC] s. Started on the first piece (2 s), it played that from the whole
     * minute and stopped on "Loading…" until it looked again; only then did it skip to the point
     * asked for, and load once more. Waits [REPLAY_COPY_WAIT_MS] at most (a provider sending
     * about as fast as it plays), no longer than the player would have stopped anyway, and not
     * at all when even a fast provider couldn't send that much in time (a Stalker replay asked
     * a few minutes into the show, where the copy starts at the show's start; asked further in
     * than any provider could send in time, it isn't saved at all, see [startReplayCache]). It
     * then starts as close to that point as that much ahead allows, so it plays on without
     * stopping; a point picked by a skip ([copyExact]) is kept. A copy of an HLS stream keeps its
     * own way (the timeline skips).
     */
    private suspend fun copyStartPoint(): Long {
        // HLS copies don't count what's saved: the player skips to the point once it can, as before.
        if (replayCache.session?.kind != LiveTvTimeshift.Kind.TS) return 0L
        val began = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val lead = leadSeekMs
            val saved = replayCache.savedMs
            val waitedMs = android.os.SystemClock.elapsedRealtime() - maxOf(began, copyWaitFrom)
            val missingMs = lead + REPLAY_COPY_AHEAD_MS - saved
            // Carrying on by itself from where it froze: it waits longer for that point (under the
            // picture kept), as starting earlier plays again what was just watched.
            val budgetMs = if (copyKeepsPoint) REPLAY_KEEP_POINT_WAIT_MS else REPLAY_COPY_WAIT_MS
            val giveUp = waitedMs >= budgetMs ||
                missingMs > (budgetMs - waitedMs) * REPLAY_FASTEST_SAVE
            val startAt = when {
                missingMs <= 0 -> lead
                // Saved to its end, or the save stopped: nothing more is coming to wait for.
                !replayCache.isRecording -> if (lead < saved) lead else (saved - REPLAY_COPY_AHEAD_MS).coerceIn(0L, lead)
                giveUp && copyExact -> lead.coerceAtMost((saved - 1_000).coerceAtLeast(0L))
                giveUp -> (saved - REPLAY_COPY_AHEAD_MS).coerceIn(0L, lead)
                else -> null
            }
            if (startAt != null) {
                // Done here: nothing left for the timeline to skip.
                leadSeekMs = 0L
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "Catch-up: copy played from ${startAt / 1000} s in" +
                        (if (lead > 0) " (the point asked for is ${lead / 1000} s in${if (copyKeepsPoint) ", where it froze" else ""})" else "") +
                        ", ${saved / 1000} s saved after ${waitedMs / 100 / 10.0} s more"
                )
                return startAt
            }
            delay(100)
        }
    }

    /**
     * A skip while the replay's copy isn't playing yet (see [copyStartPoint]): it starts at
     * [inStreamMs] instead, when that's in the copy or about to be. False otherwise (the
     * provider is then asked again from there). Before, the copy on its way was thrown away and
     * saved again from the new point.
     */
    fun retargetCopy(inStreamMs: Long): Boolean {
        val p = _player ?: return false
        if (!copyOnItsWay() || replayCache.session?.kind != LiveTvTimeshift.Kind.TS || savedRecordingId != null ||
            _state.value.catchupTitle == null
        ) return false
        if (inStreamMs < 0 || inStreamMs > replayCache.savedMs + REPLAY_COPY_AHEAD_MS) return false
        leadSeekMs = inStreamMs
        copyExact = true
        copyWaitFrom = android.os.SystemClock.elapsedRealtime()
        // The seek bar goes by the stopped player's place meanwhile (a replay already started:
        // a copy read again, or one played straight that moved on to a saved link). Only the
        // place moves; the copy is then given its own start point (playCopyFrom).
        p.seekTo(inStreamMs)
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: skip to ${inStreamMs / 1000} s while the copy is on its way; it starts there")
        return true
    }

    /**
     * A skip to [positionMs] in the replay's copy saved on this box lands past (or just before
     * the end of) what the player has read of the copy's list, while more is saved. The player
     * reads that list again only every [REPLAY_LIST_TARGET_SEC] s, and the copy can come in many
     * times faster than it plays: a plain skip there left a frozen picture until the player next
     * looked, or the replay stopped again soon after. See [rereadCopyAt].
     */
    fun copyRereadNeeded(positionMs: Long): Boolean {
        val p = _player ?: return false
        if (!replayLocal || savedRecordingId != null || replayCache.session == null || _state.value.catchupTitle == null) return false
        val listed = listedMs(p) ?: return false
        return replayCache.savedMs > listed && positionMs + REPLAY_COPY_AHEAD_MS > listed
    }

    /**
     * Plays the replay's copy again from [positionMs], which reads its whole list now (see
     * [copyRereadNeeded]). The player lets go of the picture meanwhile. With less than
     * [REPLAY_COPY_AHEAD_MS] saved past that point, it waits for it first, stopped (as a new
     * copy does, see [copyStartPoint]): it would otherwise stop again soon after, until the
     * player next read the list. False if the copy isn't what's playing any more.
     */
    fun rereadCopyAt(positionMs: Long): Boolean {
        val p = _player ?: return false
        val session = replayCache.session ?: return false
        if (!replayLocal || savedRecordingId != null || _state.value.catchupTitle == null) return false
        val at = positionMs.coerceAtLeast(0L)
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Catch-up: skip to ${at / 1000} s in the copy, past what the player has read of it " +
                "(${(listedMs(p) ?: 0L) / 1000} s of ${replayCache.savedMs / 1000} s saved); reading the copy again from there"
        )
        leadSeekMs = 0L
        if (replayCache.savedMs >= at + REPLAY_COPY_AHEAD_MS || !replayCache.isRecording) {
            playCopyFrom(p, session, at)
            return true
        }
        // Stopped until enough is saved past it; the seek bar shows the new point meanwhile.
        // Paused or playing stays as it was.
        p.stop()
        p.seekTo(at)
        copyStarting = true
        copyExact = true
        copyKeepsPoint = false
        copyWaitFrom = 0L
        leadSeekMs = at
        showLoading()
        replayJob?.cancel()
        replayJob = scope.launch {
            val startAt = copyStartPoint()
            if (replayCache.session != session || !replayLocal) return@launch
            copyStarting = false
            playCopyFrom(p, session, startAt)
        }
        return true
    }

    /**
     * What's playing in [timeline] (null: nothing yet, or only the stand-in the player shows until
     * the stream answers).
     */
    private fun windowOf(p: ExoPlayer, timeline: androidx.media3.common.Timeline = p.currentTimeline): androidx.media3.common.Timeline.Window? {
        if (timeline.isEmpty) return null
        return timeline.getWindow(p.currentMediaItemIndex.coerceIn(0, timeline.windowCount - 1), androidx.media3.common.Timeline.Window())
            .takeUnless { it.isPlaceholder }
    }

    /** How much of the stream playing the player has read so far (null: not known yet). */
    private fun listedMs(p: ExoPlayer): Long? = windowOf(p)?.durationMs?.takeIf { it != C.TIME_UNSET }
    var timeshiftEnabled: Boolean = true
    var timeshiftMinutes: Int = 10
    /** Rewind anytime: full-screen channels play through a rolling buffer (see [startAlwaysBuffer]). */
    var rewindAlways: Boolean = false
    @Volatile private var bufferStorageId: String? = null

    /** The buffer goes on the drive set for recordings (a USB drive has far more room). */
    fun setBufferStorage(id: String) {
        if (id == bufferStorageId) return
        val first = bufferStorageId == null
        bufferStorageId = id
        val old = timeshift.baseDir
        timeshift.baseDir = com.nuvio.tv.livetv.record.RecordingStorage.resolve(context, id).timeshiftDir
        // The drive's own leftovers on first use this session (nothing is recording yet), and
        // the old place's when the setting changes.
        timeshift.cleanLeftovers(listOf(timeshift.baseDir), olderThanMs = if (first) 60_000L else 15 * 60_000L)
        if (old != timeshift.baseDir) timeshift.cleanLeftovers(listOf(old), olderThanMs = 15 * 60_000L)
    }

    /** The current buffer was started by Rewind Anytime (not by pausing). */
    @Volatile private var alwaysBuffer = false
    @Volatile private var bufferStartedAt = 0L
    /** A channel whose buffer kept failing: played directly until another channel is picked. */
    @Volatile private var bufferFailedKey: String? = null

    private fun shouldBuffer(channel: LiveChannel, catchupTitle: String?): Boolean =
        rewindAlways && timeshiftEnabled && catchupTitle == null && channel.drm == null &&
            stalkerMimeType != StalkerMimeProbe.DASH &&
            com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.value &&
            channel.key != bufferFailedKey && timeshift.hasRoomFor(timeshiftMinutes)

    /**
     * Rewind anytime: the channel is recorded from the moment it starts, and you watch from the
     * recording a few seconds behind live, so you can skip back at any time (up to the buffer
     * length) without having paused. One connection to the provider, as always.
     */
    private fun startAlwaysBuffer(channel: LiveChannel, url: String): Boolean {
        val p = _player ?: return false
        val session = timeshift.start(url, channel.headers, timeshiftMinutes, refreshStream = bufferResolver(channel), policy = stalkerMediaPolicy, mimeType = stalkerMimeType) ?: return false
        alwaysBuffer = true
        bufferStartedAt = System.currentTimeMillis()
        shiftPrepared = false
        _shiftStartedAt.value = session.startedAtMs
        shiftJob?.cancel()
        shiftJob = scope.launch {
            var waited = 0
            val f = java.io.File(session.playUri.path ?: "")
            while (waited < 15_000 && !(f.exists() && f.length() > 0)) { delay(200); waited += 200 }
            if (timeshift.session != session) return@launch
            if (!f.exists()) { bufferFailed(); return@launch }
            p.setMediaSource(localRecordingSource(session.playUri))
            p.prepare()
            p.playWhenReady = true
            shiftPrepared = true
        }
        return true
    }

    /** The buffer couldn't keep up (or the stream won't record): play this channel directly. */
    private fun bufferFailed() {
        val ch = currentChannel ?: return
        bufferFailedKey = ch.key
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Rewind buffer failed on ${ch.name}; playing it directly")
        goLiveFresh()
    }

    /** Rewind Anytime's buffer stopped coming in or playing: started over, or played directly when it failed straight away. */
    private fun bufferDropped() {
        if (System.currentTimeMillis() - bufferStartedAt < 20_000) bufferFailed() else goLiveFresh()
    }

    /** Drops the buffer and plays the channel again from now (a new buffer, if it's on). */
    private fun goLiveFresh() {
        val ch = currentChannel ?: return
        val url = currentUrl
        stopShift()
        currentUrl = null
        play(ch, overrideUrl = url.takeIf { it != ch.url })
    }

    /** Going full screen with Rewind Anytime on: switch the channel over to the buffer. */
    private fun watchFullscreen() {
        scope.launch {
            com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.collect { full ->
                val ch = currentChannel ?: return@collect
                if (!full || !rewindAlways || _shiftStartedAt.value != null || _recordingPlayback.value != null) return@collect
                if (_state.value.catchupTitle != null || _player == null) return@collect
                goLiveFresh()
            }
        }
    }

    /**
     * Left / Right should skip in the buffer: always after a pause; with Rewind Anytime, once
     * you're more than a few seconds behind live (otherwise they keep their usual jobs).
     */
    fun shiftIsBehind(): Boolean {
        if (_ownTimeline.value) {
            val p = _player ?: return false
            if (_state.value.userPaused || !p.isCurrentMediaItemDynamic) return true
            val offset = p.currentLiveOffset
            if (offset == C.TIME_UNSET) return false
            // Past how far behind live it normally plays (so one skip back already counts).
            val target = runCatching {
                p.currentTimeline.getWindow(p.currentMediaItemIndex, androidx.media3.common.Timeline.Window()).liveConfiguration?.targetOffsetMs
            }.getOrNull()?.takeIf { it != C.TIME_UNSET } ?: 10_000L
            return offset > target + OWN_BEHIND_MS
        }
        if (_shiftStartedAt.value == null) return false
        if (!alwaysBuffer || _state.value.userPaused) return true
        val pos = shiftPositionWallMs() ?: return false
        return System.currentTimeMillis() - pos > 20_000
    }

    @Volatile private var shiftReloads = 0

    /** Plays the buffer again from [positionMs] (after it ran dry or a read error). */
    private fun reloadShiftAt(positionMs: Long, why: String) {
        val p = _player ?: return
        val session = timeshift.session ?: return
        shiftReloads++
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: $why; picking up again")
        shiftJob?.cancel()
        shiftJob = scope.launch {
            delay(1_500)
            if (timeshift.session != session) return@launch
            // Never below what's actually still in the buffer (older parts may have been
            // trimmed since): a position earlier than that would just fail the same way again.
            val startAt = positionMs.coerceAtLeast(timeshift.trimmedMs)
            shiftStartPending = startAt == 0L
            p.setMediaSource(localRecordingSource(session.playUri), startAt)
            p.prepare()
            // Paused or playing stays as it was (Pause pressed while it picks up, or paused when
            // the read error came): before, it always played on once picked up.
            shiftPrepared = true
        }
    }

    /** The earliest point the buffer still has (wall clock), while playing from it. */
    fun shiftEarliestWallMs(): Long? = _shiftStartedAt.value?.let { it + timeshift.trimmedMs }

    private val _shiftStartedAt = MutableStateFlow<Long?>(null)
    /** Non-null while playing from a pause-and-rewind recording: when the recording started. */
    val shiftStartedAt: StateFlow<Long?> = _shiftStartedAt.asStateFlow()
    private var shiftPrepared = false
    /** Pause buffer: a start position of 0 that still has to be applied once the buffer's real timeline is in (see onTimelineChanged). */
    @Volatile private var shiftStartPending = false
    private var shiftJob: Job? = null

    /** Wall-clock time of what's on screen while playing a recording, or null. */
    fun shiftPositionWallMs(): Long? {
        val start = _shiftStartedAt.value ?: return null
        val p = _player ?: return start
        return start + timeshift.trimmedMs + if (shiftPrepared) p.currentPosition.coerceAtLeast(0) else 0L
    }

    /** Seek thumbnails: the rewind buffer's list of pieces on this box, while playing from it. */
    val shiftPlaylistFile: java.io.File?
        get() {
            if (_shiftStartedAt.value == null || !shiftPrepared) return null
            return timeshift.session?.playUri?.path?.let { java.io.File(it) }?.takeIf { it.exists() }
        }

    /** Skipping back and forward works on every recording (TS ones are saved in pieces too now). */
    fun shiftCanSeek(): Boolean = (timeshift.session != null && shiftPrepared) || _recordingPlayback.value != null || _ownTimeline.value

    // ---------------------------------------------------------------- a sports addon game's own timeline

    private val _ownTimeline = MutableStateFlow(false)
    /**
     * True while a sports addon game is playing whose stream can be paused and skipped through
     * by itself (a replay, or a live stream with its own rewind window). Pause and skipping then
     * work on the stream itself, as in Nuvio's own player, instead of the pause buffer.
     */
    val ownTimeline: StateFlow<Boolean> = _ownTimeline.asStateFlow()

    /** Where the stream is, how long its timeline is, and whether it's live (still growing). */
    data class OwnTimeline(val positionMs: Long, val durationMs: Long, val live: Boolean)

    fun ownTimelineNow(): OwnTimeline? {
        if (!_ownTimeline.value) return null
        val p = _player ?: return null
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return OwnTimeline(p.currentPosition.coerceIn(0L, d), d, p.isCurrentMediaItemDynamic)
    }

    /** The stream playing now has shown itself as live at some point (a live game, not a replay). */
    @Volatile private var sawLive = false

    private val _videoFinished = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A replay or highlight from Sports played to its end (full screen goes back where it was opened). */
    val videoFinished: kotlinx.coroutines.flow.SharedFlow<Unit> = _videoFinished

    /**
     * True when what just ended is a sports addon replay or an official highlight that played
     * through: a fixed length, and never live. A live game that drops (even one whose provider
     * closes its list) keeps retrying as before.
     */
    /** A sports addon replay or official highlight (a fixed-length video, never live) is playing. */
    private fun playingClip(): Boolean {
        val p = _player ?: return false
        val ch = currentChannel ?: return false
        if (ch.sourceId != com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID &&
            ch.sourceId != com.nuvio.tv.livetv.sports.SportsAddonSource.SOURCE_ID) return false
        if (ch.sourceId == com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID) return true
        return !sawLive && !p.isCurrentMediaItemDynamic
    }

    /** The clip [setPictureHidden] paused, by channel key (it plays on when the picture is back). */
    @Volatile private var pausedWhileHidden: String? = null

    /**
     * The Sports page's video window has nowhere to go (Follow Teams & Leagues and the like): a
     * replay or highlight waits paused until it's back, rather than playing on unseen (some boxes
     * lost their picture altogether doing that). A live channel keeps playing; only its picture stops.
     */
    fun setPictureHidden(hidden: Boolean) {
        val p = _player ?: return
        if (hidden) {
            if (playingClip() && p.playWhenReady) {
                pausedWhileHidden = currentChannel?.key
                p.playWhenReady = false
            }
        } else {
            val key = pausedWhileHidden ?: return
            pausedWhileHidden = null
            if (currentChannel?.key == key) p.playWhenReady = true
        }
    }

    private fun clipFinished(): Boolean {
        val p = _player ?: return false
        val ch = currentChannel ?: return false
        // Official highlights and condensed games (ESPN, NHL, MLB, YouTube) are never live.
        if (ch.sourceId == com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID) return true
        if (ch.sourceId != com.nuvio.tv.livetv.sports.SportsAddonSource.SOURCE_ID) return false
        if (sawLive || p.isCurrentMediaItemDynamic) return false
        val d = p.duration
        return d != C.TIME_UNSET && d > 0
    }

    /** An official highlight failed in its last few seconds: it has finished, not dropped. */
    private fun clipFailedAtEnd(): Boolean {
        val p = _player ?: return false
        if (currentChannel?.sourceId != com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID) return false
        val d = p.duration
        return d != C.TIME_UNSET && d > 0 && p.currentPosition >= d - 3_000L
    }

    private fun finishClip() {
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Video ${currentChannel?.name} finished")
        _videoFinished.tryEmit(Unit)
        stop()
    }

    private fun updateOwnTimeline() {
        val p = _player
        val ch = currentChannel
        // The stand-in timeline the player shows before the stream answers always says "live";
        // only the real one counts (it made every replay and highlight look live, so they
        // reconnected at the end instead of finishing).
        if (p != null && p.isCurrentMediaItemDynamic && !currentWindowIsPlaceholder(p)) sawLive = true
        _ownTimeline.value = p != null &&
            (ch?.sourceId == com.nuvio.tv.livetv.sports.SportsAddonSource.SOURCE_ID ||
                ch?.sourceId == com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID) &&
            _state.value.catchupTitle == null && _shiftStartedAt.value == null && _recordingPlayback.value == null &&
            p.isCurrentMediaItemSeekable && p.duration != C.TIME_UNSET &&
            // Live: only with a real rewind window (a short one would run out while paused, so
            // those keep the pause buffer). A replay: any real length.
            p.duration >= (if (p.isCurrentMediaItemDynamic) OWN_LIVE_WINDOW_MS else OWN_MIN_MS)
    }

    private fun currentWindowIsPlaceholder(p: ExoPlayer): Boolean {
        val t = p.currentTimeline
        if (t.isEmpty) return true
        val i = p.currentMediaItemIndex.coerceIn(0, t.windowCount - 1)
        return t.getWindow(i, androidx.media3.common.Timeline.Window()).isPlaceholder
    }

    // ---------------------------------------------------------------- watching while recording

    private val _recordingPlayback = MutableStateFlow<java.io.File?>(null)
    /**
     * Non-null while the channel is playing from its own recording (Record while watching): the
     * recording uses the provider connection, and you watch from it, so one connection does
     * both. Pause and skipping back work as in any recording.
     */
    val recordingPlayback: StateFlow<java.io.File?> = _recordingPlayback.asStateFlow()
    /** True from pressing Record on the channel being watched until its recording starts playing. */
    private val _recordingStarting = MutableStateFlow(false)
    val recordingStarting: StateFlow<Boolean> = _recordingStarting.asStateFlow()

    /** Lets go of the provider connection, so a recording of this channel can use it. */
    fun handOverForRecording() {
        lineWaitJob?.cancel()
        val p = _player ?: return
        if (_shiftStartedAt.value != null) stopShift()
        reconnectJob?.cancel()
        p.stop()
    }

    /** The live link the player is using for [channel] (its own link if something else is on). */
    fun liveUrlFor(channel: LiveChannel): String =
        currentUrl?.takeIf { currentChannel?.key == channel.key && _state.value.catchupTitle == null } ?: channel.url

    /** The recording playing as a show of its own ([playSavedRecording]), or null. */
    @Volatile var savedRecordingId: String? = null
        private set
    /** That recording's playlist of pieces on this box (for its seek thumbnails), or null. */
    @Volatile var savedRecordingPlaylist: java.io.File? = null
        private set

    /**
     * Plays a recording from Recordings like a replay that's all on this box: the catch-up seek
     * bar (Up to seek, 30 s skips), starting [startAtMs] in. Nothing is asked of the provider.
     */
    fun playSavedRecording(channel: LiveChannel, playlist: java.io.File, title: String, recordingId: String, startAtMs: Long) {
        play(channel, overrideUrl = android.net.Uri.fromFile(playlist).toString(), catchupTitle = title, sourceLater = true)
        lineWaitJob?.cancel()
        val p = _player ?: return
        recordingReloads = 0
        recordingWatchFrom = -1L
        savedRecordingId = recordingId
        savedRecordingPlaylist = playlist
        replayLocal = true
        archiveStartPending = false
        leadSeekMs = 0L
        fallbackUrls.clear()
        p.setMediaSource(localRecordingSource(android.net.Uri.fromFile(playlist), 0L))
        p.prepare()
        p.playWhenReady = true
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"$title\": playing in the Live TV player" + if (startAtMs > 0) " from ${startAtMs / 1000} s" else "")
        shiftJob?.cancel()
        shiftJob = scope.launch {
            // Once its list is read, go to the spot asked for: one still being recorded opens at
            // its newest piece, like a live channel, even when asked for the start.
            var waited = 0
            while (waited < 8_000 && p.playbackState != Player.STATE_READY) { delay(100); waited += 100 }
            if (savedRecordingId == recordingId && kotlin.math.abs(p.currentPosition - startAtMs) > 3_000) p.seekTo(startAtMs)
        }
    }

    /** Reloads of the recording playing ([playSavedRecording]) since it last played smoothly. */
    private var recordingReloads = 0
    /** Where the recording was at the last stall check, and since when it hasn't moved (-1: not watching). */
    private var recordingWatchFrom = -1L
    private var recordingStillSince = 0L
    private var recordingSmoothSince = 0L

    /**
     * Opens the recording playing here again at [atMs] (after an error or a freeze, a VPN change
     * or Reload): through the same source as [playSavedRecording], never as a plain file.
     */
    private fun reloadSavedRecording(atMs: Long) {
        val p = _player ?: return
        val playlist = savedRecordingPlaylist ?: return
        val id = savedRecordingId ?: return
        reconnectJob?.cancel()
        _state.value = _state.value.copy(error = null, isBuffering = true)
        recordingWatchFrom = -1L
        recordingSmoothSince = 0L
        p.stop()
        p.setMediaSource(localRecordingSource(android.net.Uri.fromFile(playlist), 0L))
        p.prepare()
        p.playWhenReady = true
        shiftJob?.cancel()
        shiftJob = scope.launch {
            var waited = 0
            while (waited < 8_000 && p.playbackState != Player.STATE_READY) { delay(100); waited += 100 }
            if (savedRecordingId == id && atMs > 0 && kotlin.math.abs(p.currentPosition - atMs) > 3_000) p.seekTo(atMs)
        }
    }

    /** A recording that errored or froze: open it again from the same spot, a few times, before giving up. */
    private fun recoverSavedRecording(why: String) {
        val p = _player ?: return
        val at = p.currentPosition.coerceAtLeast(0L)
        if (recordingReloads >= MAX_RECORDING_RELOADS) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording: $why; still not playing after $recordingReloads reloads")
            recordingWatchFrom = -1L
            p.stop()
            _state.value = _state.value.copy(error = "This recording stopped playing", isBuffering = false)
            return
        }
        recordingReloads++
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording: $why; opening it again from ${at / 1000} s (try $recordingReloads)")
        _state.value = _state.value.copy(error = null, isBuffering = true)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(recordingReloads * 1_000L)
            if (savedRecordingId != null) reloadSavedRecording(at)
        }
    }

    /** The recording playing here: a freeze (no progress while it should play) reloads it. */
    private fun checkRecordingStall(p: ExoPlayer, now: Long) {
        if (!p.playWhenReady || pausedForBackground || _state.value.error != null ||
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED ||
            reconnectJob?.isActive == true
        ) { recordingWatchFrom = -1L; recordingSmoothSince = 0L; return }
        val pos = p.currentPosition
        if (recordingWatchFrom < 0 || pos != recordingWatchFrom) {
            recordingWatchFrom = pos
            recordingStillSince = now
            if (p.isPlaying) {
                if (recordingSmoothSince == 0L) recordingSmoothSince = now
                // A minute of smooth playing: any later trouble gets the full number of tries again.
                if (now - recordingSmoothSince > 60_000L) recordingReloads = 0
            }
            return
        }
        recordingSmoothSince = 0L
        if (now - recordingStillSince < RECORDING_STALL_MS) return
        recordingWatchFrom = -1L
        recoverSavedRecording("froze at ${pos / 1000} s")
    }

    /** Plays [playlist] (a recording that's still being made), a few seconds behind live. */
    fun playFromRecording(playlist: java.io.File, fromStart: Boolean = false) {
        lineWaitJob?.cancel()
        val p = _player ?: return
        _recordingPlayback.value = playlist
        shiftJob?.cancel()
        if (appInBackground) {
            _recordingStarting.value = false
            resumeAfterBackground = { playFromRecording(playlist, fromStart) }
            return
        }
        _recordingStarting.value = true
        shiftJob = scope.launch {
          try {
            var waited = 0
            // Wait until about 15 s are saved: playing right behind the newest piece stalls
            // whenever a piece comes in late (the freezes while watching a channel you record).
            fun savedSec(): Double = HlsPieces.savedSec(playlist)
            while (waited < 20_000 && !(playlist.exists() && playlist.length() > 0)) { delay(250); waited += 250 }
            while (!fromStart && waited < 20_000 && savedSec() < 15.0) { delay(250); waited += 250 }
            if (_recordingPlayback.value != playlist) return@launch
            if (!playlist.exists()) {
                // Nothing came in: back to the live stream.
                _recordingPlayback.value = null
                currentUrl = null
                resumeAfterRecordingFailure()
                return@launch
            }
            if (appInBackground) {
                resumeAfterBackground = { playFromRecording(playlist, fromStart) }
                return@launch
            }
            p.setMediaSource(localRecordingSource(android.net.Uri.fromFile(playlist), 15_000))
            p.prepare()
            p.playWhenReady = true
            if (fromStart) {
                // A growing playlist opens near its newest piece: once it's ready, go back to the start.
                var ready = 0
                while (ready < 8_000 && p.playbackState != androidx.media3.common.Player.STATE_READY) { delay(100); ready += 100 }
                if (_recordingPlayback.value == playlist) p.seekTo(0L)
            }
          } finally {
            _recordingStarting.value = false
          }
        }
    }

    /** No longer watching from a recording that's being made (the recording itself carries on). */
    private fun stopWatchingRecording() {
        if (_recordingPlayback.value != null) { _recordingPlayback.value = null; shiftJob?.cancel() }
    }

    /** A failed commit must not restart sound after the app was backgrounded during IO. */
    fun resumeAfterRecordingFailure() {
        if (appInBackground) resumeAfterBackground = { retry() } else retry()
    }

    /** Watching a recording that ended (or failed): carry on with the channel live. */
    private fun recordingPlaybackEnded() {
        _recordingPlayback.value = null
        val ch = currentChannel ?: return
        currentUrl = null
        play(ch)
    }

    /**
     * Pause on a live channel without catch-up: record it from here (pause and rewind live TV),
     * so play carries on from this moment. Falls back to a plain pause if recording isn't
     * possible (turned off, not enough storage, encrypted stream).
     */
    fun pauseLive() {
        val p = _player ?: return
        if (_recordingPlayback.value != null) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: paused a saved recording"); p.pause(); return }
        val ch = currentChannel
        if (_shiftStartedAt.value != null) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: paused again inside the buffer"); p.pause(); return }
        if (ch == null || _state.value.catchupTitle != null || stalkerMimeType == StalkerMimeProbe.DASH || !timeshiftEnabled || !timeshift.hasRoomFor(timeshiftMinutes)) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: plain pause, no recording (channel=${ch != null}, catchup=${_state.value.catchupTitle != null}, setting on=$timeshiftEnabled, room=${timeshift.hasRoomFor(timeshiftMinutes)})")
            p.pause(); return
        }
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: pausing; recording started")
        val url = currentUrl ?: ch.url
        p.pause()
        // HLS: save from where you paused (how far behind live the player was), in the language
        // you're listening to if the sound comes separately.
        val behindLive = runCatching { p.currentLiveOffset }.getOrNull()?.takeIf { it != C.TIME_UNSET && it > 0 }
        val audioLanguage = runCatching {
            p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        }.getOrNull()
        p.stop() // hand the provider connection over to the recording
        val session = timeshift.start(url, ch.headers, timeshiftMinutes, leadMs = behindLive, audioLanguage = audioLanguage, refreshStream = bufferResolver(ch), policy = stalkerMediaPolicy, mimeType = stalkerMimeType)
        if (session == null) {
            // Couldn't record: back to the live stream, paused.
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: couldn't start recording (no room, or provider refused); paused live instead")
            p.setMediaSource(buildMediaSource(url, ch.headers, isLive = true)); p.prepare(); p.playWhenReady = false
            return
        }
        shiftPrepared = false
        _shiftStartedAt.value = session.startedAtMs
        reconnectJob?.cancel()
    }

    /** Play from the recording (first time: once there's something recorded to play). */
    private fun resumeShift() {
        val p = _player ?: return
        val session = timeshift.session
        if (session == null) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: play pressed but there is no recording session"); return }
        if (shiftPrepared) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: play pressed (playing from the buffer)"); p.play(); return }
        shiftJob?.cancel()
        shiftJob = scope.launch {
            // Wait until the first part is saved (a few seconds). Never left stuck: if the
            // channel couldn't be recorded (the provider refused, the recording stopped), it goes
            // back to live and says so, instead of staying paused.
            _state.value = _state.value.copy(isBuffering = true)
            var waited = 0
            val f = java.io.File(session.playUri.path ?: "")
            // Up to 25 s: a provider that allows one connection can take a while to let go of
            // the one the player had, and the recording keeps trying meanwhile.
            while (waited < 25_000) {
                if (f.exists() && f.length() > 0) break
                if (!timeshift.isRecording && waited > 2_000) break
                delay(250); waited += 250
            }
            if (!(f.exists() && f.length() > 0)) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: nothing recorded after ${waited / 1000}s; back to live")
                com.nuvio.tv.livetv.record.LiveTvNotice.show("This channel couldn't be recorded to pause it, so it's back to live")
                goLive()
                return@launch
            }
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: first piece saved after ${waited / 1000.0}s; playing from the pause point")
            // Both kinds are a local playlist of pieces now (TS streams are cut up as recorded).
            val source = localRecordingSource(session.playUri)
            // Start where you paused: the recording began the instant you paused, so its very
            // start (0) is where you paused, near enough. The start position is given with the
            // source itself (a seek to 0 made after the player has already read the playlist
            // would be ignored, and a growing playlist would otherwise start near its end) —
            // and it has to be 0 exactly: an offset guessed from the provider's own look-back
            // window could land outside what the player considers the valid live window and
            // get rejected, which is what made resuming slow and land back near live instead of
            // where you paused.
            // A start of 0 given with the source is read as "no start given" and a growing
            // playlist then begins at its newest end (live). So it's set again as a real seek
            // as soon as the stream's own timeline arrives (onTimelineChanged).
            shiftStartPending = true
            p.setMediaSource(source, 0L)
            p.prepare()
            p.playWhenReady = true
            shiftPrepared = true
        }
    }

    /**
     * A local recording (a playlist of pieces) to play. Played at normal speed throughout: as a
     * growing "live" playlist the player would otherwise speed up a little to catch up with the
     * end of it, creeping towards live.
     */
    internal fun localRecordingSource(uri: android.net.Uri, offsetMs: Long = 8_000): androidx.media3.exoplayer.source.MediaSource {
        adaptiveBuffer.sourceChanged()
        val item = MediaItem.Builder()
            // A network folder recording's pieces leave this box once copied: played through the
            // recordings server, which fetches those from the network folder.
            .setUri(com.nuvio.tv.livetv.record.NetworkFolderStore.playUriFor(uri) ?: uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                // "Live" here means a few seconds behind the newest piece (not 3 target lengths).
                MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
                    .setTargetOffsetMs(offsetMs).build()
            )
            .build()
        // Closed captions are inside the recorded video too: look for them as for the live stream
        // (without this, full screen with Rewind Anytime lost the captions).
        return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context))
            .setExtractorFactory(
                LiveCaptions.Hls(
                    androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                        /* exposeCea608WhenMissingDeclarations = */ true
                    )
                )
            )
            .createMediaSource(item)
    }

    /** Skip back or forward in the recording. */
    fun shiftSeekBy(deltaMs: Long) {
        val p = _player ?: return
        if (!shiftCanSeek()) return
        val max = if (p.duration > 0) p.duration - 3_000 else p.currentPosition
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, max.coerceAtLeast(0L)))
    }

    /** Go live: drop the recording and play the channel live again. */
    fun goLive() {
        // Back to the newest part of what's playing, which stays: a sports addon game's own
        // rewind window, Rewind Anytime's buffer (it keeps your rewind history), or a recording
        // of this channel being watched (live, a few seconds behind, on the recording's one
        // connection).
        if (_ownTimeline.value || (alwaysBuffer && _shiftStartedAt.value != null && shiftPrepared) ||
            _recordingPlayback.value != null
        ) {
            _player?.seekToDefaultPosition(); _player?.play(); return
        }
        val ch = currentChannel
        val url = currentUrl
        stopShift()
        if (ch != null) {
            currentUrl = null
            play(ch, overrideUrl = url.takeIf { it != ch.url })
        }
    }

    private fun stopShift() {
        alwaysBuffer = false
        shiftStartPending = false
        shiftReloads = 0
        shiftJob?.cancel()
        shiftJob = null
        if (_shiftStartedAt.value != null || timeshift.session != null) timeshift.stop()
        _shiftStartedAt.value = null
        shiftPrepared = false
    }

    /** The channel the player is on (as it was when playback started). */
    val playingChannel: LiveChannel? get() = currentChannel
    private var currentUrl: String? = null
    /** The link playing now (a backup or catch-up link when one is in use), or null. */
    val playingUrl: String? get() = currentUrl
    private var attachCount = 0
    private var releaseJob: Job? = null
    private var reconnectJob: Job? = null
    var autoReconnect: Boolean = true

    /**
     * The video's frame rate as measured from the frames themselves (for Match Frame Rate).
     * Many live streams don't state their frame rate, so the player's own value is often
     * missing and the display was never switched.
     */
    private val frameRateEstimator = LiveFrameRateEstimator()
    val measuredFps: Float get() = frameRateEstimator.framesPerSecond
    fun frameRateDiagnostics(): String = frameRateEstimator.diagnostics()
    private val frameListener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        frameRateEstimator.onFrame(presentationTimeUs)
    }

    /** A new channel: measure its frame rate afresh. */
    private fun resetFrameRate() {
        frameRateEstimator.reset()
    }

    /** Live TV buffer size ("none", "small", "normal", "large", "xlarge"); changing it rebuilds the player. */
    var bufferSize: String = "normal"
        set(value) {
            if (field == value) return
            field = value
            rebuildPlayer()
        }

    /**
     * Audio passthrough. Off: Dolby / DTS audio is decoded by the app and sent as plain PCM,
     * which is far more robust with Alexa Home Theater / Echo speakers on Fire TV. Changing it
     * rebuilds the player (and carries on with the same channel).
     */
    var audioPassthrough: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuildPlayer()
        }

    /** A setting the player is built with changed: built again, carrying on with what was playing. */
    private fun rebuildPlayer() {
        val p = _player ?: return
        val ch = currentChannel
        val url = currentUrl
        val title = _state.value.catchupTitle
        val providerRef = activeStalkerRef
        val wasPlaying = p.playWhenReady
        release()
        if (ch != null && wasPlaying) play(ch, overrideUrl = url.takeIf { it != ch.url }, catchupTitle = title, stalkerStream = providerRef)
    }

    @Volatile private var watchingFullscreen = false

    fun attach(): ExoPlayer {
        releaseJob?.cancel()
        attachCount++
        if (!watchingFullscreen) { watchingFullscreen = true; watchFullscreen() }
        return _player ?: buildPlayer().also { _player = it; adaptiveBuffer.attach(it) }
    }

    /** Releases the player shortly after the last screen detaches (lets route changes hand it over). */
    fun detach() {
        attachCount = (attachCount - 1).coerceAtLeast(0)
        if (attachCount == 0) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(600)
                if (attachCount == 0) release()
            }
        }
    }

    /** Other links to try, in turn, if the current one can't be played (catch-up variants). */
    private val fallbackUrls = ArrayDeque<String>()
    /** The catch-up now playing has actually started (pictures moving), at least once. */
    @Volatile private var archiveStarted = false

    fun play(
        channel: LiveChannel,
        overrideUrl: String? = null,
        catchupTitle: String? = null,
        fallback: String? = null,
        fallbacks: List<String> = emptyList(),
        catchupStartMs: Long? = null,
        catchupLeadMs: Long = 0L,
        catchupPrimaryUrl: String? = null,
        catchupExpectWallMs: Long? = null,
        stalkerStream: StalkerStreamRef? = null,
        reconnectAttempt: Int = 0,
        /** Start the link again even when it's the one playing (a skip in a replay, which then goes to its new point). */
        forceReload: Boolean = false,
        /**
         * Plays once in; false: it's there paused, the controls showing it so (a paused replay
         * picked up again after the background). Play/Pause and Home while a Stalker link is
         * obtained still change it. A skip in a paused replay stays paused anyway (see [playResolved]).
         */
        plays: Boolean = true,
        /** The caller sets the player's source itself right after (a recording from Recordings). */
        sourceLater: Boolean = false,
    ) {
        if (currentChannel?.key != channel.key) adaptiveBuffer.sourceChanged(newChannel = true)
        if (stalkerStream == null && overrideUrl == null && catchupTitle == null && channel.stalkerRef != null &&
            currentChannel?.key == channel.key && activeStalkerRef == channel.stalkerRef && _state.value.error == null &&
            (stalkerResolveJob?.isActive == true || _player?.playbackState?.let { it != Player.STATE_IDLE } == true)
        ) {
            if (stalkerResolveJob?.isActive != true && _player?.isPlaying == false) _player?.play()
            return
        }
        val previousChannelKey = currentChannel?.key
        val ref = stalkerStream ?: when {
            catchupTitle == null && overrideUrl?.startsWith("file:") != true && overrideUrl?.contains("127.0.0.1") != true -> channel.stalkerRef
            catchupTitle != null && channel.key == currentChannel?.key && overrideUrl == currentUrl -> activeStalkerRef
            else -> null
        }
        stalkerResolveJob?.cancel()
        val generation = ++stalkerGeneration
        activeStalkerRef = ref
        stalkerMediaPolicy?.retire()
        stalkerMediaPolicy = null
        stalkerMimeType = null
        if (ref == null) {
            playResolved(channel, overrideUrl, catchupTitle, fallback, fallbacks, catchupStartMs,
                catchupLeadMs, catchupPrimaryUrl, catchupExpectWallMs, forceReload = forceReload, plays = plays, sourceLater = sourceLater)
            return
        }
        // Let go of the previous provider connection while the new temporary link is obtained.
        reconnectJob?.cancel()
        stopReplayCache()
        stopShift()
        _player?.stop()
        currentChannel = channel
        currentUrl = null
        // Paused once in when asked so, or for a skip asked of the provider in a paused replay
        // (read before the state below starts afresh).
        val wants = plays && !(forceReload && catchupTitle != null && replayPausedNow())
        // Where this request starts, should Home let go of it (or it fail) before its link comes in.
        fetchStartMs = catchupStartMs
        fetchLeadMs = catchupLeadMs
        // Not started yet: what the replay before it did doesn't count (see scheduleReconnect).
        archiveStarted = false
        _state.value = LivePlaybackState(channelKey = channel.key, isBuffering = true, catchupTitle = catchupTitle, userPaused = !wants)
            .copy(reconnectAttempt = reconnectAttempt)
        // While the link is obtained, the stopped player says whether it plays once in, as it does
        // while a replay's copy is on its way: Play/Pause and Home meanwhile go by it, and the link
        // is played as it says then (below). Before, a paused replay picked up again after the
        // background was set paused only once this had returned, and its Stalker link, in later,
        // started it playing for a moment.
        _player?.playWhenReady = wants
        _requests.value = _requests.value + 1
        stalkerResolveJob = scope.launch {
            try {
                val resolved = stalker.prepare(channel.sourceId, ref)
                if (generation != stalkerGeneration) { resolved.policy.retire(); return@launch }
                stalkerMediaPolicy = resolved.policy
                stalkerMimeType = resolved.mimeType
                playResolved(channel.copy(headers = resolved.headers), resolved.url, catchupTitle,
                    fallback = fallback, fallbacks = fallbacks,
                    catchupStartMs = catchupStartMs, catchupLeadMs = catchupLeadMs,
                    catchupPrimaryUrl = catchupPrimaryUrl, catchupExpectWallMs = catchupExpectWallMs,
                    previousChannelKey = previousChannelKey, reconnectAttempt = reconnectAttempt, forceReload = forceReload,
                    // As the stopped player says now (see above), not as first asked.
                    plays = if (_player != null) wantsToPlay() else wants)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != stalkerGeneration) return@launch
                scheduleReconnect(StalkerGateway.failureMessage(failure))
            }
        }
    }

    private fun playResolved(
        channel: LiveChannel,
        overrideUrl: String? = null,
        catchupTitle: String? = null,
        fallback: String? = null,
        fallbacks: List<String> = emptyList(),
        /** Catch-up: the real time the replay should start at (the show's start). */
        catchupStartMs: Long? = null,
        /** Catch-up: how far the replay begins before [catchupStartMs] (whole-minute links); skipped once it's in. */
        catchupLeadMs: Long = 0L,
        /** Catch-up: the link the app built first (the others are other kinds of the same request). */
        catchupPrimaryUrl: String? = null,
        /** Catch-up: the real time the link asks for, to check against the stream's own clock (null: don't check). */
        catchupExpectWallMs: Long? = null,
        previousChannelKey: String? = currentChannel?.key,
        reconnectAttempt: Int = 0,
        forceReload: Boolean = false,
        /** Plays once in (false: there paused, see [play]). */
        plays: Boolean = true,
        sourceLater: Boolean = false,
    ) {
        // Something chosen to play (back from the background through a launcher row, say):
        // that wins over picking up what was on before.
        resumeAfterBackground = null
        // Picked by you (not a switch to the next channel for a game): start the game's list afresh.
        if (!switchingForGame) gameTried.clear()
        if (previousChannelKey != channel.key) recentStalls.clear()
        shiftStartPending = false
        savedRecordingId = null
        savedRecordingPlaylist = null
        _ownTimeline.value = false
        if (_shiftStartedAt.value != null) stopShift()
        stopWatchingRecording()
        archiveStartWallMs = if (catchupTitle != null) catchupStartMs else null
        linkStartWallMs = if (catchupTitle != null && catchupStartMs != null) catchupStartMs - catchupLeadMs.coerceAtLeast(0L) else null
        archiveWallMapped = false
        archiveExpectWallMs = if (catchupTitle != null) catchupExpectWallMs else null
        wrongShowSkips = 0
        wallCheckedFor = null
        if (previousChannelKey != channel.key) {
            // A new channel starts on the hardware decoder again.
            decoderFallback.reset()
            neverStartedCount = 0
            sawLive = false
            audioHandled = false
            audioPrefPending = true
            captionsHandled = false
            resetFrameRate()
            LiveCaptions.reset()
            captionWatch?.cancel()
        }
        // A channel whose HLS playlist couldn't be read earlier: its TS link instead.
        val url = overrideUrl ?: channel.url.let { u -> if (channel.key in tsInstead) tsLinkFor(u) ?: u else u }
        if (previousChannelKey != channel.key || currentUrl != url) {
            forcedMime = stalkerMimeType
            formatGuess = 0
            // A new stream (catch-up, another link): ask for the saved audio language again.
            audioPrefPending = true
        }
        // A channel whose "HLS" turned out to be plain TS: read it as TS from the start.
        if (catchupTitle == null && channel.key in plainTs && overrideUrl == null) forcedMime = MimeTypes.VIDEO_MP2T
        fallbackUrls.clear()
        hlsCatchupFailedOn = null
        archiveStarted = false
        fallback?.let { fallbackUrls += it }
        fallbacks.forEach { if (it !in fallbackUrls) fallbackUrls += it }
        if (catchupTitle != null) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up \"$catchupTitle\" on ${channel.name}: ${redact(url)}")
        startStallWatch()
        val p = _player ?: attach().also { attachCount-- }
        // Captions follow the setting until you change them yourself on this channel. Without
        // this the player switched on captions a stream marks as "default" (ARD, ZDF…) a couple
        // of seconds in, even with captions off in Settings.
        // Audio picked before on this channel: ask for that language from the start (catch-up
        // and reconnects included); without one, the stream's own default as before.
        if (audioPrefPending) {
            audioPrefPending = false
            val lang = channelAudio[channel.key]?.split('|')?.getOrNull(0)?.ifBlank { null }
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(lang)
                .build()
        }
        if (!captionsHandled) {
            // A choice you made on this channel (on or off) wins over the setting.
            val textOff = channelCaptions[channel.key]?.let { it.startsWith("off") } ?: !captionsByDefault
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textOff)
                .build()
        }
        // The same link already playing, or its copy on its way (the player is stopped until it
        // plays from the copy): it carries on. Picking the replay again meanwhile threw the copy
        // away and asked the provider again.
        val copyOnItsWay = copyOnItsWay()
        if (!forceReload && currentChannel?.key == channel.key && currentUrl == url && _state.value.error == null &&
            (p.playbackState != Player.STATE_IDLE || copyOnItsWay)
        ) {
            // Picked again: it plays (once its copy is in), as a pick of a paused replay does.
            if (copyOnItsWay) {
                copyPlays = true
                if (backgroundJob?.isActive != true) p.playWhenReady = true
            } else if (!p.isPlaying) {
                p.play()
                copyPlays = true
            }
            return
        }
        reconnectJob?.cancel()
        // A skip that asks the provider again in a paused replay stays paused once in, from its
        // copy or straight from the provider, and the controls say so. Before, it started playing.
        // A replay picked from the guide, live channels, and a replay asked again where its window
        // ran out (it was playing) play as before.
        val playsNow = plays && !(forceReload && catchupTitle != null && replayPausedNow())
        // A new replay or channel (not the same one asked again from a point): its copy is
        // watched for running dry afresh (see copyStarved).
        if (!forceReload || catchupTitle == null) starvation.reset()
        copyEndedSince = 0L
        farLeadDirect = false
        // A new request (not a skip, a restart or a reconnect): an earlier failure doesn't carry over.
        if (!forceReload && reconnectAttempt == 0) farDirectFailed = false
        leadSeekMs = if (catchupTitle != null) catchupLeadMs.coerceAtLeast(0) else 0L
        leadSeekUntil = 0L
        catchupPrimary = if (catchupTitle != null) (catchupPrimaryUrl ?: overrideUrl) else null
        catchupAskedAt = android.os.SystemClock.elapsedRealtime()
        catchupLinkReported = false
        stopReplayCache()
        currentChannel = channel
        LiveWatching.sourceId = channel.sourceId
        currentUrl = url
        // Paused from the start when it's to stay paused, the controls included: the player
        // doesn't say so again when it was already paused.
        copyPlays = playsNow
        _state.value = LivePlaybackState(channelKey = channel.key, isBuffering = true, catchupTitle = catchupTitle, userPaused = !playsNow)
            .copy(reconnectAttempt = reconnectAttempt)
        _requests.value = _requests.value + 1
        if (catchupTitle == null) markStarting(channel.key)
        if (bufferFailedKey != null && bufferFailedKey != channel.key) bufferFailedKey = null
        // Rewind anytime (full screen): play through the rolling buffer instead.
        if (shouldBuffer(channel, catchupTitle)) {
            p.stop()
            if (startAlwaysBuffer(channel, url)) { p.playWhenReady = true; return }
        }
        // A TS catch-up replay: saved while it plays, for instant skipping.
        if (catchupTitle != null && catchupCache && channel.drm == null &&
            stalkerMimeType != StalkerMimeProbe.HLS && stalkerMimeType != StalkerMimeProbe.DASH &&
            !url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) && startReplayCache(channel, url, playsNow)
        ) return
        // A recording from Recordings: its own source comes next. Starting it here as a plain
        // file first could fail on a recording joined into one file (its pieces aren't there).
        if (sourceLater) return
        archiveStartPending = catchupTitle != null
        // The stopped player says whether it plays once in (Play/Pause meanwhile go by it).
        if (backgroundJob?.isActive != true) p.playWhenReady = playsNow
        // Once the replay's copy (or the pause buffer) has let go of its connection.
        openWhenLineFree(p) { afterWait ->
            p.setMediaSource(buildMediaSource(url, channel.headers, isLive = catchupTitle == null))
            p.prepare()
            // As asked, or as Play/Pause set it while the line was let go.
            val plays = if (catchupTitle != null) copyPlays else playsNow
            if (backgroundJob?.isActive == true && (catchupTitle != null || afterWait)) {
                // A replay whose link came in just after Home (or anything opened after waiting
                // for the line): it stays paused (it played behind the launcher until the line
                // was let go), and plays on coming back if it was to.
                if (plays) pausedForBackground = true
                p.playWhenReady = false
            } else {
                p.playWhenReady = plays
            }
        }
    }

    /**
     * Catch-up just started: begin at the start of what we asked for. Some providers send a
     * show that's still airing as a growing "live" stream, and the player starts those at the
     * live edge (so "Watch from start" landed near now, and you had to rewind by hand).
     */
    @Volatile private var archiveStartPending = false

    /** The real time the replay should start at, when known. */
    @Volatile private var archiveStartWallMs: Long? = null

    /** The time the catch-up link asked for, to compare with the stream's own clock; null = no check. */
    @Volatile private var archiveExpectWallMs: Long? = null
    private var wrongShowSkips = 0
    private var wallCheckedFor: String? = null

    /**
     * Some providers answer a catch-up link written in the wrong clock with a different show
     * (hours earlier or later), and nothing says so. When the stream carries its own clock and
     * it doesn't cover the time asked for, the link is dropped and the next one with a
     * different time is tried. With no other time to try, the replay carries on as before.
     */
    private fun checkCatchupClock(timeline: androidx.media3.common.Timeline) {
        val expect = archiveExpectWallMs ?: return
        val url = currentUrl ?: return
        if (_state.value.catchupTitle == null || wallCheckedFor == url) return
        val p = _player ?: return
        val w = windowOf(p, timeline) ?: return
        if (w.windowStartTimeMs == C.TIME_UNSET) return
        wallCheckedFor = url
        val from = w.windowStartTimeMs
        val to = if (w.durationMs != C.TIME_UNSET && w.durationMs > 0) from + w.durationMs else System.currentTimeMillis()
        if (expect >= from - WRONG_SHOW_SLACK_MS && expect <= to + WRONG_SHOW_SLACK_MS) return
        val minutesOff = (from - expect) / 60_000
        val stamp = com.nuvio.tv.livetv.data.CatchupUrlBuilder.askedStamp(url)
        val other = fallbackUrls.filter { com.nuvio.tv.livetv.data.CatchupUrlBuilder.askedStamp(it) != stamp }
        val ch = currentChannel
        if (ch == null || wrongShowSkips >= 2 || other.isEmpty()) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Catch-up: the stream's clock is $minutesOff min away from the show asked for; no other time to try, playing it"
            )
            return
        }
        wrongShowSkips++
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Catch-up: the provider sent a show $minutesOff min away from the one asked for; trying ${redact(other.first())}"
        )
        scope.launch {
            if (currentUrl != url) return@launch
            val pl = _player ?: return@launch
            fallbackUrls.clear()
            fallbackUrls.addAll(other)
            val fb = fallbackUrls.removeFirst()
            pl.stop()
            playCatchupLink(pl, ch, fb)
        }
    }

    /**
     * The provider sent its live stream with a long rewind window instead of the show itself:
     * positions are then worked out from the stream's own clock (see [archiveOffsetMs]).
     */
    @Volatile var archiveWallMapped = false
        private set

    /** The stream's clock time at the current position, when the stream carries one. */
    private fun streamWallMs(p: ExoPlayer): Long? =
        windowOf(p)?.windowStartTimeMs?.takeIf { it != C.TIME_UNSET }?.let { it + p.currentPosition }

    /**
     * Time into the show that started at [programStartMs], for a replay that came as the live
     * stream's rewind window. Null for ordinary replays (there the position is the time).
     */
    fun archiveOffsetMs(programStartMs: Long): Long? {
        if (!archiveWallMapped) return null
        val p = _player ?: return null
        return streamWallMs(p)?.let { it - programStartMs }
    }

    /**
     * Where in the stream to seek such a replay to be [offsetMs] into the show that started at
     * [programStartMs]; null if it can't be done in the stream.
     */
    fun archiveSeekTarget(programStartMs: Long, offsetMs: Long): Long? {
        if (!archiveWallMapped) return null
        val p = _player ?: return null
        val wall = streamWallMs(p) ?: return null
        return (p.currentPosition + (programStartMs + offsetMs - wall)).takeIf { it >= 0 }
    }

    /** Plain words for playback errors, with the provider's HTTP status when there is one. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    // One text per code, so it's shown in the device's language (see LiveTvTexts).
                    // Most often the account's connection limit: another device or app watching,
                    // or the provider still counting a connection just let go of.
                    401 -> "Provider refused the stream (HTTP 401): too many connections on this account, or it's expired or blocked"
                    403 -> "Provider refused the stream (HTTP 403): too many connections on this account, or it's expired or blocked"
                    404 -> "Stream not found (HTTP 404). Try another stream format in the playlist's settings"
                    429, 456, 458, 509 -> "Too many connections on this account (HTTP ${cause.responseCode})"
                    in 500..599 -> com.nuvio.tv.livetv.ui.LiveTvTexts.trf("Provider's server error (HTTP {0})", cause.responseCode)
                    else -> com.nuvio.tv.livetv.ui.LiveTvTexts.trf("Provider answered HTTP {0}", cause.responseCode)
                }
            }
            cause = cause.cause
        }
        // Plain words on screen, saying whose end it is ("Parsing manifest malformed" made people
        // think the app was broken). The exact error still goes in the Start-Up Report.
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "The provider isn't sending this channel properly right now"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Can't reach the provider right now"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The provider doesn't have this channel's stream right now"
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                "This channel is protected and couldn't be unlocked"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "This TV couldn't play this channel's picture or sound"
            else -> "This channel isn't playing right now"
        }
    }

    // ---------------------------------------------------------------- sleep timer

    private val _sleepAtMs = MutableStateFlow<Long?>(null)
    /** When the sleep timer stops Live TV (null = off). Runs wherever you are in the app. */
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()
    private val _sleepFired = MutableStateFlow(0)
    /** Goes up each time the sleep timer stops playback (full screen closes itself). */
    val sleepFired: StateFlow<Int> = _sleepFired.asStateFlow()
    private var sleepJob: Job? = null

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleepAtMs.value = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAtMs.value = at
        sleepJob = scope.launch {
            delay(at - System.currentTimeMillis())
            _sleepAtMs.value = null
            stop()
            _sleepFired.value++
        }
    }

    /** Lets go of the Stalker link in use, and of one still being obtained. */
    private fun dropStalker() {
        stalkerMediaPolicy?.retire()
        stalkerMediaPolicy = null
        stalkerMimeType = null
        stalkerResolveJob?.cancel()
        stalkerGeneration++
        activeStalkerRef = null
    }

    fun stop() {
        _stops.value = _stops.value + 1
        adaptiveBuffer.sourceChanged(newChannel = true)
        dropStalker()
        savedRecordingId = null
        savedRecordingPlaylist = null
        clearStarting()
        reconnectJob?.cancel()
        lineWaitJob?.cancel()
        stopReplayCache()
        // A pause buffer lets go of its provider connection too (a recording you're watching
        // from carries on; only watching it stops).
        if (_shiftStartedAt.value != null || timeshift.session != null) stopShift()
        stopWatchingRecording()
        _player?.stop()
        _player?.clearMediaItems()
        currentChannel = null
        LiveWatching.sourceId = null
        currentUrl = null
        _ownTimeline.value = false
        sawLive = false
        _state.value = LivePlaybackState()
    }

    // ---------------------------------------------------------------- stall watchdog

    /**
     * Some streams stop sending pictures without ever reporting an error (the provider keeps
     * the connection open, or a live HLS list stops updating), leaving "Loading…" on screen
     * forever. If playback makes no progress for [STALL_MS], reload the stream, the same as
     * switching away and back.
     */
    private var stallJob: Job? = null
    private var lastPosition = -1L
    private var lastProgressAt = 0L
    /** How much was loaded at the last check (to tell "slow to start" from "nothing coming in"). */
    private var lastBuffered = -1L

    private fun startStallWatch() {
        lastProgressAt = android.os.SystemClock.elapsedRealtime()
        lastPosition = -1L
        lastBuffered = -1L
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            while (true) {
                delay(2_000)
                checkStall()
            }
        }
    }

    /** Watches a replay's copy for running dry on a slow save (see [copyStarved]). */
    private val starvation = CopyStarvation()
    /** The copy [starvation] is measuring (a new one has its own amounts). */
    private var starvationCopy: java.io.File? = null
    /** Since when a replay's copy has sat at its end (see [copyRanOut]); 0: it hasn't, -1: asked again. */
    private var copyEndedSince = 0L

    /**
     * A replay playing from its copy caught up with the save, which comes in slower than it plays
     * (see [CopyStarvation]): asked again from where it is, as a skip there does (true if done).
     * Seen on PureTv: the picture froze, played a few seconds as each piece came in, and froze
     * again, and only a skip by hand got it going; a new request gets the provider's fast start.
     */
    private fun copyStarved(p: ExoPlayer, ch: LiveChannel, now: Long, pos: Long): Boolean {
        val copy = replayCache.session
        val saved = replayCache.savedMs
        // Not a portal (Stalker) replay: its link always starts at the show's start, so asking again
        // from a point far in plays it straight from the provider or from the start. Nor close to
        // live (a show still airing): a new request can't send faster than it airs.
        val nearLive = linkStartWallMs?.let { it + saved >= System.currentTimeMillis() - 120_000L } ?: true
        // Nor while the save drops what the provider sent again over a new connection (half a
        // minute at most, see LiveTvTimeshift.catchingUp): asking again there would be a cut.
        if (_state.value.catchupTitle == null || !replayLocal || copy == null || copy.kind != LiveTvTimeshift.Kind.TS ||
            !replayCache.isRecording || copyOnItsWay() || onCatchupStarved == null || activeStalkerRef != null || nearLive ||
            replayCache.catchingUp
        ) {
            starvation.notWatching()
            return false
        }
        if (starvationCopy != copy.dir) { starvationCopy = copy.dir; starvation.newCopy() }
        val waiting = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
        return when (starvation.check(now, saved, pos, waiting)) {
            CopyStarvation.Verdict.WAIT -> false
            CopyStarvation.Verdict.GIVE_UP -> {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "Catch-up on ${ch.name}: asking the provider again didn't help either (it sends slower than the replay plays from the start); waiting for the save instead"
                )
                onCatchupSlow?.invoke()
                false
            }
            CopyStarvation.Verdict.ASK_AGAIN -> {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "Catch-up on ${ch.name}: playback caught up with the save (${saved / 1000} s saved, " +
                        "${String.format(java.util.Locale.US, "%.2f", starvation.askedAtRate)}x real time lately); " +
                        "asking the provider again from this point, as a skip there does"
                )
                lastProgressAt = now
                // A lighter copy of the channel offered meanwhile (the view model's own call).
                onCatchupSlow?.invoke()
                // The copy that comes next starts where it froze (see copyKeepsPoint).
                keepPointUntil = now + 15_000L
                if (onCatchupStarved?.invoke() == true) return true
                // Not asked (a skip of yours on its way, say): yours goes on, and this doesn't count.
                keepPointUntil = 0L
                starvation.declined()
                false
            }
        }
    }

    /**
     * A replay's copy played to the end of what was saved (the save stopped early: the provider
     * ended the stream and can't carry it on, see [LiveTvTimeshift]) and nothing carried it on:
     * asked again from there (true if done). Full screen does that within half a second (the
     * view model's followCatchup); this is for the guide's preview window, and for the end of a
     * copy where the guide has no show, which were left on their last picture until a skip.
     */
    private fun copyRanOut(p: ExoPlayer, ch: LiveChannel, now: Long): Boolean {
        if (p.playbackState != Player.STATE_ENDED || _state.value.catchupTitle == null || !replayLocal ||
            replayCache.isRecording || !p.playWhenReady || pausedForBackground || _state.value.error != null || onCatchupEnded == null
        ) {
            copyEndedSince = 0L
            return false
        }
        if (copyEndedSince == 0L) { copyEndedSince = now; return false }
        if (copyEndedSince < 0L || now - copyEndedSince < 3_000L) return false
        copyEndedSince = -1L
        lastProgressAt = now
        keepPointUntil = now + 15_000L
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up on ${ch.name}: its copy played to the end of what was saved (${replayCache.savedMs / 1000} s)")
        return onCatchupEnded?.invoke() == true
    }

    private fun checkStall() {
        val p = _player ?: return
        if (savedRecordingId != null) {
            lastProgressAt = android.os.SystemClock.elapsedRealtime()
            checkRecordingStall(p, lastProgressAt)
            return
        }
        if (_shiftStartedAt.value != null || _recordingPlayback.value != null) { lastProgressAt = android.os.SystemClock.elapsedRealtime(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        val ch = currentChannel
        if (ch != null && copyRanOut(p, ch, now)) return
        // A replay asked for while paused (a skip that asks the provider again) and not in yet, with
        // no copy and no picture: still watched, so a link that sends nothing moves on to the next
        // one, still paused. Left alone, it sat on "Loading…" until Play was pressed. Ready to play
        // counts as in.
        val pausedLoading = !p.playWhenReady && _state.value.catchupTitle != null && !archiveStarted &&
            !_state.value.started && !replayLocal
        // Paused, in the background, nothing playing, or already showing an error: not a stall.
        if (ch == null || (!p.playWhenReady && !pausedLoading) || pausedForBackground || _state.value.error != null ||
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
        ) {
            lastProgressAt = now
            lastPosition = p.currentPosition
            starvation.notWatching()
            return
        }
        val pos = p.currentPosition
        val moving = p.playbackState == Player.STATE_READY && ((p.isPlaying && pos != lastPosition) || pausedLoading)
        lastPosition = pos
        // Still loading, with more of the stream arriving since the last check.
        val buffered = p.bufferedPosition
        val stillLoading = p.playbackState == Player.STATE_BUFFERING && lastBuffered >= 0 && buffered > lastBuffered
        lastBuffered = buffered
        if (copyStarved(p, ch, now, pos)) return
        if (moving) {
            lastProgressAt = now
            if (_state.value.catchupTitle != null) { archiveStarted = true; catchupRestarts = 0; farDirectFailed = false }
            return
        }
        // A Dispatcharr channel that's slow to load but still coming in gets extra time before
        // it's reloaded: a reload drops the connection, and Dispatcharr can then move to the
        // channel's next, lower-quality stream. Other channels reload on time, as before.
        if (stillLoading && slowStarter() && now - lastProgressAt < STALL_MS + SLOW_START_EXTRA_MS) return
        // An HLS catch-up link that hasn't shown anything yet, with the TS link next: providers
        // that don't do HLS replays answer it with nothing at all, and waiting the full 15 s
        // for that was most of the start (seen: 16 s lost, then the TS link played in 5 s).
        val hlsFirstTry = _state.value.catchupTitle != null && !archiveStarted &&
            isHlsReplayLink(currentUrl) && isTsReplayLink(fallbackUrls.firstOrNull())
        if (now - lastProgressAt < (if (hlsFirstTry) HLS_REPLAY_WAIT_MS else STALL_MS) || !autoReconnect) return
        lastProgressAt = now
        val archive = _state.value.catchupTitle != null
        // A catch-up that never started (stuck on "Loading…" with no error): some panels answer
        // a form of catch-up link they don't really support with an empty stream instead of an
        // error, so the other link forms were never tried. Move on to the next one; when none
        // are left, say so instead of loading forever.
        if (archive && !archiveStarted) {
            val fb = fallbackUrls.removeFirstOrNull()
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Catch-up didn't start on ${ch.name} (nothing came in)" + (fb?.let { "; trying ${redact(it)}" } ?: "")
            )
            p.stop()
            if (fb != null) {
                val wasHls = isHlsReplayLink(currentUrl)
                val toTs = isTsReplayLink(fb)
                hlsCatchupFailedOn = if (wasHls && toTs) ch.sourceId else null
                playCatchupLink(p, ch, fb)
            } else {
                scheduleReconnect("Catch-up didn't start: the provider sent nothing for this show")
            }
            return
        }
        // A replay played straight from the provider froze (often after a skip inside it, which
        // some providers can't do): reloading the same link just froze again every 16 s. Ask the
        // provider for the replay again from where it stopped; after 3 tries in a row, say so.
        // A replay playing from its copy on this box caught up with the part still coming in:
        // wait for it (it carries on by itself after a cut), as long as more keeps arriving.
        // Reloading here used to play the provider's link straight, with no copy, every 15 s.
        if (archive && replayLocal && replayCache.isRecording) {
            val saved = replayCache.savedMs
            if (saved != replayWaitSavedMs) { replayWaitSavedMs = saved; replayWaitSince = now }
            if (now - replayWaitSince < REPLAY_WAIT_MS) return
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up on ${ch.name}: nothing more came in to save for ${REPLAY_WAIT_MS / 1000}s")
        }
        // Also when it played from the copy: asking again from this point saves a new copy from
        // there (the copy that stopped can't go further).
        if (archive && onCatchupStalled != null) {
            if (catchupRestarts >= 3) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up on ${ch.name} kept freezing; stopped after 3 tries")
                catchupRestarts = 0
                p.stop()
                _state.value = _state.value.copy(error = "The provider stopped sending this show. Try again, or skip to another point", isBuffering = false)
                return
            }
            catchupRestarts++
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up on ${ch.name} froze; asking the provider again from this point (try $catchupRestarts)")
            // Stopped first, so asking again for the same link (a freeze in the replay's first
            // minute) really reloads it; the position is kept.
            p.stop()
            if (onCatchupStalled?.invoke() == true) return
        }
        Log.w(TAG, "Stream stalled on ${ch.name}; reloading")
        if (!archive && awaitingFrame) neverStartedCount++
        // A live game: a channel that won't start, or that froze twice in a few minutes, moves on
        // to the next channel showing the game (its other copies first). Other channels keep
        // reloading as before.
        if (!archive && !awaitingFrame) {
            recentStalls.addLast(now)
            while (recentStalls.isNotEmpty() && now - recentStalls.first() > FREEZE_WINDOW_MS) recentStalls.removeFirst()
        }
        if (!archive && ((awaitingFrame && neverStartedCount >= 2) || (!awaitingFrame && recentStalls.size >= 2)) &&
            tryGameFailover(ch, if (awaitingFrame) "never started" else "kept freezing")
        ) return
        if (!archive && awaitingFrame && neverStartedCount >= 2 && onLiveFailure?.invoke(ch.key) == true) return
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Channel ${ch.name} " + (if (awaitingFrame) "never started (nothing came in for ${STALL_MS / 1000}s)" else "stalled") +
                "; reloading · ${redact(currentUrl ?: ch.url)}"
        )
        val url = currentUrl ?: ch.url
        val resumeAt = if (archive) pos else ownReplayResumeAt(p)
        if (activeStalkerRef != null) {
            restartStalker(resumeAt)
            return
        }
        p.stop()
        archiveStartPending = false
        p.setMediaSource(buildMediaSource(url, ch.headers, isLive = !archive))
        p.prepare()
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.playWhenReady = true
    }

    /** Catch-up moved on to the next show: update the title shown. */
    fun setCatchupTitle(title: String) {
        if (_state.value.catchupTitle != null) _state.value = _state.value.copy(catchupTitle = title)
    }

    private fun restartStalker(positionMs: Long = 0L, startMs: Long? = archiveStartWallMs) {
        val channel = currentChannel ?: return
        val ref = activeStalkerRef ?: return
        play(channel, catchupTitle = _state.value.catchupTitle, catchupStartMs = startMs,
            catchupLeadMs = positionMs.coerceAtLeast(0L), stalkerStream = ref,
            reconnectAttempt = _state.value.reconnectAttempt,
            // A paused replay stays paused when its link is obtained again (after a failure, say);
            // live channels play as before.
            plays = _state.value.catchupTitle == null || wantsToPlay())
    }

    private fun bufferResolver(channel: LiveChannel): (suspend () -> StalkerPlayback)? =
        (activeStalkerRef ?: channel.stalkerRef)?.let { ref -> { stalker.prepare(channel.sourceId, ref) } }

    fun retry() {
        if (savedRecordingId != null) {
            recordingReloads = 0
            reloadSavedRecording(_player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            return
        }
        val ch = currentChannel ?: return
        if (activeStalkerRef != null) { restartStalker(); return }
        val url = currentUrl
        currentUrl = null
        play(ch, url, _state.value.catchupTitle)
    }

    private var pausedForBackground = false
    private var appInBackground = false

    private var backgroundJob: Job? = null
    /** A replay picked up after the background, waiting to be back where it was (see [disconnectForBackground]). */
    private var resumeJob: Job? = null
    /** The replay a Stalker link is being obtained for: where it starts (see [play]). */
    private var fetchStartMs: Long? = null
    private var fetchLeadMs = 0L
    /** Set when the channel's connection was let go in the background: how to pick it up again. */
    private var resumeAfterBackground: (() -> Unit)? = null

    /**
     * The app left the screen (Home button, another app): stop the sound straight away, and
     * a few seconds later let go of the provider connection (and any pause buffer), so the line
     * is free for another device. A quick overlay (a voice search, a system message) doesn't
     * drop the channel; going to the launcher does. Scheduled recordings carry on.
     */
    fun onAppBackground() {
        appInBackground = true
        adaptiveBuffer.setForeground(false)
        val p = _player ?: return
        // Held by Match Frame Rate counts as playing: it stays paused (its resume no longer plays
        // it behind the launcher) and plays on coming back.
        if (p.playWhenReady || displaySwitchHold) {
            pausedForBackground = true
            displaySwitchHold = false
            p.pause()
        }
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(5_000)
            disconnectForBackground()
        }
    }

    private fun disconnectForBackground() {
        val p = _player ?: return
        val ch = currentChannel ?: return
        // A recording on this device: nothing to let go of.
        if (_recordingPlayback.value != null) return
        if (resumeAfterBackground != null) return
        resumeJob?.cancel()
        lineWaitJob?.cancel()
        val wasPlaying = pausedForBackground
        val url = currentUrl
        val title = _state.value.catchupTitle
        // A Stalker link still being obtained: let go of that request too (its link came in later
        // and played behind the launcher, with no way back), and ask again on coming back from
        // where it was to start. The player still has the place of what played before it.
        val fetching = stalkerResolveJob?.isActive == true
        if (fetching) {
            stalkerResolveJob?.cancel()
            stalkerGeneration++
        }
        val start = if (fetching) fetchStartMs else archiveStartWallMs
        // A replay's copy on its way: the stopped player still has the place of what played
        // before it (another replay, a channel); the copy was to start where it's headed.
        val pos = when {
            fetching -> fetchLeadMs
            copyOnItsWay() -> leadSeekMs
            // A replay not moved to its point yet (its timeline isn't in, the player still says 0).
            title != null && leadSeekMs > 0 -> leadSeekMs
            else -> p.currentPosition
        }
        // A replay saved on this box as it played, more than a minute into its link: on coming
        // back it's asked of the provider again from there ([onCatchupResume]), a link that starts
        // within a minute of that point, so its copy gets there in seconds. Played from its own
        // link again, the copy had to save everything up to that point first: it started near the
        // link's start, and the player was then moved past what was saved ("Loading" for ever, or
        // the wrong place).
        val askAgain = title != null && replayCache.session != null && pos > RESUME_ASK_AGAIN_MS
        val clip = playingClip()
        val stoppedVideoMime = p.videoFormat?.sampleMimeType
        resumeAfterBackground = {
            if (decoderFallback.retryAfterBackgroundStop(ch.key, stoppedVideoMime)) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Resuming after background stop: retrying the recovered video decoder")
            }
            if (title != null) {
                // Catch-up: the same replay, back where you were. Its copy starts right there
                // when it can (see copyStartPoint); otherwise the player is moved there once ready.
                val skipsBefore = skipSerial
                // Paused before: it stays paused throughout (its Stalker link and its copy
                // included) and the controls show it paused; playing or not, Home pressed again
                // meanwhile sees it right. The stopped player says so first, which is what a link
                // asked again goes by. Before, this was set once play() had returned, which a
                // Stalker replay undid (its link comes in later): it played for a moment, sound
                // and picture, until the pause below.
                _player?.playWhenReady = wasPlaying
                // Far into a replay saved on this box (see askAgain): asked of the provider again
                // from there, a new link that starts there by itself. Otherwise its own link again.
                val askedAgain = askAgain && onCatchupResume?.invoke(pos, wasPlaying) == true
                if (!askedAgain) {
                    play(ch, url, title, catchupStartMs = start, catchupLeadMs = pos.coerceAtLeast(0L), plays = wasPlaying)
                }
                // What plays now (a new link when asked again, maybe in the next show): the wait
                // below goes by it. By the old link's, a new one counted as gone at once.
                val titleNow = if (askedAgain) _state.value.catchupTitle else title
                val startNow = if (askedAgain) archiveStartWallMs else start
                resumeJob = scope.launch {
                    var waited = 0
                    var readyWaited = 0
                    // Live, another channel, or another replay or point asked of the provider
                    // since (a new link): the old place means nothing there.
                    fun gone() = currentChannel?.key != ch.key || _state.value.catchupTitle != titleNow || archiveStartWallMs != startNow
                    // Its copy on its way doesn't count against the 15 s it gets to be ready
                    // (a skip meanwhile can make it wait again).
                    while (!gone() && waited < 60_000 &&
                        (copyOnItsWay() || (_player?.playbackState != Player.STATE_READY && readyWaited < 15_000))
                    ) {
                        delay(200)
                        waited += 200
                        if (!copyOnItsWay()) readyWaited += 200
                    }
                    if (gone()) return@launch
                    // A skip in it since wins over the old place (it was undone); paused stays paused.
                    val skipped = skipSerial != skipsBefore
                    _player?.let { pl ->
                        // Asked again: the new link already starts there (the old place is in the old link's time).
                        // Playing from its copy: only within what's saved. It started as close as it could;
                        // moved past that, it sat on "Loading" until the copy got there, or failed.
                        // (An HLS copy doesn't count what it saved: moved there as before.)
                        val reachable = replaySavedMs()?.takeIf { replayCache.session?.kind == LiveTvTimeshift.Kind.TS }
                            ?.let { pos + 1_000 < it } ?: true
                        if (pos > 0 && !skipped && !askedAgain && reachable && kotlin.math.abs(pl.currentPosition - pos) > 2_000) pl.seekTo(pos)
                        // Asked for paused (see above), so this only catches a link tried again by
                        // itself that plays (a format tried again, the copy given up for the
                        // provider's link), unless Play was pressed or it was picked again meanwhile.
                        if (!wasPlaying && !copyPlays) {
                            pl.pause()
                            // Home pressed again meanwhile marked it to play on coming back.
                            pausedForBackground = false
                        }
                    }
                }
            } else {
                currentUrl = null
                play(ch, overrideUrl = url.takeIf { it != ch.url })
                // A replay or highlight: back where it was, and still paused if it was.
                if (clip) {
                    if (!wasPlaying) _player?.playWhenReady = false
                    scope.launch {
                        var waited = 0
                        while (waited < 15_000 && _player?.playbackState != Player.STATE_READY) { delay(200); waited += 200 }
                        _player?.let { pl -> if (pos > 0) pl.seekTo(pos); if (!wasPlaying) pl.pause() }
                    }
                }
            }
        }
        stopShift()
        stopReplayCache()
        reconnectJob?.cancel()
        stallJob?.cancel()
        stallJob = null
        p.stop()
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("App in the background: ${ch.name} stopped, so the line is free for another device")
    }

    /** Back in the app: carry on, jumping to the live edge for live channels. */
    fun onAppForeground() {
        appInBackground = false
        adaptiveBuffer.setForeground(true)
        backgroundJob?.cancel()
        backgroundJob = null
        resumeAfterBackground?.let { r ->
            resumeAfterBackground = null
            pausedForBackground = false
            r()
            return
        }
        if (!pausedForBackground) return
        pausedForBackground = false
        val p = _player ?: return
        if (_state.value.catchupTitle == null && p.isCurrentMediaItemLive) p.seekToDefaultPosition()
        p.play()
    }

    fun togglePause() {
        val p = _player ?: return
        // A replay's copy on its way (the player is stopped meanwhile): whether it plays once in.
        // Before, the copy then always played, whatever was pressed meanwhile.
        if (copyOnItsWay()) {
            copyPlays = !p.playWhenReady
            p.playWhenReady = copyPlays
            return
        }
        // Paused or playing goes by what was asked (playWhenReady), which is what the play/pause
        // button shows, not by the picture moving: while it loads after a skip or a stall it
        // isn't moving yet, and Pause then pressed Play instead, which did nothing, so it played
        // on once loaded. With a problem on screen (reconnecting) it's as before.
        val asked = p.playWhenReady && _state.value.error == null
        // A live channel, recording or pause buffer that's stopped (its link still being fetched,
        // the recording or buffer about to start) is set playing once it's in whatever is
        // pressed, and the pause buffer needs a stream to record from: there it's as before.
        val streamIn = asked && p.playbackState != Player.STATE_IDLE
        when {
            _shiftStartedAt.value != null -> if (streamIn) p.pause() else resumeShift()
            _recordingPlayback.value != null -> if (streamIn) p.pause() else p.play()
            // A sports addon game: the stream itself pauses (no pause buffer needed).
            _ownTimeline.value -> p.playWhenReady = !p.playWhenReady
            _state.value.catchupTitle == null -> if (streamIn) pauseLive() else p.play()
            // A replay, loaded or still loading (stopped while its link is fetched too): it
            // stays paused once in.
            asked -> {
                p.pause()
                copyPlays = false
            }
            else -> {
                p.play()
                // A paused replay picked up after the background then plays (see its resume).
                copyPlays = true
            }
        }
    }

    fun seekBy(ms: Long) {
        val p = _player ?: return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
    }

    /** Captions are showing (a text track is selected and text isn't switched off). */
    fun captionsOn(): Boolean {
        val p = _player ?: return false
        if (C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes) return false
        return tracksOf(C.TRACK_TYPE_TEXT).any { it.selected }
    }

    /** Settings: captions on automatically when a channel has them. */
    var captionsByDefault: Boolean = false
    /** Captions were already switched on (or you chose) for the channel now playing. */
    private var captionsHandled = false

    /** Saved caption choices: channel key -> "off" / "on|cc" / "on|sub|label" (kept up to date by Live TV). */
    var channelCaptions: Map<String, String> = emptyMap()
    /** Called when you switch captions on or off, to remember it for that channel. */
    var onCaptionsChosen: ((channelKey: String, captions: String) -> Unit)? = null

    private fun saveCaptionChoice(option: LiveTrackOption?) {
        val key = currentChannel?.key ?: return
        val saved = when {
            option == null -> "off"
            option.kind != null -> "on|cc"
            else -> "on|sub|" + option.label
        }
        channelCaptions = channelCaptions + (key to saved)
        onCaptionsChosen?.invoke(key, saved)
    }

    /** Puts the saved choice back once the channel's tracks are known (see [saveCaptionChoice]). */
    private fun applySavedCaptions(saved: String) {
        if (saved.startsWith("off")) {
            if (captionsOn()) selectTrack(C.TRACK_TYPE_TEXT, null)
            return
        }
        val all = tracksOf(C.TRACK_TYPE_TEXT)
        val wantsCc = saved.startsWith("on|cc")
        val label = saved.removePrefix("on|sub|")
        val now = if (captionsOn()) all.firstOrNull { it.selected } else null
        // Already showing the right kind (the other copy of the captions counts too).
        if (now != null && (if (wantsCc) now.kind != null else now.kind == null && now.label == label)) return
        val pick = if (wantsCc) primaryCaption(all) else all.firstOrNull { it.kind == null && it.label == label }
        if (pick == null) return
        selectTrack(C.TRACK_TYPE_TEXT, pick)
        if (pick.kind != null) watchCaptions()
    }

    /** One press: captions off if on; otherwise on, with the first available track. */
    fun toggleCaptions(): Boolean {
        captionsHandled = true // your choice for this channel stands
        captionWatch?.cancel()
        return if (captionsOn()) { selectTrack(C.TRACK_TYPE_TEXT, null); saveCaptionChoice(null); false }
        else {
            val all = tracksOf(C.TRACK_TYPE_TEXT)
            val pick = subtitleTracks().firstOrNull()
            // For the report: what the stream offers and what has been seen carrying text.
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Captions: " + (pick?.let { "turned on \"${it.label}\"" } ?: "nothing to turn on") +
                    "; caption tracks in the stream: " + (all.joinToString(", ") { it.kind ?: (it.label) }.ifBlank { "none" }) +
                    "; seen with text: " + (LiveCaptions.found.value.joinToString(", ").ifBlank { if (LiveCaptions.watching()) "none yet" else "not checked" })
            )
            pick ?: return false
            selectTrack(C.TRACK_TYPE_TEXT, pick)
            saveCaptionChoice(pick)
            if (pick.kind != null) watchCaptions()
            true
        }
    }

    /** Picked in the Subtitles menu: that track stays, no switching to the other copy. */
    fun pickSubtitle(option: LiveTrackOption?) {
        captionsHandled = true
        captionWatch?.cancel()
        selectTrack(C.TRACK_TYPE_TEXT, option)
        saveCaptionChoice(option)
    }

    private var captionWatch: Job? = null

    /**
     * Captions switched on: if the copy picked shows nothing for 10 s while the other copy of the
     * same captions (the old kind or the digital kind) is carrying text, switch to that one.
     */
    private fun watchCaptions() {
        captionWatch?.cancel()
        captionWatch = scope.launch {
            repeat(12) { step ->
                delay(5_000)
                if (!captionsOn()) return@launch
                val all = tracksOf(C.TRACK_TYPE_TEXT)
                val kind = all.firstOrNull { it.selected }?.kind ?: return@launch
                val found = LiveCaptions.found.value
                if (kind in found) return@launch // working
                if (step < 1) return@repeat
                val other = all.firstOrNull {
                    it.kind != null && it.kind != kind &&
                        it.kind in setOf(LiveCaptions.CC1, LiveCaptions.DIGITAL) && it.kind in found
                }
                if (other != null) {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Captions: nothing on ${kind.uppercase()}, switched to ${other.kind?.uppercase()}")
                    selectTrack(C.TRACK_TYPE_TEXT, other)
                    return@launch
                }
            }
        }
    }

    /**
     * The closed-captions track to offer first: CC1, or the digital copy when only that one has
     * text. Null when the channel has no captions inside the video.
     */
    private fun primaryCaption(all: List<LiveTrackOption>): LiveTrackOption? {
        val cea = all.filter { it.kind != null }
        if (cea.isEmpty()) return null
        val found = LiveCaptions.found.value
        return cea.firstOrNull { it.kind == LiveCaptions.CC1 && (found.isEmpty() || LiveCaptions.CC1 in found) }
            ?: cea.firstOrNull { it.kind == LiveCaptions.DIGITAL && LiveCaptions.DIGITAL in found }
            ?: cea.firstOrNull { it.kind in found }
            ?: cea.firstOrNull { it.kind == LiveCaptions.CC1 }
            ?: cea.first()
    }

    /** Other caption tracks this channel really carries (the CC menu's "More caption tracks"). */
    fun moreCaptionTracks(): List<LiveTrackOption> {
        val all = tracksOf(C.TRACK_TYPE_TEXT)
        val primary = primaryCaption(all)
        val found = LiveCaptions.found.value
        return all.filter { it.kind != null && it != primary && (it.kind in found || it.selected) }
    }

    /** For an empty Subtitles menu: why. */
    fun captionsNote(): String? = when {
        subtitleTracks().isNotEmpty() || moreCaptionTracks().isNotEmpty() -> null
        else -> "No captions on this channel"
    }

    /** Stream Info: which captions the channel carries. */
    fun captionsInfo(): String {
        val found = LiveCaptions.found.value
        if (found.isNotEmpty()) return LiveCaptions.summary(found)
        val subs = tracksOf(C.TRACK_TYPE_TEXT).filter { it.kind == null }
        if (subs.isNotEmpty()) return subs.joinToString(", ") { it.label }
        return when {
            LiveCaptions.noneFound() -> "None found in this stream"
            LiveCaptions.watching() -> "Checking…"
            tracksOf(C.TRACK_TYPE_TEXT).isEmpty() -> "None"
            else -> "Not checked for this stream type"
        }
    }

    /**
     * The sound of what's playing for the info bar badge: the kind when it's surround from Dolby
     * or DTS, and the channels ("DD+ 5.1", "DOLBY 5.1", "ATMOS", "DTS 5.1", "5.1", "STEREO").
     */
    fun audioBadge(): String? {
        val f = _player?.audioFormat ?: return null
        val chans = when (val c = f.channelCount) {
            1 -> "MONO"
            2 -> "STEREO"
            6 -> "5.1"
            8 -> "7.1"
            in 3..16 -> "${c}CH"
            else -> null
        }
        val mime = f.sampleMimeType.orEmpty()
        val codecs = f.codecs.orEmpty().lowercase()
        val kind = when {
            mime == MimeTypes.AUDIO_E_AC3_JOC || codecs.contains("joc") -> return "ATMOS"
            mime == MimeTypes.AUDIO_E_AC3 -> "DD+"
            mime == MimeTypes.AUDIO_AC3 -> "DOLBY"
            mime == MimeTypes.AUDIO_AC4 -> "AC-4"
            mime.startsWith("audio/vnd.dts") -> "DTS"
            else -> null
        }
        return when {
            kind != null && chans != null && chans != "STEREO" && chans != "MONO" -> "$kind $chans"
            kind != null && chans == null -> kind
            else -> chans
        }
    }

    /** The audio track playing, short ("English 5.1"), for the panel. */
    fun audioLabel(): String = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label?.take(18) ?: "Audio"

    fun audioTracks(): List<LiveTrackOption> = tracksOf(C.TRACK_TYPE_AUDIO)

    /** Saved audio choices: channel key -> "language|label|channels" (kept up to date by Live TV). */
    var channelAudio: Map<String, String> = emptyMap()
    /** Called when you pick an audio track, to remember it for that channel. */
    var onAudioChosen: ((channelKey: String, audio: String) -> Unit)? = null
    private var audioHandled = false
    private var audioPrefPending = true

    /** Picked in the Audio menu: plays it, and it's used every time this channel plays. */
    fun pickAudio(option: LiveTrackOption) {
        audioHandled = true
        selectTrack(C.TRACK_TYPE_AUDIO, option)
        val key = currentChannel?.key ?: return
        // Its place in the list too: streams often have several tracks with the same name and no
        // language ("AAC 2ch" twice), and the name alone picked the first of them every time.
        val position = tracksOf(C.TRACK_TYPE_AUDIO).indexOfFirst { it.groupIndex == option.groupIndex && it.trackIndex == option.trackIndex }
        val saved = listOf(option.language.orEmpty(), option.label, option.channels.toString(), position.toString()).joinToString("|")
        channelAudio = channelAudio + (key to saved)
        _player?.let { p ->
            // Later streams on this channel (catch-up, a reconnect) start in this language too.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(option.language)
                .build()
        }
        onAudioChosen?.invoke(key, saved)
    }

    /**
     * The saved audio choice, once this channel's tracks are known. The language is already
     * asked for; this also picks the same track among several in that language (stereo or 5.1,
     * main or audio description), by its label and number of channels.
     */
    private fun applySavedAudio() {
        val key = currentChannel?.key ?: return
        val options = tracksOf(C.TRACK_TYPE_AUDIO)
        if (options.isEmpty()) return
        // Checked every time the tracks change, not just once per channel: a reconnect or a
        // catch-up brings new tracks, and the player went back to the stream's default audio.
        audioHandled = true
        val parts = channelAudio[key]?.split('|') ?: return
        val lang = parts.getOrNull(0).orEmpty()
        val label = parts.getOrNull(1).orEmpty()
        val chans = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val position = parts.getOrNull(3)?.toIntOrNull() ?: -1
        fun sameLang(o: LiveTrackOption) = lang.isNotBlank() && o.language != null &&
            java.util.Locale.forLanguageTag(o.language).language == java.util.Locale.forLanguageTag(lang).language
        val sameName = options.filter { it.label == label }
        val pick = when {
            sameName.size == 1 -> sameName[0]
            // Several with that name: the one in the same place as before.
            sameName.size > 1 -> options.getOrNull(position)?.takeIf { it.label == label } ?: sameName[0]
            else -> options.firstOrNull { sameLang(it) && it.channels == chans }
                ?: options.firstOrNull { sameLang(it) }
                ?: options.getOrNull(position)?.takeIf { lang.isBlank() && (chans == 0 || it.channels == chans) }
        } ?: return
        if (!pick.selected) selectTrack(C.TRACK_TYPE_AUDIO, pick)
    }
    /**
     * The Subtitles menu: ordinary subtitle tracks, plus one closed-captions entry (the others
     * are under "More caption tracks"). No captions entry once 20 s of watching found none.
     */
    fun subtitleTracks(): List<LiveTrackOption> {
        val all = tracksOf(C.TRACK_TYPE_TEXT)
        val primary = primaryCaption(all)
        // Closed captions stay on offer even when none have been seen yet (some streams carry
        // them in ways the check misses): just labelled so.
        val shown = primary?.let { if (LiveCaptions.noneFound() && !it.selected) it.copy(label = it.label + " (" + com.nuvio.tv.livetv.ui.LiveTvTexts.tr("none seen yet") + ")") else it }
        val subs = all.filter { it.kind == null }
        return when {
            shown == null -> subs
            // Captions seen carrying text (or switched on): first, for the CC button.
            LiveCaptions.found.value.isNotEmpty() || shown.selected -> listOf(shown) + subs
            // None seen yet: after the stream's own subtitles, still there to pick.
            else -> subs + shown
        }
    }

    fun selectTrack(type: Int, option: LiveTrackOption?) {
        val p = _player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun tracksOf(type: Int): List<LiveTrackOption> {
        val p = _player ?: return emptyList()
        val out = mutableListOf<LiveTrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, group: Tracks.Group ->
            if (group.type != type) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val kind = LiveCaptions.kindOf(f)
                val captions = kind?.let { com.nuvio.tv.livetv.ui.LiveTvTexts.tr(LiveCaptions.label(it)) }
                val parts = listOfNotNull(
                    captions ?: f.label,
                    f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } },
                    f.sampleMimeType?.takeIf { captions == null }?.substringAfter('/')?.uppercase(),
                    if (f.channelCount > 0) "${f.channelCount}ch" else null
                )
                out += LiveTrackOption(
                    gi, ti, parts.distinct().joinToString(" · ").ifBlank { "Track ${out.size + 1}" }, group.isTrackSelected(ti), kind,
                    language = f.language, channels = f.channelCount.coerceAtLeast(0)
                )
            }
        }
        return out
    }

    /**
     * How much the player may download ahead, in bytes, from how much memory the app really gets.
     * Uses the same figure as the image cache and the main player ([MemoryBudget.heapMb]): on a
     * 1 GB box that reports a 512 MB "large" heap it can't back, that is half the reported heap,
     * so the download-ahead is about 75 MB there instead of about 150 MB.
     */
    private fun playerBufferPolicy() = LiveTvBufferPolicy.forDevice(
        MemoryBudget.heapMb,
        com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context)
    )

    private val decoderFallback = LiveTvDecoderFallback()

    private fun buildPlayer(): ExoPlayer {
        val passthrough = audioPassthrough
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                // Pretend the output only takes plain PCM: Dolby / DTS gets decoded here.
                @Suppress("DEPRECATION")
                val sink = if (passthrough) super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                else androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
                return sink?.let { adaptiveBuffer.wrapAudioSink(it, enableAudioTrackPlaybackParams) }
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            // Asked each time a decoder opens: software only for a channel whose hardware
            // decoder failed (see onPlayerError), hardware as normal everywhere else.
            .setMediaCodecSelector { mime, secure, tunneling ->
                val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (!decoderFallback.usesSoftware(currentChannel?.key, mime) ||
                    !androidx.media3.common.MimeTypes.isVideo(mime)
                ) all else all.filter { it.softwareOnly }.ifEmpty { all }
            }
        // Buffer size setting: how much is downloaded ahead. Bigger rides out a shaky connection or
        // a slow provider; smaller starts channels a little faster.
        val (minMs, maxMs, startMs, rebufferMs) = when (bufferSize) {
            // None: as little as the player can play with (a second or two ahead): closest to
            // live and the quickest channel changes, but any hiccup in the stream shows as a pause.
            "none" -> listOf(1_500, 4_000, 250, 500)
            "small" -> listOf(10_000, 30_000, 1_000, 2_000)
            "large" -> listOf(40_000, 90_000, 3_000, 6_000)
            "xlarge" -> listOf(60_000, 150_000, 5_000, 10_000)
            else -> listOf(25_000, 60_000, 2_000, 4_000)
        }
        val bufferPolicy = playerBufferPolicy()
        val targetBufferBytes = bufferPolicy.targetBytes
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(bufferPolicy.prioritizeTime)
            // The download-ahead lives in the app's memory. The player's own cap (about 130 MB
            // for video) is more than half of what a cheap stick gives an app, which made the
            // whole app stutter as memory ran short. Capped to about 30% of the memory the app can
            // really use (see playerBufferPolicy):
            // the same on boxes with plenty, smaller on low-memory ones. On small heaps the
            // byte target also stops loading before the duration minimum on 1-2 GiB devices:
            // high-bitrate video
            // must not consume the UI/decoder's memory to satisfy a large buffer setting.
            .setTargetBufferBytes(targetBufferBytes)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(adaptiveBuffer.wrapLoadControl(loadControl, targetBufferBytes))
            // Take part in Android's audio focus like other media apps: when something else starts
            // playing sound (another app, a launcher's video wallpaper with sound…), Live TV pauses.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
            .apply {
                playWhenReady = true
                addListener(listener)
                // Choppy video ("slide show"): how many frames the box couldn't show in time,
                // in the start-up report, so we can see when it starts and what was going on.
                setVideoFrameMetadataListener(frameListener)
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    private var droppedTotal = 0L
                    private var lastReportAt = 0L
                    private var lastJumpReportAt = 0L

                    /**
                     * The sound's own clock jumped inside the stream (not a skip): what the
                     * provider sent is spliced from two moments of the show. The sound plays on
                     * from the other moment while the picture holds until it catches up (seen on
                     * a replay carried on from the middle on a provider that sends it differently
                     * each time). In the Start-Up Report, so it shows where it came from.
                     */
                    override fun onAudioSinkError(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        audioSinkError: Exception
                    ) {
                        val jump = audioSinkError as? androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException ?: return
                        val deltaMs = (jump.actualPresentationTimeUs - jump.expectedPresentationTimeUs) / 1000
                        if (kotlin.math.abs(deltaMs) < 1_000) return
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastJumpReportAt < 10_000) return
                        lastJumpReportAt = now
                        val what = when {
                            _state.value.catchupTitle == null -> "channel ${currentChannel?.name}"
                            replayLocal -> "replay, from its copy on this box"
                            else -> "replay, straight from the provider · ${redact(currentUrl.orEmpty())}"
                        }
                        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                            "Sound jumped ${if (deltaMs < 0) "back" else "ahead"} ${String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(deltaMs) / 1000.0)} s " +
                                "inside the stream ($what); the picture holds until it catches up"
                        )
                    }
                    override fun onDroppedVideoFrames(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        droppedTotal += droppedFrames
                        val now = System.currentTimeMillis()
                        if (droppedFrames >= 10 && now - lastReportAt > 30_000) {
                            lastReportAt = now
                            val rt = Runtime.getRuntime()
                            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                                "Video dropped $droppedFrames frames in ${elapsedMs / 1000}s ($droppedTotal so far; app memory ${usedMb} of ${rt.maxMemory() / (1024 * 1024)} MB)"
                            )
                        }
                    }
                })
            }
    }

    // ---------------------------------------------------------------- picture watchdog

    /**
     * Moving the picture between the guide preview and full screen gives the player a new
     * screen to draw on. On some TV chips the video then freezes (sound keeps going) or never
     * starts. After each move, if no picture appears within a couple of seconds, quietly
     * restart the stream on the new screen (what "Reload stream" did by hand).
     */
    @Volatile private var awaitingFrame = false
    private var frameJob: Job? = null

    fun onSurfaceAttached() {
        val p = _player ?: return
        awaitingFrame = true
        frameJob?.cancel()
        frameJob = scope.launch {
            // Sound playing but no picture: frozen video. A channel that's simply still loading
            // gets longer before we step in (it may just be slow to start).
            delay(FRAME_TIMEOUT_MS)
            // Dispatcharr, 4K/HDR: the picture takes longer to appear after the sound.
            if (stuck(p, requireReady = true) && slowStarter() && (p.videoFormat?.height ?: 0) > 1080) delay(BIG_PICTURE_EXTRA_MS)
            if (!stuck(p, requireReady = true)) {
                val bufferedBefore = p.bufferedPosition
                delay(LOADING_AFTER_MOVE_MS - FRAME_TIMEOUT_MS)
                if (!stuck(p, requireReady = false)) return@launch
                // Dispatcharr, still loading but the stream keeps coming in: just slow to start
                // (it buffers first), not stuck. Restarting would drop the connection, and
                // Dispatcharr may then hand over the channel's next, lower-quality stream. The
                // stall check still steps in if it never gets going. Everything else restarts
                // straight away, as before.
                if (slowStarter() && p.playbackState == Player.STATE_BUFFERING && p.bufferedPosition > bufferedBefore) return@launch
            }
            Log.w(TAG, "No picture after moving the video; restarting the stream")
            val archive = _state.value.catchupTitle != null
            val pos = p.currentPosition
            // A replay or highlight carries on where it was, as catch-up does.
            val ownAt = ownReplayResumeAt(p)
            p.stop()
            archiveStartPending = false
            p.prepare()
            if (archive && pos > 0) p.seekTo(pos) else if (ownAt != C_TIME_UNSET && ownAt > 0) p.seekTo(ownAt)
            p.playWhenReady = true
        }
    }

    private fun stuck(p: ExoPlayer, requireReady: Boolean): Boolean {
        if (!awaitingFrame || !p.playWhenReady || currentChannel == null || _state.value.error != null) return false
        return if (requireReady) p.playbackState == Player.STATE_READY && p.isPlaying
        else p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_READY
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.value = _state.value.copy(userPaused = !playWhenReady)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            applySavedAudio()
            // "Captions on by default": once the channel's caption tracks are known, switch them
            // on (once per channel, and not if you've turned them off yourself).
            if (tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }) return
            // What you chose on this channel last time (or earlier in this watch) comes first.
            // Checked on every track change, not once: a reconnect or the full-screen switch
            // to the rewind buffer brings new tracks and the choice would otherwise be lost.
            val saved = currentChannel?.key?.let { channelCaptions[it] }
            if (saved != null) { captionsHandled = true; applySavedCaptions(saved); return }
            if (!captionsByDefault) return
            captionsHandled = true
            if (!captionsOn()) subtitleTracks().firstOrNull()?.let {
                selectTrack(C.TRACK_TYPE_TEXT, it)
                if (it.kind != null) watchCaptions()
            }
        }

        override fun onRenderedFirstFrame() {
            // Stopped: a picture of the stream just let go of, told late (the player is stopped
            // while a replay's copy is on its way). Not the picture of what's asked for now.
            if (_player?.playbackState == Player.STATE_IDLE) return
            awaitingFrame = false
            if (!_state.value.pictureShown || !_state.value.started) _state.value = _state.value.copy(pictureShown = true, started = true)
            _pictures.value = _pictures.value + 1
            // Playing fine for a while: no longer counts as "the app closed while this was starting".
            currentChannel?.key?.let { key ->
                scope.launch {
                    kotlinx.coroutines.delay(15_000)
                    if (currentChannel?.key == key) clearStarting()
                }
            }
            // HLS catch-up failed and the TS link plays: remember it for this provider.
            hlsCatchupFailedOn?.let { source ->
                hlsCatchupFailedOn = null
                onCatchupHlsUnsupported?.invoke(source)
            }
            // Catch-up playing: remember which kind of link this provider answers, and say how
            // long it took (Start-Up Report), once per replay.
            if (_state.value.catchupTitle != null && !catchupLinkReported) {
                catchupLinkReported = true
                val playing = currentUrl
                val primary = catchupPrimary
                val secs = (android.os.SystemClock.elapsedRealtime() - catchupAskedAt) / 100 / 10.0
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: picture after $secs s" + if (replayLocal) " (saved while it plays)" else "")
                val source = currentChannel?.sourceId
                if (playing != null && primary != null && source != null && com.nuvio.tv.livetv.data.CatchupUrlBuilder.wholeMinutes(primary)) {
                    onCatchupLinkWorked?.invoke(source, com.nuvio.tv.livetv.data.CatchupUrlBuilder.linkForm(playing, primary))
                }
            }
            // HLS catch-up playing: this provider does offer it.
            val url = currentUrl?.substringBefore('?')
            if (_state.value.catchupTitle != null && url != null && url.contains("/timeshift/") && url.endsWith(".m3u8", true)) {
                currentChannel?.sourceId?.let { onCatchupHlsWorks?.invoke(it) }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            updateOwnTimeline()
            // Stopped while a replay's copy is on its way: this is still about the stream let go
            // of (the player tells it once more as it stops). Taken for the copy, the skip into
            // the minute was "done" on the old stream and forgotten, so the copy started on the
            // whole minute, or a skip that reads the copy again went back to the copy's start.
            if (copyOnItsWay()) return
            val urlBefore = currentUrl
            checkCatchupClock(timeline)
            // The wrong show came and another link is asked for: the rest of this is the old
            // link's timeline (it cleared the new link's skip into the minute, or its own start).
            if (currentUrl != urlBefore || copyOnItsWay()) return
            val p = _player ?: return
            // The first timeline is only a stand-in until the stream answers. A seek to 0 made
            // then is ignored by the player (it reads 0 as "no seek" and starts live streams at
            // the live edge), which is why "Watch from start" still began at now on some
            // providers. Wait for the real one.
            val window = windowOf(p, timeline) ?: return
            if (shiftStartPending) {
                shiftStartPending = false
                p.seekTo(0L)
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: timeline in (buffer holds ${if (window.durationMs != C.TIME_UNSET) window.durationMs / 1000 else -1} s); started at the pause point (was at ${p.currentPosition / 1000} s)")
            }
            if (!archiveStartPending) {
                if (leadSeekMs > 0) tryLeadSeek(p, window)
                return
            }
            archiveStartPending = false
            if (_state.value.catchupTitle == null) return
            if (!(window.isDynamic || window.isLive())) { tryLeadSeek(p, window); return }
            // A replay sent as a growing live stream: the player would start it at the live
            // edge. When the stream carries its own clock and reaches back before the show
            // (the provider sent its live stream with a long rewind window instead of just the
            // show), go to the show's start by the clock; otherwise to the start of the window.
            val startWall = archiveStartWallMs
            val windowStart = window.windowStartTimeMs
            if (startWall != null && windowStart != C.TIME_UNSET && startWall - windowStart > 120_000) {
                val length = window.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
                val target = (startWall - windowStart).coerceAtMost(length)
                archiveWallMapped = true
                // Positions go by the stream's clock here, which already lands on the exact time.
                leadSeekMs = 0L
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up came as the live stream with rewind; jumping back ${target / 60_000} min into it to the show's start")
                p.seekTo(target)
            } else {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up came as a live stream; starting from the beginning")
                p.seekTo(0L)
                tryLeadSeek(p, window)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                // Stopped while a replay's copy is on its way: still loading.
                isBuffering = playbackState == Player.STATE_BUFFERING || (playbackState == Player.STATE_IDLE && copyOnItsWay()),
                error = if (playbackState == Player.STATE_READY) null else _state.value.error,
                reconnectAttempt = if (playbackState == Player.STATE_READY) 0 else _state.value.reconnectAttempt
            )
            if (playbackState == Player.STATE_ENDED && _state.value.catchupTitle != null && replayLocal && savedRecordingId == null) {
                // The replay's copy played to its end: it's carried on from here (followCatchup, or
                // copyRanOut), and the next copy starts at this very point (see copyKeepsPoint).
                keepPointUntil = android.os.SystemClock.elapsedRealtime() + 15_000L
            }
            if (playbackState == Player.STATE_ENDED && _shiftStartedAt.value != null && timeshift.isRecording) {
                // Caught up with the end of what's recorded while it's still recording: carry on
                // from here (the newest pieces come in), rather than dropping back to live.
                reloadShiftAt(_player?.currentPosition ?: 0L, "reached the end of the buffer")
                return
            }
            if (playbackState == Player.STATE_ENDED && _shiftStartedAt.value != null && !alwaysBuffer) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: playback ended (recording stopped); back to live")
                goLive()
                return
            }
            if (playbackState == Player.STATE_ENDED && _shiftStartedAt.value != null && alwaysBuffer) {
                // The buffer stopped coming in (the stream dropped): start over, or play directly
                // if it fails straight away.
                bufferDropped()
                return
            }
            if (playbackState == Player.STATE_ENDED && _recordingPlayback.value != null) {
                // The recording you were watching from finished: on with the channel live.
                recordingPlaybackEnded()
                return
            }
            if (playbackState == Player.STATE_ENDED && _state.value.catchupTitle == null) {
                // A replay or highlight from Sports that played to its end: done, not a dropout.
                if (clipFinished()) {
                    finishClip()
                    return
                }
                // A live stream should never end; treat it as a dropped connection.
                scheduleReconnect("Stream ended")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying, started = _state.value.started || isPlaying)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.value = _state.value.copy(
                videoWidth = videoSize.width, videoHeight = videoSize.height,
                pixelRatio = videoSize.pixelWidthHeightRatio
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // A recording that failed: back to live rather than reconnecting to nowhere.
            if (_shiftStartedAt.value != null && timeshift.isRecording && shiftReloads < 5) {
                // A hiccup reading the buffer (it's still recording): pick up where it was.
                Log.w(TAG, "Buffer playback error; reloading", error)
                reloadShiftAt(_player?.currentPosition ?: 0L, error.errorCodeName)
                return
            }
            if (_shiftStartedAt.value != null && alwaysBuffer) {
                Log.w(TAG, "Buffer playback failed", error)
                bufferDropped()
                return
            }
            if (_shiftStartedAt.value != null) {
                Log.w(TAG, "Recording playback failed", error)
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: playback error ${error.errorCodeName} (recording=${timeshift.isRecording}, reloads=$shiftReloads); back to live")
                goLive(); return
            }
            if (_recordingPlayback.value != null) { Log.w(TAG, "Playing from the recording failed", error); recordingPlaybackEnded(); return }
            // A recording from Recordings: there's nothing else to try, so say so.
            if (savedRecordingId != null) {
                Log.w(TAG, "Recording playback error", error)
                recoverSavedRecording("playback error ${error.errorCodeName}")
                return
            }
            // The saved catch-up couldn't be played: play the replay straight from the provider.
            if (replayLocal) {
                val ch0 = currentChannel
                val url0 = currentUrl
                // The player gave up on the copy's list because it hadn't grown for 35 s (a piece
                // took that long to save: PureTv at 0.15x), while the save goes on. The copy is
                // fine: it's read again from here and goes on waiting for the save. Asking the
                // provider again is left to the watch for a copy running dry (copyStarved), which
                // keeps its place and doesn't keep asking where that doesn't help. Before, this
                // threw the copy away and asked again from the whole minute, every time.
                val copy0 = replayCache.session
                val pos0 = _player?.currentPosition ?: 0L
                if (copy0 != null && replayCache.isRecording && copy0.kind == LiveTvTimeshift.Kind.TS && _player != null &&
                    generateSequence<Throwable>(error) { it.cause }.take(8)
                        .any { it is androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker.PlaylistStuckException }
                ) {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                        "Catch-up: the player stopped waiting for the copy (nothing new in its list for 35 s; ${replayCache.savedMs / 1000} s saved); reading it again from ${pos0 / 1000} s"
                    )
                    playCopyFrom(_player!!, copy0, pos0)
                    return
                }
                // First ask again from where it got to (a new copy from there), instead of
                // starting the replay over from its beginning with no copy; up to 3 times running.
                if (onCatchupStalled != null && catchupRestarts < 3) {
                    catchupRestarts++
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: the saved copy didn't play (${error.errorCodeName}); asking again from this point (try $catchupRestarts)")
                    _player?.stop()
                    if (onCatchupStalled?.invoke() == true) return
                }
                stopReplayCache()
                if (ch0 != null && url0 != null) {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: the saved copy didn't play (${error.errorCodeName}); playing it directly")
                    archiveStartPending = true
                    _player?.let { pl ->
                        // Once the copy's save has let go of its connection.
                        openWhenLineFree(pl) {
                            pl.setMediaSource(buildMediaSource(url0, ch0.headers, isLive = false))
                            pl.prepare()
                            // Paused or playing stays as it was.
                        }
                    }
                    return
                }
            }
            // A replay played straight from the provider that had been playing (not one that
            // never started, which tries the other links as before): asked again from where it got
            // to, saved while it plays. The other forms of the link, and the same link again,
            // start where the link starts: a replay ten minutes in went back ten minutes.
            if (_state.value.catchupTitle != null && archiveStarted && !replayLocal && activeStalkerRef == null &&
                onCatchupStalled != null && catchupRestarts < 3
            ) {
                catchupRestarts++
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up stopped while playing straight from the provider (${error.errorCodeName}); asking again from this point (try $catchupRestarts)")
                _player?.stop()
                if (onCatchupStalled?.invoke() == true) return
            }
            val p = _player
            // The provider doesn't offer this as HLS: switch to the TS link once.
            val ch = currentChannel
            val fb = fallbackUrls.removeFirstOrNull()
            if (fb != null && ch != null && p != null) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up failed (${error.errorCodeName}); trying ${redact(fb)}")
                val wasHls = isHlsReplayLink(currentUrl)
                val toTs = isTsReplayLink(fb)
                hlsCatchupFailedOn = if (wasHls && toTs) ch.sourceId else null
                currentUrl = fb
                if (_state.value.catchupTitle != null && saveFallback(ch, fb)) return
                archiveStartPending = _state.value.catchupTitle != null
                p.setMediaSource(buildMediaSource(fb, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                // A replay stays paused or playing as it was (a skip asked while paused stays paused).
                if (_state.value.catchupTitle == null) p.playWhenReady = true
                return
            }
            // "Container unsupported": the link didn't say what kind of stream it is and it was
            // read as a plain video file. Try it as HLS, then as DASH, before giving up.
            if (ch != null && p != null && isUnrecognizedFormat(error) && formatGuess < FORMAT_GUESSES.size) {
                forcedMime = FORMAT_GUESSES[formatGuess++]
                Log.w(TAG, "Unrecognized stream format for ${ch.name}; trying $forcedMime")
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    (if (_state.value.catchupTitle != null) "Catch-up" else "Channel ${ch.name}") +
                        " failed (${error.errorCodeName}); trying it as ${forcedMime?.substringAfter('/')}"
                )
                val url = currentUrl ?: ch.url
                p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                // A replay stays paused or playing as it was.
                if (_state.value.catchupTitle == null) p.playWhenReady = true
                return
            }
            // The provider's HLS playlist can't be read ("manifest malformed"): Xtream channels
            // also come as plain TS, which TiviMate-style players use. Switch once, and keep using
            // TS for this channel until the app restarts.
            if (ch != null && p != null && _state.value.catchupTitle == null && ch.key !in tsInstead && isMalformedManifest(error)) {
                val ts = tsLinkFor(currentUrl ?: ch.url)?.takeIf { it != (currentUrl ?: ch.url) }
                if (ts != null) {
                    tsInstead += ch.key
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                        "Channel ${ch.name}: its HLS playlist couldn't be read (${error.errorCodeName}); playing it as TS instead"
                    )
                    currentUrl = ts
                    forcedMime = null
                    p.setMediaSource(buildMediaSource(ts, ch.headers, isLive = true))
                    p.prepare()
                    p.playWhenReady = true
                    return
                }
            }
            // Still "malformed" (no TS link to switch to, or the TS link said the same): the
            // provider is often sending plain TS on a link that looks like HLS. Read the same
            // link as TS once before reconnecting over and over.
            if (ch != null && p != null && _state.value.catchupTitle == null && ch.key !in plainTs && isMalformedManifest(error)) {
                plainTs += ch.key
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "Channel ${ch.name}: still no readable HLS playlist (${error.errorCodeName}); reading the stream as plain TS"
                )
                forcedMime = MimeTypes.VIDEO_MP2T
                val url = currentUrl ?: ch.url
                p.setMediaSource(buildMediaSource(url, ch.headers, isLive = true))
                p.prepare()
                p.playWhenReady = true
                return
            }
            // The TV's hardware video decoder failed on this channel: play it once more on
            // Android's software decoder before showing an error (a decoder taken away for a
            // moment just reconnects as usual).
            val failedMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
                ?: p?.videoFormat?.sampleMimeType
            val transientDecoderFailure = error.errorCode in TRANSIENT_VIDEO_DECODER_ERRORS &&
                generateSequence(error.cause) { it.cause }.take(8).any {
                    it is android.media.MediaCodec.CodecException && (it.isTransient || it.isRecoverable)
                }
            if (ch != null && p != null && error.errorCode in VIDEO_DECODER_ERRORS &&
                decoderFallback.fallBack(ch.key, failedMime, transientDecoderFailure)
            ) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Channel ${ch.name}: video decoder failed (${error.errorCodeName}); trying the software decoder")
                val url = currentUrl ?: ch.url
                p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                p.playWhenReady = true
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (_state.value.catchupTitle != null) com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Catch-up failed on ${ch?.name} (${error.errorCodeName}); no other links left to try"
            )
            if (clipFailedAtEnd()) { finishClip(); return }
            scheduleReconnect(describe(error), error)
        }
    }

    /**
     * Catch-up from a whole-minute link: how far into the replay the point asked for is (up to
     * a minute). Skipped as soon as the replay has that much in; if it can't be (a stream with no
     * length, or one that only grows as it plays), the replay simply plays from the minute and
     * the seek bar already shows that time.
     */
    @Volatile private var leadSeekMs = 0L
    /** When to stop waiting for room to skip [leadSeekMs] (0: the replay hasn't answered yet). */
    @Volatile private var leadSeekUntil = 0L
    /** The first link built for the catch-up playing (see [onCatchupLinkWorked]). */
    @Volatile private var catchupPrimary: String? = null
    @Volatile private var catchupAskedAt = 0L
    @Volatile private var catchupLinkReported = false
    /** Called with the playlist id and the kind of catch-up link that played there. */
    var onCatchupLinkWorked: ((String, String) -> Unit)? = null

    /** Catch-up asked again in a row after freezing (see [checkStall]); back to 0 once it plays. */
    @Volatile private var catchupRestarts = 0
    /** A replay from the provider froze: ask for it again from where it stopped (true if done). */
    var onCatchupStalled: (() -> Boolean)? = null
    /**
     * A replay's copy ran dry on a save slower than it plays (see [copyStarved]): ask the provider
     * again from where it is, as a skip there does, over the picture kept (true if asked).
     */
    var onCatchupStarved: (() -> Boolean)? = null
    /**
     * The same replay froze because the provider sends it slower than it plays (each time
     * [copyStarved] steps in, and when asking again stops helping): the view model offers the
     * channel in a lower quality from the same provider, whose replay comes in faster.
     */
    var onCatchupSlow: (() -> Unit)? = null
    /**
     * A replay's copy played to the end of what was saved and nothing carried it on (see
     * [copyRanOut]): ask again from there (true if asked).
     */
    var onCatchupEnded: (() -> Boolean)? = null
    /**
     * A replay picked up after the background far into its link: ask the provider for it again
     * from [inStreamMs] into the link playing, as a skip there does, playing or paused as [plays]
     * says (true if asked, straight away).
     */
    var onCatchupResume: ((inStreamMs: Long, plays: Boolean) -> Boolean)? = null

    /**
     * Skips can go inside the replay now playing: one saved on this TV, an HLS replay, or the
     * live stream's rewind window. A TS replay straight from the provider can't: the player then
     * asks the provider for part of the file, which many providers don't handle (it froze).
     */
    fun catchupSeekInStream(): Boolean =
        replayLocal || archiveWallMapped || stalkerMimeType == StalkerMimeProbe.HLS || stalkerMimeType == StalkerMimeProbe.DASH || saysHls(currentUrl)

    /**
     * Faster Catch-Up Skipping: how much of the replay is saved on this box so far (ms into what
     * was asked for), while it plays from there; null otherwise.
     */
    fun replaySavedMs(): Long? =
        if (replayLocal && savedRecordingId == null && replayCache.session != null) replayCache.savedMs else null

    /** Seek thumbnails: the list of pieces of the catch-up being saved on this box (null if none). */
    val replayPlaylistFile: java.io.File?
        get() = if (replayLocal && savedRecordingId == null) replayCache.session?.playUri?.path?.let { java.io.File(it) } else null

    /** Seek thumbnails: what the provider needs on each stream request (Stalker portals). */
    val thumbnailPolicy: StalkerMediaPolicy? get() = stalkerMediaPolicy

    /** Seek thumbnails: the replay is a streamed (HLS) link that plays straight from the provider. */
    fun catchupIsHls(): Boolean =
        !replayLocal && (stalkerMimeType == StalkerMimeProbe.HLS || saysHls(currentUrl))

    /**
     * Moves the replay on to [url], another of its links: a TS link is saved while it plays, like
     * one that started as TS (true: its copy takes it from here), any other is played straight
     * from the provider. Paused or playing stays as it was: a replay asked for while paused stays
     * paused.
     */
    private fun playCatchupLink(p: ExoPlayer, ch: LiveChannel, url: String): Boolean {
        currentUrl = url
        if (saveFallback(ch, url)) return true
        archiveStartPending = true
        p.setMediaSource(buildMediaSource(url, ch.headers, isLive = false))
        p.prepare()
        return false
    }

    /** A TS catch-up link tried after another failed: saved while it plays (false if it can't be); paused stays paused. */
    private fun saveFallback(ch: LiveChannel, url: String): Boolean =
        catchupCache && ch.drm == null && !saysHls(url) &&
            startReplayCache(ch, url, plays = !replayPausedNow())

    /**
     * You skipped yourself: forget the automatic skip into the replay. Not while its copy is on
     * its way, where it's where the copy starts: a skip then moves it ([retargetCopy]) or asks the
     * provider again (which starts afresh). Cleared, the copy could start at its very beginning
     * just before that request replaced it, with sound, for the seconds a time zone is looked up.
     */
    fun cancelCatchupLead() {
        if (!copyOnItsWay()) leadSeekMs = 0L
    }

    private fun tryLeadSeek(p: ExoPlayer, w: androidx.media3.common.Timeline.Window) {
        val lead = leadSeekMs
        if (lead <= 0L) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (leadSeekUntil == 0L) leadSeekUntil = now + 6_000
        val dur = w.durationMs
        val room = w.isSeekable && dur != C.TIME_UNSET && (if (w.isDynamic) dur >= lead + 3_000 else dur > lead)
        if (room) {
            leadSeekMs = 0L
            farLeadDirect = false
            p.seekTo(lead)
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: the provider started on the whole minute; skipped the first ${lead / 1000} s to the point asked for")
        } else if (!w.isDynamic || now > leadSeekUntil) {
            // Played straight from the provider only because the point is far in, and this link
            // can't be moved there: saved as before instead, so it starts as close as it can and
            // later skips inside what's saved are instant. Before, it played from the show's start
            // and every skip past the first minutes went back there. The point is kept for the copy.
            val ch = currentChannel
            val url = currentUrl
            if (farLeadDirect && !replayLocal && ch != null && url != null) {
                farLeadDirect = false
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: this link can't be moved ${lead / 1000} s in; saving it instead")
                // Not from inside the player's own callback.
                scope.launch { if (currentUrl == url && !replayLocal) startReplayCache(ch, url, wantsToPlay(), ignoreFarLead = true) }
                return
            }
            leadSeekMs = 0L
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: playing from the whole minute (${lead / 1000} s early; this replay can't skip yet)")
        }
    }

    /** A provider's HLS catch-up just failed and its TS link is being tried (playlist id). */
    @Volatile private var hlsCatchupFailedOn: String? = null
    /** Called with the playlist id when that provider's catch-up works as TS but not HLS. */
    var onCatchupHlsUnsupported: ((String) -> Unit)? = null
    /** Called with the playlist id when that provider's catch-up plays as HLS. */
    var onCatchupHlsWorks: ((String) -> Unit)? = null

    /** Live channels whose HLS playlist couldn't be read: played as TS instead (shared with Multiview). */
    private val tsInstead: MutableSet<String> = LiveStreamFormats.tsInstead
    /** Live channels whose link looks like HLS but sends plain TS: read as TS (shared with Multiview). */
    private val plainTs: MutableSet<String> = LiveStreamFormats.plainTs

    /**
     * The TS version of an Xtream live link: ".../123.m3u8" -> ".../123.ts", and a link with no
     * ending at all (".../user/pass/123", which the server answers as HLS) -> ".../123.ts".
     * Null if it isn't one.
     */
    private fun tsLinkFor(url: String): String? = LiveStreamFormats.tsLinkFor(url)

    private fun isMalformedManifest(error: PlaybackException): Boolean = LiveStreamFormats.isMalformedManifest(error)

    // ---- start-up guard: a channel that made the app close while it was starting isn't
    // started by itself again next time (it used to close the app again, over and over).

    private val startGuard by lazy { context.getSharedPreferences("livetv_start_guard", Context.MODE_PRIVATE) }

    /** The channel that was starting when the app last closed unexpectedly (read once, at start-up). */
    val crashedWhileStarting: String? by lazy {
        val key = startGuard.getString("starting", null)
        val at = startGuard.getLong("at", 0L)
        startGuard.edit().clear().apply()
        key?.takeIf { System.currentTimeMillis() - at < 6 * 60 * 60 * 1000L }
    }

    private fun markStarting(key: String) {
        crashedWhileStarting // read the last one before it's replaced
        startGuard.edit().putString("starting", key).putLong("at", System.currentTimeMillis()).apply()
    }

    private fun clearStarting() {
        if (startGuard.contains("starting")) startGuard.edit().remove("starting").remove("at").apply()
    }

    /** Format tried for a link that doesn't say what it is (after "container unsupported"). */
    @Volatile private var forcedMime: String? = null
    @Volatile private var formatGuess = 0

    private fun isUnrecognizedFormat(error: PlaybackException): Boolean = LiveStreamFormats.isUnrecognizedFormat(error)

    /** A link for the start-up report, with the username and password hidden. */
    private fun redact(url: String): String =
        if (currentChannel?.stalkerRef != null || url.startsWith("stalker:")) "[Stalker stream]" else url.replace(Regex("""(/(?:timeshift|live|movie|series)/)[^/]+/[^/]+/"""), "$1***/***/")
            .replace(Regex("""(username|password)=[^&]*"""), "$1=***")
            // Xtream links without "/live": http://host:port/user/pass/123.ts
            .replace(Regex("""^(https?://[^/]+)/(?!\*\*\*)[^/]+/[^/]+/(\d+(?:\.[A-Za-z0-9]+)?(?:\?.*)?)$"""), "$1/***/***/$2")

    /**
     * A live channel failed twice in a row (or never started): Live TV may switch to a backup
     * channel. True when it did, and this channel isn't tried again.
     */
    var onLiveFailure: ((channelKey: String) -> Boolean)? = null

    /** Playlist ids served by Dispatcharr (see [com.nuvio.tv.livetv.model.PlaylistSource.isDispatcharr]). */
    var dispatcharrSources: () -> Set<String> = { emptySet() }

    /**
     * The channel playing comes from Dispatcharr, which buffers before sending and moves to a
     * channel's next stream when a viewer reconnects: given longer to start before a reconnect.
     */
    private fun slowStarter(): Boolean {
        val ch = currentChannel ?: return false
        return ch.sourceId in dispatcharrSources() ||
            com.nuvio.tv.livetv.model.PlaylistSource.isDispatcharrLink(currentUrl ?: ch.url)
    }

    /**
     * Sports: the next channel showing the same live game, when [failedKey] won't play or keeps
     * freezing ([tried]: channels already tried for it). Null when it isn't a live game's channel,
     * when none are left, or when "Switch to a backup channel" is off. Asked before [onLiveFailure].
     */
    var gameFailover: ((failedKey: String, tried: Set<String>) -> LiveChannel?)? = null
    private val gameTried = HashSet<String>()
    private var switchingForGame = false
    /** When the channel playing froze recently (not counting it never starting). */
    private val recentStalls = ArrayDeque<Long>()

    private fun tryGameFailover(ch: LiveChannel, why: String): Boolean {
        val f = gameFailover ?: return false
        gameTried += ch.key
        val next = runCatching { f(ch.key, gameTried.toSet()) }.getOrNull() ?: return false
        if (next.key == ch.key) return false
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Channel ${ch.name} $why: switched to ${next.name} (${next.group}), same game")
        com.nuvio.tv.livetv.record.LiveTvNotice.show(com.nuvio.tv.livetv.ui.LiveTvTexts.trf("{0} isn't working right now. Switched to {1}, showing the same game.", ch.name, next.name))
        switchingForGame = true
        try { play(next) } finally { switchingForGame = false }
        return true
    }
    /** Times the channel playing sat loading with nothing coming in. */
    private var neverStartedCount = 0

    private fun scheduleReconnect(reason: String, error: PlaybackException? = null) {
        val attempt = _state.value.reconnectAttempt + 1
        // Live channels that won't play: what failed and why, for the Start-Up Report (only
        // catch-up failures were written there, so "it won't play" reports had nothing to go on).
        if (_state.value.catchupTitle == null) {
            val detail = error?.takeIf { currentChannel?.stalkerRef == null }?.let { e ->
                generateSequence(e.cause) { it.cause }.take(4)
                    .map { c -> c.javaClass.simpleName + (c.message?.take(80)?.let { m -> ": $m" } ?: "") }
                    .joinToString(" ← ")
            }.orEmpty()
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Channel ${currentChannel?.name} failed (try $attempt): $reason" +
                    (error?.let { " (${it.errorCodeName})" } ?: "") +
                    (if (detail.isNotBlank()) " [$detail]" else "") +
                    (currentUrl?.let { " · ${redact(it)}" } ?: "")
            )
        }
        // A replay played straight from the provider that never played: when it's asked again far
        // into its link, its copy is used (see startReplayCache).
        // Only a link that came in: a Stalker link that couldn't be obtained is just asked again.
        if (_state.value.catchupTitle != null && !replayLocal && !archiveStarted && currentUrl != null) farDirectFailed = true
        _state.value = _state.value.copy(error = reason, isBuffering = false, reconnectAttempt = attempt)
        if (_state.value.catchupTitle == null && attempt == 2) {
            currentChannel?.let { if (tryGameFailover(it, "failed")) return }
            val key = currentChannel?.key
            if (key != null && onLiveFailure?.invoke(key) == true) return
        }
        if (!autoReconnect || attempt > MAX_RECONNECTS) return
        reconnectJob?.cancel()
        // Refused (often the account's connection limit): asking again at once is refused too, and
        // each try counts as one more connection while the provider still counts the last. Seen:
        // eight requests in 20 s, all refused.
        val refused = error?.let { e -> generateSequence<Throwable>(e) { it.cause }.take(8).any { c ->
            c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && c.responseCode in REFUSED_CODES
        } } == true
        reconnectJob = scope.launch {
            delay(if (refused) (attempt * 5_000L).coerceAtMost(15_000L) else (attempt * 2_000L).coerceAtMost(10_000L))
            ProviderLine.awaitFree()
            val ch = currentChannel ?: return@launch
            if (activeStalkerRef != null) {
                when {
                    _state.value.catchupTitle == null -> restartStalker()
                    // Its link never came in: as that request asked (another show, or a skip),
                    // not where the replay before it was.
                    currentUrl == null -> restartStalker(fetchLeadMs, fetchStartMs)
                    // A replay not moved to its point yet (its timeline never came in): that point, not 0.
                    else -> restartStalker(leadSeekMs.takeIf { it > 0 && !replayLocal } ?: _player?.currentPosition ?: 0L)
                }
                return@launch
            }
            val url = currentUrl ?: return@launch
            val p = _player ?: return@launch
            val resumeAt = ownReplayResumeAt(p)
            p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
            p.prepare()
            if (resumeAt > 0) p.seekTo(resumeAt)
            if (_state.value.catchupTitle != null && backgroundJob?.isActive == true) {
                // Home paused it: it stays paused, and plays on coming back if it was playing.
                // Before, it played behind the launcher until the line was let go.
                p.playWhenReady = false
            } else {
                // A paused replay stays paused (live channels play as before).
                p.playWhenReady = _state.value.catchupTitle == null || wantsToPlay()
            }
        }
    }

    private fun buildMediaSource(url: String, headers: Map<String, String>, isLive: Boolean): androidx.media3.exoplayer.source.MediaSource {
        adaptiveBuffer.sourceChanged()
        // A YouTube highlight from Sports: its picture and sound are two files, played together
        // the way trailers are (read in pieces, as YouTube slows down whole-file downloads).
        if (currentChannel?.sourceId == com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID) {
            com.nuvio.tv.livetv.sports.SportsVideos.soundFor(url)?.let { sound ->
                val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(com.nuvio.tv.data.trailer.YoutubeChunkedDataSourceFactory())
                return androidx.media3.exoplayer.source.MergingMediaSource(
                    factory.createMediaSource(MediaItem.fromUri(url)),
                    factory.createMediaSource(MediaItem.fromUri(sound))
                )
            }
        }
        return com.nuvio.tv.livetv.multiview.LiveTvMediaSourceBuilder(
            drm = currentChannel?.drm,
            forcedMime = forcedMime,
            bufferSize = bufferSize,
            stalkerPolicy = stalkerMediaPolicy,
            // This player asks again itself, more slowly, after a refusal (see scheduleReconnect).
            retryRefusals = false,
        ).build(url, headers, isLive)
    }

    private fun isAdaptiveRemoteLiveSource(): Boolean {
        val p = _player ?: return false
        val channel = currentChannel ?: return false
        if (_state.value.catchupTitle != null || savedRecordingId != null || savedRecordingPlaylist != null ||
            _recordingPlayback.value != null || _shiftStartedAt.value != null || timeshift.session != null ||
            replayLocal || alwaysBuffer || p.mediaItemCount != 1 || currentWindowIsPlaceholder(p)) return false
        if (channel.sourceId == com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID ||
            // Addons may reuse a live game's key for its replay. Remembering live playback
            // is not enough: ambiguous links and static replays keep their normal rate.
            (channel.sourceId == com.nuvio.tv.livetv.sports.SportsAddonSource.SOURCE_ID &&
                (!sawLive || !p.isCurrentMediaItemLive))) return false
        val scheme = p.currentMediaItem?.localConfiguration?.uri?.scheme
        return scheme.equals("http", true) || scheme.equals("https", true)
    }

    /**
     * Where a replay with its own timeline (an addon replay, a highlight) had got to, so reloading
     * it after a freeze or an error carries on there instead of starting over. Unset otherwise.
     */
    private fun ownReplayResumeAt(p: ExoPlayer): Long =
        if (_ownTimeline.value && !p.isCurrentMediaItemDynamic) p.currentPosition.coerceAtLeast(0L) else C.TIME_UNSET

    /**
     * Lets go of the player (and its hold on the audio output) right away. Called when Nuvio's
     * own player opens, so the two never hold the audio at the same time.
     */
    fun releaseNow() {
        _stops.value = _stops.value + 1
        if (_player != null) release()
    }

    private fun release() {
        lineWaitJob?.cancel()
        adaptiveBuffer.release()
        dropStalker()
        backgroundJob?.cancel()
        backgroundJob = null
        resumeAfterBackground = null
        clearStarting()
        stopShift()
        // A catch-up being saved goes too. Leaving Live TV (or opening Nuvio's own player) during
        // a replay used to leave it downloading to the end of the show in the background, its
        // copy (about 35 MB a minute) kept on the box until the next channel or app restart.
        stopReplayCache()
        reconnectJob?.cancel()
        stallJob?.cancel()
        stallJob = null
        _player?.removeListener(listener)
        _player?.release()
        _player = null
        currentChannel = null
        LiveWatching.sourceId = null
        currentUrl = null
        _ownTimeline.value = false
        _state.value = LivePlaybackState()
    }

    init {
        // This controller is application-scoped. Reopen active playback after a scope change
        // so a DefaultHttpDataSource opened before protection was enabled cannot keep reading.
        com.nuvio.vpn.android.VpnPlaybackProtection.observeSettings {
            scope.launch {
                val channel = currentChannel ?: return@launch
                val url = currentUrl ?: return@launch
                val active = _player ?: return@launch
                // A recording from Recordings is on this box (or the recordings server): reopen it the same way.
                if (savedRecordingId != null) {
                    val wasPlaying = active.playWhenReady
                    reloadSavedRecording(active.currentPosition.coerceAtLeast(0L))
                    active.playWhenReady = wasPlaying
                    return@launch
                }
                val resume = active.playWhenReady
                val position = active.currentPosition
                val live = _state.value.catchupTitle == null
                stopReplayCache()
                stopShift()
                active.stop()
                if (activeStalkerRef != null) { restartStalker(if (live) 0L else position); return@launch }
                active.setMediaSource(buildMediaSource(url, channel.headers, live))
                active.prepare()
                if (!live && position > 0) active.seekTo(position)
                active.playWhenReady = resume
            }
        }
    }

    companion object {
        /** A sports addon stream needs at least this much timeline to pause and skip in it by itself. */
        private const val OWN_MIN_MS = 60_000L
        /** A live sports addon stream with at least this much rewind window is paused and skipped in by itself. */
        private const val OWN_LIVE_WINDOW_MS = 5 * 60_000L
        /** This much further behind live than it normally plays counts as "behind live" in a sports addon game. */
        private const val OWN_BEHIND_MS = 8_000L
        private const val MAX_RECONNECTS = 8
        /** HTTP answers that mean the provider refused this account the stream (see scheduleReconnect). */
        private val REFUSED_CODES = setOf(401, 403)
        /** A recording from Recordings: reloads after an error or a freeze before saying it stopped. */
        private const val MAX_RECORDING_RELOADS = 3
        /** A recording from Recordings with no progress for this long while it should play = frozen. */
        private const val RECORDING_STALL_MS = 10_000L
        /** No progress for this long while it should be playing = stalled. */
        private const val STALL_MS = 15_000L
        /** An HLS catch-up link with nothing to show after this long moves on to its TS link. */
        private const val HLS_REPLAY_WAIT_MS = 6_000L
        /** A replay playing from its copy waits this long for more to be saved before asking again. */
        private const val REPLAY_WAIT_MS = 60_000L
        /**
         * A replay's copy starts playing once it holds this much past the point asked for: more
         * than the player plays before it next reads the copy's list (see [copyStartPoint]).
         */
        private const val REPLAY_COPY_AHEAD_MS = REPLAY_LIST_TARGET_SEC * 1_000L + 3_000L
        /** The longest a replay's copy waits for that: no longer than the player would have stopped for anyway. */
        private const val REPLAY_COPY_WAIT_MS = REPLAY_LIST_TARGET_SEC * 1_000L
        /**
         * How long a copy that carries a replay on from where it froze waits for that point (see
         * copyKeepsPoint): about what PureTv takes to send the whole minute up to it again at its
         * real-time start (under the waiting screen, whose own limit is a minute).
         */
        private const val REPLAY_KEEP_POINT_WAIT_MS = 45_000L
        /** About the fastest a provider sends a replay (times real time, 17x seen): more than that won't come in time. */
        private const val REPLAY_FASTEST_SAVE = 20L
        /**
         * Back from the background further than this into a replay saved on this box: asked of the
         * provider again from there (catch-up links name whole minutes, so one asked from that point
         * starts at most this much before it; the link playing starts further back).
         */
        private const val RESUME_ASK_AGAIN_MS = 60_000L
        /** A Stalker replay asked further in than this is played straight from the provider (see startReplayCache). */
        private const val STALKER_DIRECT_FROM_MS = 60_000L
        /** How far a replay's own clock may be from the time asked for before it counts as a different show. */
        private const val WRONG_SHOW_SLACK_MS = 10 * 60_000L
        /** Two freezes within this long: the channel counts as failing (live games only). */
        private const val FREEZE_WINDOW_MS = 3 * 60_000L
        private val FORMAT_GUESSES = LiveStreamFormats.FORMAT_GUESSES
        /** Hardware decoder failures worth one try on a software decoder (not "taken away"). */
        private val VIDEO_DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        private val TRANSIENT_VIDEO_DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        /** No picture this long after the video moved to a new screen = stuck. */
        private const val FRAME_TIMEOUT_MS = 2_500L
        /** Still loading this long after the video moved to a new screen = stuck. */
        private const val LOADING_AFTER_MOVE_MS = 7_000L
        /** Extra wait for a first picture on Dispatcharr 4K/HDR channels after the video moved. */
        private const val BIG_PICTURE_EXTRA_MS = 3_000L
        /** Extra time to start for a Dispatcharr channel that's still coming in (on top of [STALL_MS]). */
        private const val SLOW_START_EXTRA_MS = 15_000L
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
        private const val TAG = "LiveTvPlayback"

        /** The link says it's HLS ("m3u8" in it, before any "?"). */
        private fun saysHls(url: String?): Boolean = url?.substringBefore('?')?.contains("m3u8", ignoreCase = true) == true

        /** An Xtream catch-up link for HLS (…/timeshift/….m3u8). */
        private fun isHlsReplayLink(url: String?): Boolean =
            url?.substringBefore('?')?.let { it.contains("/timeshift/") && it.endsWith(".m3u8", true) } == true

        /** An Xtream catch-up link for TS (…/timeshift/….ts). */
        private fun isTsReplayLink(url: String?): Boolean =
            url?.substringBefore('?')?.let { it.contains("/timeshift/") && it.endsWith(".ts", true) } == true
    }
}

/**
 * The playlist Live TV's player is streaming from right now (null when nothing is playing). A
 * recording that drops checks this: many accounts allow one connection at a time, and watching
 * another channel from the same provider is the usual reason a recording gets cut off.
 */
object LiveWatching {
    /** Also tells the app a channel is on (Nuvio's idle slideshow stays away while one is). */
    val active = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    var sourceId: String?
        get() = active.value
        set(value) { active.value = value }
}
