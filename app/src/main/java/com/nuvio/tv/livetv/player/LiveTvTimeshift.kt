package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
 * How much of a replay's save is asked for again when it carries on over a new connection, and
 * checked to be the very bytes already saved. Some providers make the replay afresh for each
 * request: "the same byte" is then another moment of the show (seen: the picture froze and the
 * sound went 35 s back), so it's carried on by the stream's clock instead (see TsSegmenter.rejoin).
 */
internal const val SPLICE_CHECK_BYTES = 188 * 1024

/** The last bytes written, up to [size] (see [SPLICE_CHECK_BYTES]). */
internal class SavedTail(private val size: Int) {
    private val ring = ByteArray(size)
    private var next = 0
    /** How many bytes it holds (at most [size]). */
    var filled = 0
        private set

    fun add(b: ByteArray, off: Int, len: Int) {
        if (len >= size) {
            System.arraycopy(b, off + len - size, ring, 0, size)
            next = 0; filled = size
            return
        }
        val first = minOf(len, size - next)
        System.arraycopy(b, off, ring, next, first)
        if (len > first) System.arraycopy(b, off + first, ring, 0, len - first)
        next = (next + len) % size
        filled = minOf(size, filled + len)
    }

    /** The last [n] bytes written, oldest first ([n] at most [filled]). */
    fun last(n: Int): ByteArray {
        require(n in 0..filled)
        val out = ByteArray(n)
        val start = ((next - n) % size + size) % size
        val first = minOf(n, size - start)
        System.arraycopy(ring, start, out, 0, first)
        if (n > first) System.arraycopy(ring, 0, out, first, n - first)
        return out
    }
}

/** [len] bytes of [a] from [aOff] are the same as those of [b] from [bOff]. */
internal fun sameBytes(a: ByteArray, aOff: Int, b: ByteArray, bOff: Int, len: Int): Boolean {
    for (i in 0 until len) if (a[aOff + i] != b[bOff + i]) return false
    return true
}

/**
 * Whether a replay's save, coming in at [rate] (seconds of show saved per second over the last
 * 20 s) after a best of [peakRate], should carry on over a new connection: it came fast, then the
 * provider slowed it right down (seen: 17x, then 1.2x after about 16 minutes); a new connection
 * may get full speed again. Not for a save that's simply slow, even well under real time: on
 * providers that make the replay afresh for each request it can't be carried on (see
 * [SPLICE_CHECK_BYTES]), and asking each time cost a 12 s stop and a replay from the whole minute,
 * over and over (tried, then dropped).
 */
internal fun replaySaveThrottled(peakRate: Double, rate: Double): Boolean =
    peakRate >= 4.0 && rate < 2.0 && rate < peakRate * 0.4

/**
 * Whether a replay's save has caught up with what has aired (a show still on air): the copy,
 * from [linkStartMs] (wall clock; null: not known), holds [savedMs], which reaches within
 * [NEAR_AIRED_MS] of [nowMs]. From there it can only come in as it airs, so a slower save isn't
 * the provider holding it back, and a new connection can't make it faster. Seen on M6 on
 * 2026-11-24: one was asked for all the same (6.4x, then 0.3x), and the provider carried on 17 s
 * further: a jump in the picture.
 */
internal fun replaySaveNearAired(linkStartMs: Long?, savedMs: Long, nowMs: Long): Boolean =
    linkStartMs != null && linkStartMs + savedMs >= nowMs - NEAR_AIRED_MS

/** See [replaySaveNearAired]: the provider's own archive runs a minute or so behind the channel. */
internal const val NEAR_AIRED_MS = 3L * 60_000

/**
 * Providers (by host) that sent other bytes when a replay was carried on from the middle (see
 * [SPLICE_CHECK_BYTES]): carried on by the stream's clock there, and a new connection for full
 * speed only when the save falls behind playback, a few minutes apart at most (see
 * [RESENDING_BOOST_GAP_MS]). Until the app restarts.
 */
private val RESENDING_HOSTS: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

internal fun providerResends(url: String): Boolean = hostOf(url)?.let { it in RESENDING_HOSTS } == true

internal fun markProviderResends(url: String) { hostOf(url)?.let { RESENDING_HOSTS += it } }

/**
 * On a provider that resends (see [providerResends]), new connections for full speed at least
 * this far apart: each one makes the replay afresh, and when the stream's clock can't be gone by
 * it shows as a jump in the picture. One now and then is fine; close together, it isn't.
 */
internal const val RESENDING_BOOST_GAP_MS = 5L * 60_000

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
     * A replay's save dropping what the provider sent again after a new connection, until the
     * stream's clock is back where it stopped (see TsSegmenter.rejoin): nothing new is saved for
     * up to half a minute, which isn't the save running dry.
     */
    @Volatile var catchingUp: Boolean = false
        private set

    /**
     * A replay: when the provider first answered this recording's request (wall clock, ms), 0
     * until then. Its copy waits for the first piece counting from there (see the playback
     * controller's startReplayCache): a slow provider answers late, then sends at once.
     */
    @Volatile var answeredAtMs: Long = 0L
        private set

    /** Where the buffer is kept: the drive set for recordings (this device's cache otherwise). */
    @Volatile var baseDir: File = File(context.cacheDir, "timeshift")

    /** Enough room on the device for [minutes] of recording (rough: about 35 MB a minute). */
    fun hasRoomFor(minutes: Int): Boolean =
        runCatching {
            // What the recording just stopped is still being deleted counts as free: a replay's
            // copy asked again (a skip, another replay) found the old copy still there and was
            // played straight from the provider (seen on a box with 4 GB free, the old copy 0.6 GB).
            (baseDir.takeIf { it.exists() || it.mkdirs() } ?: context.cacheDir).usableSpace + releasing.get() > (minutes * 35L + 300L) * 1024 * 1024
        }.getOrDefault(false)

    /** TS: how much of it is saved so far (bytes), counted free once it's stopped (see [releasing]). */
    @Volatile private var savedBytes = 0L
    /** Bytes of stopped recordings still being deleted (see [hasRoomFor]). */
    private val releasing = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Starts recording [url] (with [headers]). Returns the session to play, or null if this
     * stream can't be recorded (encrypted HLS, a link that won't open…).
     */
    @Synchronized fun start(
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
        /** A replay: when (wall clock) its link starts, to carry on with what airs after it (see recordTs). */
        linkStartMs: Long? = null,
    ): Session? {
        if (mimeType == StalkerMimeProbe.DASH) return null
        stop()
        val dir = File(baseDir, System.currentTimeMillis().toString()).apply { mkdirs() }
            .takeIf { it.exists() } ?: File(context.cacheDir, "timeshift/" + System.currentTimeMillis()).apply { mkdirs() }
        val isHls = mimeType == StalkerMimeProbe.HLS || url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true)
        val now = System.currentTimeMillis()
        trimmedMs = 0L
        savedMs = 0L
        savedBytes = 0L
        answeredAtMs = 0L
        catchingUp = false
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
            scope.launch { recordTs(url, headers, dir, keepMinutes, reconnect, label, refreshStream, policy, open, linkStartMs) }
        }
        return s
    }

    /**
     * This recording (the one saving into [dir]) is still the one wanted. A new one (another
     * replay, say) used to set the shared flag back on, so the old one carried on writing into
     * its deleted folder, and when it finally stopped it switched the new one off with it
     * ("finished after 2s, 0s recorded", then 30 s or more before the replay played).
     */
    private fun alive(dir: File): Boolean = recording && current(dir)

    /**
     * The recording saving into [dir] is the session's (stopped or not). One just replaced only
     * writes its amounts and flags while this holds: it wrote its own length over the new save's
     * for a moment, which a replay's copy waits on to start.
     */
    private fun current(dir: File): Boolean = session?.dir == dir

    /**
     * [update] only while the recording saving into [dir] is still the session's, checked and
     * done under start/stop's lock: one being replaced can't write over the new one's amounts.
     */
    private inline fun ifCurrent(dir: File, update: () -> Unit) = synchronized(this) { if (session?.dir == dir) update() }

    /** Stops recording and deletes it. */
    @Synchronized fun stop() {
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
        val freed = savedBytes
        savedBytes = 0L
        session?.dir?.let { d ->
            ACTIVE -= d
            if (freed > 0) releasing.addAndGet(freed)
            scope.launch {
                runCatching { d.deleteRecursively() }
                if (freed > 0) releasing.addAndGet(-freed)
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

    /** One decimal, for the Start-Up Report. */
    private fun oneDecimal(x: Double): String = String.format(java.util.Locale.US, "%.1f", x)

    /** Milliseconds as seconds with one decimal, for the Start-Up Report. */
    private fun secs(ms: Long): String = oneDecimal(ms / 1000.0)

    private fun ownsWriter(writer: Job, dir: File): Boolean = synchronized(this) {
        job === writer && session?.dir == dir
    }

    /** Counter updates share start/stop's monitor, without holding it during reads or segment writes. */
    private inline fun withWriter(writer: Job, dir: File, update: () -> Unit) = synchronized(this) {
        writer.ensureActive()
        if (!recording || job !== writer || session?.dir != dir) {
            throw kotlinx.coroutines.CancellationException("Recording was replaced")
        }
        update()
    }

    private fun checkWriter(writer: Job, dir: File) = withWriter(writer, dir) { }

    // ------------------------------------------------------------------ TS

    private suspend fun recordTs(
        url: String,
        headers: Map<String, String>,
        dir: File,
        keepMinutes: Int?,
        reconnect: Boolean,
        label: String,
        refreshStream: (suspend () -> StalkerPlayback)?,
        initialPolicy: StalkerMediaPolicy?,
        /** This recording's open connections, which [stop] closes at once. */
        lines: MutableCollection<HttpURLConnection>,
        firstLinkStartMs: Long? = null,
    ) {
        val writer = checkNotNull(currentCoroutineContext()[Job])
        // The link being saved, and when it starts: a replay close to live carries on with the
        // next one (see below).
        var url = url
        var linkStartMs = firstLinkStartMs
        var carriedBytes = 0L
        // Up to three hours carried on in all, as a replay's first link asks for at most.
        var carryLeftMs = 3 * 3_600_000L
        // Pieces past the time limit are dropped as new ones come in (it records on, behind you).
        // A replay (kept whole): a 10 s target, so playback close to what's saved sees new
        // pieces within seconds instead of freezing up to 20 s.
        val segmenter = TsSegmenter(dir, keepSec = keepMinutes?.let { it * 60.0 }, fixedTargetSec = if (keepMinutes == null) REPLAY_LIST_TARGET_SEC else null)
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
        // Said once in the report: the save caught up with what has aired (see [replaySaveNearAired]).
        var airedNoted = false
        // The end of what's saved (to check a new connection carries on with the very same bytes).
        val tail = SavedTail(SPLICE_CHECK_BYTES)
        try {
            // Keeps going until you go live or change channel: a dropped connection is opened
            // again, with the gap marked, instead of ending the buffer (which sent you back to
            // live after half a minute or so).
            while (writer.isActive && alive(dir)) {
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
                        ifCurrent(dir) { recording = false }
                        throw java.io.IOException("The Stalker stream changed its buffer format. Retry playback.")
                    }
                    if (fresh != null) { if (policy !== fresh.policy) policy?.retire(); policy = fresh.policy }
                    // Carrying on: asked from a little before where it stopped, so the first bytes
                    // can be checked against the end of what's saved (see [SavedTail]).
                    val overlap = if (resuming) minOf(tail.filled.toLong(), totalBytes, SPLICE_CHECK_BYTES.toLong()).toInt() else 0
                    val expected = if (overlap > 0) tail.last(overlap) else null
                    val requestHeaders = if (resuming) headers + ("Range" to "bytes=${totalBytes - overlap}-") else headers
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
                            ifCurrent(dir) { answeredAtMs = answeredAt }
                            report.add("$label: the provider answered in ${secs(answeredAt - t0)} s (HTTP $code), ${secs(answeredAt - began)} s after asking")
                        }
                        when {
                            resuming && code == 416 -> { c.disconnect(); complete = true; "all of it is saved" }
                            code !in 200..299 -> { c.disconnect(); "HTTP $code" }
                            else -> {
                                // The provider sent the replay from its start again (it can't carry on
                                // from the middle): what's already saved is read again and skipped.
                                var skip = if (resuming && code == 200) totalBytes - overlap else 0L
                                // The overlap, still to be checked: it must be the very bytes saved.
                                var verified = 0
                                val verify = expected?.size ?: 0
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
                                    while (writer.isActive && alive(dir)) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        // Stopped or replaced while reading (not every provider's read
                                        // ends at once): nothing more goes into this save.
                                        if (!alive(dir)) break
                                        var off = 0
                                        if (skip > 0) {
                                            off = minOf(n.toLong(), skip).toInt()
                                            skip -= off
                                            if (off == n) continue
                                            checkAt = System.currentTimeMillis(); checkSaved = savedMs
                                        }
                                        if (verified < verify && expected != null) {
                                            val k = minOf(n - off, verify - verified)
                                            if (sameBytes(buf, off, expected, verified, k)) {
                                                verified += k
                                                off += k
                                                if (off == n) continue
                                            } else {
                                                // Other bytes: the provider made the replay afresh (another
                                                // moment of the show at "the same byte"). Carried on by the
                                                // stream's clock: what it sent again is dropped (see
                                                // TsSegmenter.rejoin). Before, the save ended here, and
                                                // playback stopped there while it asked again (a clean cut,
                                                // worse than a jump in the picture). From here these bytes
                                                // are the new ones: the part of the overlap not checked yet
                                                // is counted again below.
                                                markProviderResends(url)
                                                segmenter.rejoin()
                                                ifCurrent(dir) { catchingUp = true }
                                                totalBytes -= (verify - verified).toLong()
                                                verified = verify
                                                report.add("$label: the provider sent other bytes where the save stopped (it makes the replay afresh for each request); carrying on where the stream's clock gets back there")
                                            }
                                            checkAt = System.currentTimeMillis(); checkSaved = savedMs
                                        }
                                        val m = n - off
                                        bytes += m
                                        totalBytes += m
                                        segmenter.write(buf, off, m)
                                        tail.add(buf, off, m)
                                        segmenter.takeRejoinNote()?.let { report.add("$label: $it") }
                                        // Only for the save still going (see [current]).
                                        ifCurrent(dir) {
                                            catchingUp = segmenter.isRejoining
                                            trimmedMs = (segmenter.trimmedSec * 1000).toLong()
                                            savedMs = (segmenter.totalSec * 1000).toLong()
                                            // A replay keeps all it saved (a rewind buffer drops its oldest pieces).
                                            if (keepMinutes == null) savedBytes = carriedBytes + totalBytes
                                        }
                                        if (!reconnect && !firstPieceReported && segmenter.totalSec > 0) {
                                            firstPieceReported = true
                                            val at = System.currentTimeMillis()
                                            report.add(
                                                "$label: first piece ready ${secs(at - began)} s after asking, ${secs(at - answeredAt)} s after the provider answered " +
                                                    "(${oneDecimal(segmenter.totalSec)} s of the show, " +
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
                                                    "last ${secs.toInt()}s: ${oneDecimal((savedMs - noteSaved) / 1000.0 / secs)}x real time, " +
                                                    "${(totalBytes - noteBytes) / 1024 / secs.toLong().coerceAtLeast(1)} KB/s"
                                            )
                                            lastNote = nowMs; noteBytes = totalBytes; noteSaved = savedMs
                                        }
                                        if (!reconnect && nowMs - checkAt >= 20_000) {
                                            val rate = (savedMs - checkSaved) / (nowMs - checkAt).toDouble()
                                            checkAt = nowMs; checkSaved = savedMs
                                            val throttled = replaySaveThrottled(peakRate, rate)
                                            if (rate > peakRate) peakRate = rate
                                            // On a provider that sends other bytes when carried on, each new
                                            // connection makes the replay afresh: only when the save falls
                                            // behind playback (seen on PureTv: one at 1.1x, for nothing), and
                                            // minutes apart, never close together. Not while still catching up.
                                            val resends = providerResends(url)
                                            val gap = if (resends) RESENDING_BOOST_GAP_MS else 60_000L
                                            // Caught up with what has aired (a show still on air): it comes
                                            // in as it airs, and a new connection can't make that faster.
                                            val nearAired = replaySaveNearAired(firstLinkStartMs, (segmenter.totalSec * 1000).toLong(), nowMs)
                                            if (throttled && nearAired && !airedNoted) {
                                                airedNoted = true
                                                report.add("$label: caught up with what has aired (${oneDecimal(rate)}x real time lately); it carries on as it airs")
                                            }
                                            if (throttled && !nearAired && rangeWorks != false && (!resends || rate < 1.0) && !segmenter.isRejoining &&
                                                nowMs - lastBoostAt >= gap
                                            ) {
                                                lastBoostAt = nowMs
                                                end = "slowed to ${oneDecimal(rate)}x real time; new connection for full speed"
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
                    writer.ensureActive()
                    if (refreshStream != null) "Stalker stream unavailable" else e.message ?: e.javaClass.simpleName
                }
                if (!alive(dir)) break
                if (!reconnect) {
                    if (knownLength > 0 && totalBytes >= knownLength) complete = true
                    if (bytes > 0) resumeTries = 0 else if (resuming) resumeTries++
                    // Saved to its end, or it can't be carried on (playback then asks the provider
                    // again from where it got to): the save ends here.
                    if (complete && totalBytes > 0) {
                        // All of that link is in, and more of the channel has aired since it was
                        // asked for (a replay a few minutes behind live: its link could only hold
                        // those few minutes): the save carries on with the link after it, put back
                        // in place by the stream's clock. Before, playback ran to the end of the
                        // link every couple of minutes and waited some 10 s on a new one. With
                        // under a minute more aired, it waits for that minute. A link asked to the
                        // end of a show still on air ends where the show had got to: on from where
                        // the save got to (see CatchupUrlBuilder.nextLink), not after the show.
                        var next: Pair<String, Long>? = null
                        val start = linkStartMs
                        val savedUpTo = firstLinkStartMs?.let { it + (segmenter.totalSec * 1000).toLong() }
                        while (start != null && carryLeftMs > 0 && alive(dir)) {
                            val more = com.nuvio.tv.livetv.data.CatchupUrlBuilder.nextLink(url, start, System.currentTimeMillis(), savedUpTo)
                            if (more != null) { next = more; break }
                            // Not this kind of link, or far from live no more: nothing to wait for.
                            if (com.nuvio.tv.livetv.data.CatchupUrlBuilder.nextLink(url, start, Long.MAX_VALUE / 2) == null) break
                            delay(5_000)
                        }
                        if (next != null && alive(dir)) {
                            val linkEnd = start?.let { com.nuvio.tv.livetv.data.CatchupUrlBuilder.nextLink(url, it, Long.MAX_VALUE / 2)?.second }
                            val shortOfEnd = linkEnd != null && next.second < linkEnd
                            report.add(
                                "$label: ${totalBytes / 1024 / 1024} MB saved, " +
                                    (if (shortOfEnd) "all the provider had of that link (the show was still on air); carrying on from where the save got to"
                                    else "all of that link; carrying on with what has aired since") +
                                    " (${com.nuvio.tv.livetv.data.CatchupUrlBuilder.askedStamp(next.first)})"
                            )
                            url = next.first
                            linkStartMs = next.second
                            carryLeftMs -= (Regex("""/timeshift/[^/]+/[^/]+/(\d+)/""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 180L) * 60_000
                            carriedBytes += totalBytes
                            totalBytes = 0L; knownLength = -1L; resumeTries = 0; rangeWorks = null; attempt = 0
                            segmenter.rejoin()
                            continue
                        }
                    }
                    if (complete || resumeTries >= 6 || (totalBytes == 0L && attempt >= 2)) {
                        report.add("$label: ${totalBytes / 1024 / 1024} MB saved ($why)")
                        break
                    }
                    // Carried on from the same byte, also on a provider that sends other bytes
                    // there (see [providerResends]): the stream's clock puts it back where it stopped.
                    if (totalBytes > 0) {
                        report.add("$label: connection ended after ${(System.currentTimeMillis() - t0) / 1000}s ($why); carrying on from ${totalBytes / 1024 / 1024} MB")
                        if (bytes > 0) attempt = 1
                        continue
                    }
                }
                report.add("$label: connection ended after ${(System.currentTimeMillis() - t0) / 1000}s, ${bytes / 1024} KB ($why); reconnecting")
                // Got nothing at all several times running: give up (the provider won't serve it).
                if (bytes == 0L && attempt >= 9) { report.add("$label: gave up (no data)"); break }
                if (bytes > 0) attempt = 1
            }
        } finally {
            // Its connection is closed by now (the next request waits for this, see ProviderLine).
            if (opened) ProviderLine.closed()
            report.add("$label finished after ${(System.currentTimeMillis() - began) / 1000}s (${segmenter.totalSec.toInt()}s recorded)")
            // Stopped or replaced: its unfinished piece isn't published, nor its playlist ended.
            val complete = writer.isActive && ownsWriter(writer, dir)
            runCatching { if (complete) segmenter.finish(ended = true) else segmenter.discard() }
            synchronized(this) {
                if (job === writer && session?.dir == dir) {
                    if (complete) savedMs = (segmenter.totalSec * 1000).toLong()
                    catchingUp = false
                    recording = false
                }
            }
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
        val writer = checkNotNull(currentCoroutineContext()[Job])
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
            checkWriter(writer, dir)
            val choice = if (HlsPieces.isMaster(firstText)) {
                HlsPieces.pick(firstUrl, firstText, audioLanguage) ?: return
            } else null
            val videoUrl = choice?.videoUrl ?: firstUrl
            val audioUrl = choice?.audioUrl
            val video = HlsPieces.Track(videoUrl, dir, if (audioUrl != null) "v" else "", headers, connect)
            val audio = audioUrl?.let { HlsPieces.Track(it, dir, "a", headers, connect) }
            // Stopped: the piece being read and those left are dropped at once.
            video.stopNow = { !writer.isActive || !alive(dir) }
            audio?.stopNow = { !writer.isActive || !alive(dir) }
            val videoFile = if (audio != null) File(dir, "video.m3u8") else local
            val audioFile = File(dir, "audio.m3u8")
            var vm = if (choice == null) HlsPieces.parseMedia(firstText) else video.fetch()
            checkWriter(writer, dir)
            var am = audio?.fetch()
            checkWriter(writer, dir)
            // Encrypted streams aren't recorded (the keys would have to be saved too).
            if (vm.encrypted || am?.encrypted == true) {
                Log.w(TAG, "Encrypted HLS: not recorded")
                report.add("$label: encrypted stream, can't be saved")
                withWriter(writer, dir) { recording = false }
                return
            }
            if (audio != null) report.add("$label: saving picture and sound separately (sound: ${choice?.audioLanguage ?: "default"})")
            var masterWritten = false
            var lastProgressAt = System.currentTimeMillis()
            var lastSavedName: String? = null
            suspend fun refreshPortal() {
                val fresh = refreshStream?.invoke() ?: return
                checkWriter(writer, dir)
                if (fresh.mimeType == StalkerMimeProbe.DASH) throw java.io.IOException(StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE)
                if (policy !== fresh.policy) policy?.retire()
                policy = fresh.policy
                val (text, servedAt) = LiveHttp.fetch(fresh.url, fresh.headers, policy, lines)
                checkWriter(writer, dir)
                val refreshed = if (HlsPieces.isMaster(text)) HlsPieces.pick(servedAt, text, audioLanguage) else null
                if ((refreshed?.audioUrl != null) != (audio != null)) throw IllegalStateException("The portal changed its audio format.")
                video.url = refreshed?.videoUrl ?: servedAt
                audio?.url = refreshed?.audioUrl ?: audio?.url.orEmpty()
                vm = if (refreshed == null) HlsPieces.parseMedia(text) else video.fetch()
                am = audio?.fetch()
                if (vm.encrypted || am?.encrypted == true) throw IllegalStateException("Encrypted portal streams cannot be buffered.")
                lastProgressAt = System.currentTimeMillis()
            }
            while (writer.isActive && alive(dir)) {
                checkWriter(writer, dir)
                // Live: from about where you are (players play a few pieces behind the newest).
                val lead = if (live) (leadMs ?: (vm.target * 3_000L)) else null
                video.saveNew(vm) { m -> if (lead == null) 0 else HlsPieces.startIndex(m.pieces, lead) }
                if (audio != null && am != null) {
                    audio.saveNew(am!!) { a -> if (lead == null) 0 else HlsPieces.matchingStart(a.pieces, video) }
                }
                checkWriter(writer, dir)
                // Keep only the time limit's worth.
                val keepSec = keepMinutes * 60.0
                val trimmed = (video.trim(keepSec) * 1000).toLong()
                audio?.trim(keepSec)
                withWriter(writer, dir) { trimmedMs += trimmed }
                if (audio == null) {
                    video.write(local, ended = false)
                } else if (video.saved.isNotEmpty() && audio.saved.isNotEmpty()) {
                    audio.write(audioFile, ended = false)
                    checkWriter(writer, dir)
                    video.write(videoFile, ended = false)
                    if (!masterWritten) {
                        checkWriter(writer, dir)
                        HlsPieces.writeMaster(local, choice!!, videoFile.name, audioFile.name)
                        masterWritten = true
                    }
                }
                if (video.saved.lastOrNull()?.name != lastSavedName) { lastSavedName = video.saved.lastOrNull()?.name; lastProgressAt = System.currentTimeMillis() }
                else if (refreshStream != null && live && System.currentTimeMillis() - lastProgressAt > 20_000L) refreshPortal()
                delay((vm.target * 1000L / 2).coerceIn(1_000L, 6_000L))
                try {
                    vm = video.fetch()
                    checkWriter(writer, dir)
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
            synchronized(this) { if (job === writer && session?.dir == dir) recording = false }
        }
    }

    private companion object {
        const val TAG = "LiveTvTimeshift"
        /** Buffers and saved replays in use right now (by any player): never cleaned up. */
        val ACTIVE: MutableSet<File> = java.util.Collections.synchronizedSet(HashSet())
    }
}
