package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import com.nuvio.tv.livetv.stalker.StalkerPlayback
import com.nuvio.tv.livetv.stalker.StalkerMediaPolicy
import com.nuvio.tv.livetv.stalker.StalkerMimeProbe

/**
 * A replay's copy (kept whole) lists its pieces with this target length. The player reads a
 * growing list again only about once per target length, so it sees new pieces this often.
 */
internal const val REPLAY_LIST_TARGET_SEC = 10

/**
 * Pause and rewind live TV, for channels without catch-up: when you pause, the channel is
 * recorded to the device (one connection to the provider, the same one playback used), and
 * play continues from the recording, behind live. "Go live" drops it.
 *
 *  - TS streams: cut into short pieces with a local playlist as they arrive ([TsSegmenter]),
 *    so skipping back and forward works the same as on HLS channels.
 *  - HLS (m3u8) streams: each segment is saved as it appears, from about where you paused,
 *    with a local playlist ([HlsPieces]; separate sound streams too); older segments are
 *    dropped past the time limit.
 *
 * Everything lives in a "timeshift" folder next to the recordings (the app's own folder on the
 * drive set for recordings: Android/data/…/files on this device, so Android's "Clear cache"
 * doesn't reach it) and is deleted when you go live, change channel or leave Live TV; anything
 * left over (a crash, the box switched off) goes when the app next starts.
 */
class LiveTvTimeshift(private val context: Context) {

    enum class Kind { TS, HLS }

    /** What's being recorded: where it started (wall clock) and how to play it. */
    data class Session(
        val kind: Kind,
        val startedAtMs: Long,
        val playUri: Uri,
        val dir: File
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** The recording's open connections to the provider, closed at once by [stop]. */
    @Volatile private var lines: java.util.concurrent.ConcurrentLinkedDeque<HttpURLConnection>? = null
    @Volatile var session: Session? = null
        private set
    @Volatile private var recording = false

    /** Time dropped from the start of an HLS recording (past the time limit). */
    @Volatile var trimmedMs: Long = 0L
        private set

    /** TS: how much has been saved so far (ms from the start), for the seek bar's saved part. */
    @Volatile var savedMs: Long = 0L
        private set

    val isRecording: Boolean get() = recording

    /**
     * HLS: how much the first look at the provider's playlist saved (ms). Those parts end at the
     * live edge, which is ahead of where you paused (players play a little behind live): resuming
     * starts this far in, less how far behind live you were, so it carries on from where you paused.
     */
    @Volatile var firstBatchMs: Long = 0L
        private set

    /** Enough room on the device for [minutes] of recording (rough: about 35 MB a minute). */
    /** Where the buffer is kept: the drive set for recordings (this device's cache otherwise). */
    @Volatile var baseDir: File = File(context.cacheDir, "timeshift")

    fun hasRoomFor(minutes: Int): Boolean =
        runCatching { (baseDir.takeIf { it.exists() || it.mkdirs() } ?: context.cacheDir).usableSpace > (minutes * 35L + 300L) * 1024 * 1024 }.getOrDefault(false)

    /**
     * Starts recording [url] (with [headers]). Returns the session to play, or null if this
     * stream can't be recorded (encrypted HLS, a link that won't open…).
     */
    fun start(
        url: String,
        headers: Map<String, String>,
        /** Older parts dropped past this (null: keep it all, for a catch-up replay). */
        keepMinutes: Int?,
        /** Open the stream again when it ends (live); false for a replay, which simply ends. */
        reconnect: Boolean = true,
        /** How it's named in the Start-Up Report. */
        label: String = "Pause buffer",
        /** HLS, live: how far behind live the player was (where you paused); null: the usual few pieces. */
        leadMs: Long? = null,
        /** HLS with separate sound: the language being listened to. */
        audioLanguage: String? = null,
        refreshStream: (suspend () -> StalkerPlayback)? = null,
        policy: StalkerMediaPolicy? = null,
        mimeType: String? = null,
    ): Session? {
        if (mimeType == StalkerMimeProbe.DASH) return null
        stop()
        val dir = File(baseDir, System.currentTimeMillis().toString()).apply { mkdirs() }
            .takeIf { it.exists() } ?: File(context.cacheDir, "timeshift/" + System.currentTimeMillis()).apply { mkdirs() }
        val isHls = mimeType == StalkerMimeProbe.HLS || url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true)
        val now = System.currentTimeMillis()
        trimmedMs = 0L
        savedMs = 0L
        firstBatchMs = 0L
        recording = true
        val local = File(dir, "index.m3u8")
        // The session is set before its recording starts: a recording carries on only while
        // it's still this one (see [alive]).
        val s = Session(if (isHls) Kind.HLS else Kind.TS, now, Uri.fromFile(local), dir)
        session = s
        ACTIVE += s.dir
        val open = java.util.concurrent.ConcurrentLinkedDeque<HttpURLConnection>()
        lines = open
        job = if (isHls) {
            scope.launch { recordHls(url, headers, dir, local, keepMinutes ?: 24 * 60, live = reconnect, leadMs = leadMs, audioLanguage = audioLanguage, label = label, refreshStream = refreshStream, initialPolicy = policy, lines = open) }
        } else {
            scope.launch { recordTs(url, headers, dir, keepMinutes, reconnect, label, refreshStream, policy, open) }
        }
        return s
    }

    /**
     * This recording (the one saving into [dir]) is still the one wanted. A new one (another
     * replay, say) used to set the shared flag back on, so the old one carried on writing into
     * its deleted folder, and when it finally stopped it switched the new one off with it
     * ("finished after 2s, 0s recorded", then 30 s or more before the replay played).
     */
    private fun alive(dir: File): Boolean = recording && session?.dir == dir

    /** Stops recording and deletes it. */
    fun stop() {
        recording = false
        val old = job
        old?.cancel()
        job = null
        // Its connection is closed now, not once its current read returns (up to 20 s on a
        // provider that stalls): the next request (a skip, live, another replay) went out while
        // the provider still counted this one, and was held for seconds or refused.
        ProviderLine.stopping(old)
        lines?.let { open ->
            lines = null
            val now = generateSequence { open.pollFirst() }.toList()
            if (now.isNotEmpty()) scope.launch { now.forEach { runCatching { it.disconnect() } } }
        }
        session?.dir?.let { d ->
            ACTIVE -= d
            scope.launch {
                runCatching { d.deleteRecursively() }
                // The recording only notices it was stopped once its current read from the
                // provider returns, and could still write a piece or its playlist into the folder
                // as it was being deleted (left behind until the next clean-up, 15 minutes on).
                // Once it has really finished, whatever it left goes too.
                withTimeoutOrNull(30_000L) { old?.join() }
                if (d.exists()) runCatching { d.deleteRecursively() }
            }
        }
        session = null
        trimmedMs = 0L
        savedMs = 0L
        // Leftovers from earlier (a crash, the app or the box switched off while paused).
        // Anything not written to for 15 minutes isn't being recorded any more: a buffer in use
        // gets a new piece every few seconds.
        cleanLeftovers(listOf(File(context.cacheDir, "timeshift"), baseDir), olderThanMs = 15 * 60_000L)
    }

    /**
     * Deletes buffers and saved replays left in [dirs] (all of them when [olderThanMs] is 0,
     * except the one being recorded now).
     */
    fun cleanLeftovers(dirs: List<File>, olderThanMs: Long = 0L) {
        scope.launch {
            val keep = ACTIVE.toSet()
            runCatching {
                dirs.distinct().flatMap { it.listFiles()?.toList().orEmpty() }.forEach { f: File ->
                    if (f in keep) return@forEach
                    val newest = if (f.isDirectory) (f.listFiles()?.maxOfOrNull { it.lastModified() } ?: f.lastModified()) else f.lastModified()
                    if (olderThanMs == 0L || newest < System.currentTimeMillis() - olderThanMs) f.deleteRecursively()
                }
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection = LiveHttp.open(url, headers, 20_000)

    /** Milliseconds as seconds with one decimal, for the Start-Up Report. */
    private fun secs(ms: Long): String = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)

    // ------------------------------------------------------------------ TS

    private suspend fun recordTs(
        url: String,
        headers: Map<String, String>,
        dir: File,
        keepMinutes: Int?,
        reconnect: Boolean = true,
        label: String = "Pause buffer",
        refreshStream: (suspend () -> StalkerPlayback)? = null,
        initialPolicy: StalkerMediaPolicy? = null,
        /** This recording's open connections, which [stop] closes at once. */
        lines: MutableCollection<HttpURLConnection>,
    ) {
        // Pieces past the time limit are dropped as new ones come in (it records on, behind you).
        // A replay (kept whole): a 10 s target, so playback close to what's saved sees new
        // pieces within seconds instead of freezing up to 20 s.
        val segmenter = TsSegmenter(dir, keepSec = keepMinutes?.let { it * 60.0 }, fixedTargetSec = if (keepMinutes == null) REPLAY_LIST_TARGET_SEC else null) { _ -> }
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        val began = System.currentTimeMillis()
        var opened = false
        // The recording before this one (the replay before a skip, say) is closed first: asked
        // while the provider still counted it, a replay was held 9 s before it answered, and a
        // live channel was refused ("too many connections").
        ProviderLine.awaitFree()
        // The player has only just let go of its connection: give the provider a moment, or
        // accounts with one connection can refuse (or later cut) this one. A replay waits less:
        // every second here is a second more before its picture (2.5 s of an 11 s start). If the
        // provider still counts the old connection and refuses, the next try comes 2 s later,
        // which is still later than the one try used to come.
        val waitLeft = (if (reconnect) 2_500L else 1_000L) - (System.currentTimeMillis() - began)
        if (waitLeft > 0) delay(waitLeft)
        // A replay, for the Start-Up Report: when the provider answered, and whether the first
        // piece (what playback waits for) has been reported yet.
        var answeredAt = 0L
        var firstPieceReported = false
        var attempt = 0
        var policy = initialPolicy
        // A replay: everything saved so far (all connections), the replay's full size when the
        // provider says it, and how many times running carrying on brought nothing.
        var totalBytes = 0L
        var knownLength = -1L
        var resumeTries = 0
        // Whether the provider carries a replay on from the middle of the file (null: not
        // known yet), and when the last new connection for full speed was asked for.
        var rangeWorks: Boolean? = null
        var lastBoostAt = 0L
        try {
            // Keeps going until you go live or change channel: a dropped connection is opened
            // again, with the gap marked, instead of ending the buffer (which sent you back to
            // live after half a minute or so).
            while (scope.isActive && alive(dir)) {
                // A replay cut off part way: ask for the rest of the same file (from the byte it
                // stopped at), so it carries on seamlessly instead of the save ending there and
                // playback stopping at that point.
                val resuming = !reconnect && totalBytes > 0
                if (attempt > 0) {
                    if (!resuming) segmenter.reconnected()
                    delay((attempt * 2_000L).coerceAtMost(8_000L))
                }
                attempt++
                val t0 = System.currentTimeMillis()
                var bytes = 0L
                var complete = false
                val why = try {
                    // Stalker: a fresh link is the live one, so not for carrying on a replay.
                    val fresh = if (attempt > 1 && !resuming) refreshStream?.invoke() else null
                    if (fresh?.mimeType == StalkerMimeProbe.DASH || fresh?.mimeType == StalkerMimeProbe.HLS) {
                        if (session?.dir == dir) recording = false
                        throw java.io.IOException("The Stalker stream changed its buffer format. Retry playback.")
                    }
                    if (fresh != null) { if (policy !== fresh.policy) policy?.retire(); policy = fresh.policy }
                    val requestHeaders = if (resuming) headers + ("Range" to "bytes=$totalBytes-") else headers
                    // Sent from here on: even a request cut (by [stop]) or failed before the provider
                    // answered counts as a connection the provider has to see go.
                    opened = true
                    val c = LiveHttp.open(fresh?.url ?: url, fresh?.headers ?: requestHeaders, 20_000, policy, lines)
                    // Let go of it as soon as it's done with (a dropped or finished connection
                    // included), and [stop] closes it at once meanwhile.
                    try {
                        val code = c.responseCode
                        if (!reconnect && !firstPieceReported) {
                            answeredAt = System.currentTimeMillis()
                            report.add("$label: the provider answered in ${secs(answeredAt - t0)} s (HTTP $code), ${secs(answeredAt - began)} s after asking")
                        }
                        when {
                            resuming && code == 416 -> { c.disconnect(); complete = true; "all of it is saved" }
                            code !in 200..299 -> { c.disconnect(); "HTTP $code" }
                            else -> {
                                // The provider sent the replay from its start again (it can't carry on
                                // from the middle): what's already saved is read again and skipped.
                                var skip = if (resuming && code == 200) totalBytes else 0L
                                if (resuming) {
                                    rangeWorks = code == 206
                                    report.add(
                                        if (code == 206) "$label: the provider carries on from ${totalBytes / 1024 / 1024} MB"
                                        else "$label: the provider can't carry on from the middle; reading the first ${totalBytes / 1024 / 1024} MB again"
                                    )
                                }
                                if (knownLength < 0) knownLength = when (code) {
                                    200 -> c.contentLengthLong
                                    206 -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                                    else -> -1L
                                }
                                c.inputStream.use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var end = "the provider ended the stream"
                                    var lastNote = System.currentTimeMillis()
                                    var noteBytes = totalBytes
                                    var noteSaved = savedMs
                                    // Speed check for a replay: the provider sends the start at full
                                    // speed, then slows to about real time (seen: 17x, then 1.2x after
                                    // about 16 minutes), so the saved part hardly gets ahead of
                                    // playback. A new connection, carrying on from the same byte, may
                                    // get full speed again.
                                    var checkAt = System.currentTimeMillis()
                                    var checkSaved = savedMs
                                    // The fastest this connection went: a new one is only asked for
                                    // when it came fast and then slowed right down (a provider that's
                                    // simply slow gains nothing from it; each new connection costs a
                                    // couple of seconds, and playback froze catching up).
                                    var peakRate = 0.0
                                    while (scope.isActive && alive(dir)) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        var off = 0
                                        if (skip > 0) {
                                            off = minOf(n.toLong(), skip).toInt()
                                            skip -= off
                                            if (off == n) continue
                                            checkAt = System.currentTimeMillis(); checkSaved = savedMs
                                        }
                                        val m = n - off
                                        bytes += m
                                        totalBytes += m
                                        segmenter.write(buf, off, m)
                                        // Only for the save still going: one just replaced (a skip
                                        // asking the provider again) wrote its own length over the new
                                        // save's for a moment, which a replay's copy waits on to start.
                                        if (session?.dir == dir) {
                                            trimmedMs = (segmenter.trimmedSec * 1000).toLong()
                                            savedMs = (segmenter.totalSec * 1000).toLong()
                                        }
                                        if (!reconnect && !firstPieceReported && segmenter.totalSec > 0) {
                                            firstPieceReported = true
                                            val at = System.currentTimeMillis()
                                            report.add(
                                                "$label: first piece ready ${secs(at - began)} s after asking, ${secs(at - answeredAt)} s after the provider answered " +
                                                    "(${String.format(java.util.Locale.US, "%.1f", segmenter.totalSec)} s of the show, " +
                                                    "${if (segmenter.lastCutClean) "cut where a picture starts" else "no picture start marked: cut by length"})"
                                            )
                                        }
                                        // A replay: how fast it's coming in, every 30 s (seconds of
                                        // show saved per second, and the speed), to see a provider
                                        // slowing it to real time or a stall.
                                        val nowMs = System.currentTimeMillis()
                                        if (!reconnect && nowMs - lastNote >= 30_000) {
                                            val secs = (nowMs - lastNote) / 1000.0
                                            report.add(
                                                "$label: ${savedMs / 1000}s saved (${totalBytes / 1024 / 1024} MB), " +
                                                    "last ${secs.toInt()}s: ${String.format(java.util.Locale.US, "%.1f", (savedMs - noteSaved) / 1000.0 / secs)}x real time, " +
                                                    "${(totalBytes - noteBytes) / 1024 / secs.toLong().coerceAtLeast(1)} KB/s"
                                            )
                                            lastNote = nowMs; noteBytes = totalBytes; noteSaved = savedMs
                                        }
                                        if (!reconnect && nowMs - checkAt >= 20_000) {
                                            val rate = (savedMs - checkSaved) / (nowMs - checkAt).toDouble()
                                            checkAt = nowMs; checkSaved = savedMs
                                            val throttled = peakRate >= 4.0 && rate < 2.0 && rate < peakRate * 0.4
                                            if (rate > peakRate) peakRate = rate
                                            if (throttled && rangeWorks != false && nowMs - lastBoostAt >= 60_000) {
                                                lastBoostAt = nowMs
                                                end = "slowed to ${String.format(java.util.Locale.US, "%.1f", rate)}x real time; new connection for full speed"
                                                break
                                            }
                                        }
                                    }
                                    if (!alive(dir)) end = "stopped"
                                    // Asked for the rest and there was none: it was all there.
                                    if (resuming && code == 206 && bytes == 0L) complete = true
                                    end
                                }
                            }
                        }
                    } finally {
                        lines.remove(c)
                        runCatching { c.disconnect() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (refreshStream != null) "Stalker stream unavailable" else e.message ?: e.javaClass.simpleName
                }
                if (!alive(dir)) break
                if (!reconnect) {
                    if (knownLength > 0 && totalBytes >= knownLength) complete = true
                    if (bytes > 0) resumeTries = 0 else if (resuming) resumeTries++
                    // Saved to its end, or it can't be carried on (playback then asks the provider
                    // again from where it got to): the save ends here.
                    if (complete || resumeTries >= 6 || (totalBytes == 0L && attempt >= 2)) {
                        report.add("$label: ${totalBytes / 1024 / 1024} MB saved ($why)")
                        break
                    }
                    if (totalBytes > 0) {
                        report.add("$label: connection ended after ${(System.currentTimeMillis() - t0) / 1000}s ($why); carrying on from ${totalBytes / 1024 / 1024} MB")
                        if (bytes > 0) attempt = 1
                        continue
                    }
                }
                report.add("$label: connection ended after ${(System.currentTimeMillis() - t0) / 1000}s, ${bytes / 1024} KB ($why); reconnecting")
                // Got nothing at all several times running: give up (the provider won't serve it).
                if (bytes == 0L && attempt >= 9) { report.add("Pause buffer: gave up (no data)"); break }
                if (bytes > 0) attempt = 1
            }
        } finally {
            // Its connection is closed by now (the next request waits for this, see ProviderLine).
            if (opened) ProviderLine.closed()
            report.add("$label finished after ${(System.currentTimeMillis() - began) / 1000}s (${segmenter.totalSec.toInt()}s recorded)")
            runCatching { segmenter.finish(ended = true) }
            if (session?.dir == dir) savedMs = (segmenter.totalSec * 1000).toLong()
            if (session?.dir == dir) recording = false
        }
    }

    // ------------------------------------------------------------------ HLS

    private suspend fun recordHls(
        url: String,
        headers: Map<String, String>,
        dir: File,
        local: File,
        keepMinutes: Int,
        /** Live: start at the newest pieces. A replay: save it all from its start. */
        live: Boolean,
        leadMs: Long?,
        audioLanguage: String?,
        label: String,
        refreshStream: (suspend () -> StalkerPlayback)? = null,
        initialPolicy: StalkerMediaPolicy? = null,
        /** This recording's open connections, which [stop] closes at once. */
        lines: java.util.concurrent.ConcurrentLinkedDeque<HttpURLConnection>,
    ) {
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        var policy = initialPolicy
        var opened = false
        // Each piece's connection is closed by HlsPieces once read; only the last few are kept
        // here, for [stop] to close one still being read.
        val connect: (String, Map<String, String>) -> HttpURLConnection = { address, requestHeaders ->
            // Stopped: no new request (the pieces left to fetch went on asking after [stop]).
            if (!alive(dir)) throw java.io.IOException("Recording stopped")
            opened = true
            LiveHttp.open(address, requestHeaders, 20_000, policy, lines).also { while (lines.size > 8) lines.pollFirst() }
        }
        // The recording before this one is closed first (see recordTs).
        ProviderLine.awaitFree()
        try {
            opened = true
            val (firstText, firstUrl) = LiveHttp.fetch(url, headers, policy, lines)
            val choice = if (HlsPieces.isMaster(firstText)) {
                HlsPieces.pick(firstUrl, firstText, audioLanguage) ?: return
            } else null
            val videoUrl = choice?.videoUrl ?: firstUrl
            val audioUrl = choice?.audioUrl
            val video = HlsPieces.Track(videoUrl, dir, if (audioUrl != null) "v" else "", headers, connect)
            val audio = audioUrl?.let { HlsPieces.Track(it, dir, "a", headers, connect) }
            // Stopped: the piece being read and those left are dropped at once.
            video.stopNow = { !alive(dir) }
            audio?.stopNow = { !alive(dir) }
            val videoFile = if (audio != null) File(dir, "video.m3u8") else local
            val audioFile = File(dir, "audio.m3u8")
            var vm = if (choice == null) HlsPieces.parseMedia(firstText) else video.fetch()
            var am = audio?.fetch()
            // Encrypted streams aren't recorded (the keys would have to be saved too).
            if (vm.encrypted || am?.encrypted == true) {
                Log.w(TAG, "Encrypted HLS: not recorded")
                report.add("$label: encrypted stream, can't be saved")
                if (session?.dir == dir) recording = false
                return
            }
            if (audio != null) report.add("$label: saving picture and sound separately (sound: ${choice?.audioLanguage ?: "default"})")
            var masterWritten = false
            var lastProgressAt = System.currentTimeMillis()
            var lastSavedName: String? = null
            suspend fun refreshPortal() {
                val fresh = refreshStream?.invoke() ?: return
                if (fresh.mimeType == StalkerMimeProbe.DASH) throw java.io.IOException(StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE)
                if (policy !== fresh.policy) policy?.retire()
                policy = fresh.policy
                val (text, servedAt) = LiveHttp.fetch(fresh.url, fresh.headers, policy, lines)
                val refreshed = if (HlsPieces.isMaster(text)) HlsPieces.pick(servedAt, text, audioLanguage) else null
                if ((refreshed?.audioUrl != null) != (audio != null)) throw IllegalStateException("The portal changed its audio format.")
                video.url = refreshed?.videoUrl ?: servedAt
                audio?.url = refreshed?.audioUrl ?: audio?.url.orEmpty()
                vm = if (refreshed == null) HlsPieces.parseMedia(text) else video.fetch()
                am = audio?.fetch()
                if (vm.encrypted || am?.encrypted == true) throw IllegalStateException("Encrypted portal streams cannot be buffered.")
                lastProgressAt = System.currentTimeMillis()
            }
            while (scope.isActive && alive(dir)) {
                // Live: from about where you are (players play a few pieces behind the newest).
                val lead = if (live) (leadMs ?: (vm.target * 3_000L)) else null
                video.saveNew(vm) { m -> if (lead == null) 0 else HlsPieces.startIndex(m.pieces, lead) }
                if (audio != null && am != null) {
                    audio.saveNew(am!!) { a -> if (lead == null) 0 else HlsPieces.matchingStart(a.pieces, video) }
                }
                // Keep only the time limit's worth.
                val keepSec = keepMinutes * 60.0
                trimmedMs += (video.trim(keepSec) * 1000).toLong()
                audio?.trim(keepSec)
                if (firstBatchMs == 0L && video.saved.isNotEmpty()) firstBatchMs = (video.totalSec * 1000).toLong()
                if (audio == null) {
                    video.write(local, ended = false)
                } else if (video.saved.isNotEmpty() && audio.saved.isNotEmpty()) {
                    audio.write(audioFile, ended = false)
                    video.write(videoFile, ended = false)
                    if (!masterWritten) {
                        HlsPieces.writeMaster(local, choice!!, videoFile.name, audioFile.name)
                        masterWritten = true
                    }
                }
                if (video.saved.lastOrNull()?.name != lastSavedName) { lastSavedName = video.saved.lastOrNull()?.name; lastProgressAt = System.currentTimeMillis() }
                else if (refreshStream != null && live && System.currentTimeMillis() - lastProgressAt > 20_000L) refreshPortal()
                delay((vm.target * 1000L / 2).coerceIn(1_000L, 6_000L))
                try {
                    vm = video.fetch()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    delay(2_000)
                    if (refreshStream != null) refreshPortal()
                    continue
                }
                if (audio != null) am = runCatching { audio.fetch() }.getOrNull()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = if (refreshStream != null) "Stalker stream unavailable" else e.message ?: e.javaClass.simpleName
            Log.w(TAG, "HLS recording stopped: $problem")
            report.add("$label: HLS saving stopped ($problem)")
        } finally {
            if (opened) ProviderLine.closed()
            if (session?.dir == dir) recording = false
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String =
        open(url, headers).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Plays a file that's still being written (the TS recording): at the end of what's written
     * so far it waits for more, instead of ending, while recording carries on.
     */
    inner class GrowingFileDataSource : BaseDataSource(false) {
        private var file: RandomAccessFile? = null
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val f = RandomAccessFile(File(dataSpec.uri.path ?: ""), "r")
            f.seek(dataSpec.position)
            file = f
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val f = file ?: return C.RESULT_END_OF_INPUT
            var waited = 0
            while (true) {
                val n = f.read(buffer, offset, length)
                if (n > 0) { bytesTransferred(n); return n }
                if (!recording || waited > 30_000) return C.RESULT_END_OF_INPUT
                Thread.sleep(150)
                waited += 150
            }
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            runCatching { file?.close() }
            file = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { GrowingFileDataSource() }

    private companion object {
        const val TAG = "LiveTvTimeshift"
        /** Buffers and saved replays in use right now (by any player): never cleaned up. */
        val ACTIVE: MutableSet<File> = java.util.Collections.synchronizedSet(HashSet())
    }
}
