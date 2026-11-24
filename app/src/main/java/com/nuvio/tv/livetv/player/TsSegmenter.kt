package com.nuvio.tv.livetv.player

import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream

/**
 * Cuts a live MPEG-TS stream into short pieces with a local HLS playlist, as it arrives, so a
 * recording can be played, paused and skipped through while it's still being made (the same
 * way the player handles HLS channels). Used by pause and rewind, and by recordings.
 *
 *  - Pieces are about [targetSec] long (the first one shorter, so playback can start soon),
 *    cut where a picture starts cleanly when the stream marks one.
 *  - Every piece starts with the stream's program tables (PAT / PMT), so each one plays on
 *    its own.
 *  - Lengths come from the stream's own clock (PCR) when it has one, else the wall clock.
 *  - [keepSec]: older pieces are dropped past this much (null: keep everything, recordings).
 *
 * Feed it bytes with [write] (any sizes); call [finish] at the end.
 */
class TsSegmenter(
    private val dir: File,
    private val targetSec: Double = 6.0,
    private val keepSec: Double? = null,
    /**
     * A fixed target length for the playlist (null: at least 20 s). The player looks at a
     * growing list again only about once per target length, so with 20 s a replay played close
     * to what's saved froze for up to 20 s before it saw the next pieces.
     */
    private val fixedTargetSec: Int? = null,
    /** The wall clock (tests set their own). */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Piece(val seq: Long, val name: String, val durationSec: Double, val discontinuity: Boolean = false) {
        internal val playlistEntry = LivePlaylistWriter.entry(durationSec, name, discontinuity)
    }

    val playlist: File = File(dir, "index.m3u8")

    private val pieces = ArrayList<Piece>()
    private var maxPieceDurationSec = 0.0
    @Volatile var trimmedSec: Double = 0.0
        private set
    @Volatile var totalSec: Double = 0.0
        private set
    /**
     * The last piece was cut where a picture starts (the stream marks those); false: cut by
     * length (at twice the target, or three times), when the stream marks none in time.
     */
    @Volatile var lastCutClean: Boolean = false
        private set

    // Packet assembly (input arrives in any sizes; TS packets are 188 bytes).
    private val pkt = ByteArray(PACKET)
    private var pktFill = 0
    private var synced = false

    // Program tables, repeated at the start of every piece.
    private var pat: ByteArray? = null
    private val pmtPids = HashSet<Int>()
    private val pmts = HashMap<Int, ByteArray>()
    private var pcrPid = -1
    /** The video stream (from the PMT): its packet id and kind, to spot where pictures start. */
    private var videoPid = -1
    private var videoType = 0
    /** Other tables the player reads (ad markers), from the PMT: checked like the PAT / PMT. */
    private var sectionPids: Set<Int> = emptySet()

    // The piece being written.
    private var seq = 0L
    private var out: BufferedOutputStream? = null
    private var outFile: File? = null
    private var pieceStartPcr = -1L
    private var lastPcr = -1L
    private var pieceStartWall = 0L
    private var pieceBytes = 0L
    private var pieceDiscontinuity = false

    fun write(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (!synced) {
                // Find the sync byte that starts a packet.
                if (buf[i] != SYNC) { i++; continue }
                synced = true
                pktFill = 0
            }
            val take = minOf(PACKET - pktFill, end - i)
            System.arraycopy(buf, i, pkt, pktFill, take)
            pktFill += take
            i += take
            if (pktFill == PACKET) {
                pktFill = 0
                if (pkt[0] != SYNC) { synced = false; continue }
                packet(pkt)
            }
        }
    }

    /**
     * The connection dropped and a new one is starting: close the current piece and mark the
     * next as a fresh start (its clock starts over), so the player carries on across the gap.
     */
    fun reconnected() {
        closePiece()
        synced = false
        pktFill = 0
        lastPcr = -1L
        nextIsDiscontinuity = pieces.isNotEmpty()
    }
    private var nextIsDiscontinuity = false

    // Rejoining by the stream's clock (see [rejoin]).
    private var rejoining = false
    /** The clock where the save stopped (-1: none to go by). */
    private var rejoinPcr = -1L
    /** The clock's there (or can't be gone by): now waiting for a picture the player can start from. */
    private var rejoinAtKey = false
    private var rejoinSince = 0L
    private var rejoinFirstPcr = -1L
    private var rejoinLastPcr = -1L
    private var rejoinKeyWait = 0
    private var rejoinWhy = ""
    @Volatile private var rejoinNote: String? = null

    /**
     * A replay carried on over a new connection that sent other bytes than those saved: some
     * providers (PureTv) make the replay afresh for each request, so "the same byte" is another
     * moment of the show (seen: 35 s earlier). What comes next is dropped until the stream's own
     * clock reaches where the save stopped, then taken from a picture the player can start from,
     * as a fresh start (marked, so the player carries on across it). Taken as it comes, from such
     * a picture, when the clock can't be gone by (another clock, ahead of where it stopped) or
     * catching up takes more than [REJOIN_MAX_WAIT_MS]: the picture then jumps back (or ahead)
     * instead. Before, the save ended there, and playback stopped there while it asked again.
     */
    fun rejoin() {
        // Again before it got there: still where the save stopped.
        val target = if (rejoining) rejoinPcr else lastPcr
        reconnected()
        rejoining = true
        rejoinPcr = target
        rejoinAtKey = target < 0
        rejoinWhy = if (target < 0) "the stream has no clock to go by" else ""
        rejoinSince = clock()
        rejoinFirstPcr = -1L
        rejoinLastPcr = -1L
        rejoinKeyWait = 0
    }

    /** What the last [rejoin] did, once it's done (for the report), or null. */
    fun takeRejoinNote(): String? = rejoinNote?.also { rejoinNote = null }

    /** Still dropping what the provider sent again (see [rejoin]). */
    val isRejoining: Boolean get() = rejoining

    /**
     * Rejoining (see [rejoin]): whether packet [pid] goes into the copy. Dropped until the
     * stream's clock reaches where the save stopped, then until a picture the player can start
     * from (or, when none comes in [REJOIN_KEY_PACKETS] packets, taken as it comes).
     */
    private fun rejoinPasses(pid: Int, pcr: Long, randomAccess: Boolean): Boolean {
        if (!rejoinAtKey) {
            if (pcr >= 0 && pid == pcrPid) {
                if (rejoinFirstPcr < 0) rejoinFirstPcr = pcr
                rejoinLastPcr = pcr
                val first = rejoinFirstPcr - rejoinPcr
                val d = pcr - rejoinPcr
                when {
                    first > REJOIN_RANGE || first < -REJOIN_RANGE -> { rejoinAtKey = true; rejoinWhy = "its clock isn't the one saved" }
                    first > 0 -> { rejoinAtKey = true; rejoinWhy = "it came ${secs(first)} s past where the save stopped" }
                    d >= 0 -> rejoinAtKey = true
                }
            }
            if (!rejoinAtKey && clock() - rejoinSince > REJOIN_MAX_WAIT_MS) {
                rejoinAtKey = true
                rejoinWhy = "catching up took too long"
            }
            if (!rejoinAtKey) return false
        }
        val start = randomAccess && (videoPid < 0 || pid == videoPid || pid == pcrPid)
        if (!start && ++rejoinKeyWait <= REJOIN_KEY_PACKETS) return false
        val dropped = if (rejoinFirstPcr >= 0 && rejoinLastPcr >= rejoinFirstPcr) secs(rejoinLastPcr - rejoinFirstPcr) else null
        val behind = if (rejoinFirstPcr >= 0 && rejoinPcr >= 0) rejoinPcr - rejoinFirstPcr else null
        rejoinNote = when {
            rejoinWhy.isEmpty() -> "carried on where the save stopped, by the stream's clock (dropped the ${dropped ?: 0} s it sent again)"
            rejoinWhy == "catching up took too long" && behind != null && behind in 0..REJOIN_RANGE ->
                "carried on ${secs(behind - (rejoinLastPcr - rejoinFirstPcr).coerceAtLeast(0))} s before where the save stopped ($rejoinWhy; the picture jumps back that much)"
            else -> "carried on as it came ($rejoinWhy)"
        }
        rejoining = false
        return true
    }

    /** Whole seconds, rounded, of a span of the stream's 90 kHz clock. */
    private fun secs(pcrTicks: Long): Long = (pcrTicks + 45_000) / 90_000

    /** Closes the last piece and marks the playlist finished (no more to come). */
    fun finish(ended: Boolean = true) {
        closePiece()
        writePlaylist(ended)
    }

    private fun packet(p: ByteArray) {
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val pusi = (p[1].toInt() and 0x40) != 0
        // A damaged table isn't passed on (see tableOk).
        if ((pid == 0 || pid in pmtPids || pid in sectionPids) && !tableOk(p, pid, pusi)) return
        val afc = (p[3].toInt() shr 4) and 0x3
        var randomAccess = false
        var pcr = -1L
        if ((afc == 2 || afc == 3) && (p[4].toInt() and 0xFF) > 0) {
            val flags = p[5].toInt() and 0xFF
            randomAccess = (flags and 0x40) != 0
            if ((flags and 0x10) != 0 && (p[4].toInt() and 0xFF) >= 7) {
                pcr = ((p[6].toLong() and 0xFF) shl 25) or ((p[7].toLong() and 0xFF) shl 17) or
                    ((p[8].toLong() and 0xFF) shl 9) or ((p[9].toLong() and 0xFF) shl 1) or
                    ((p[10].toLong() and 0x80) shr 7)
            }
        }
        // Tables: keep the latest copy of each.
        if (pid == 0 && pusi) {
            pat = p.copyOf()
            readPat(p)
        } else if (pid in pmtPids && pusi) {
            pmts[pid] = p.copyOf()
            readPmt(p)
        }
        if (pcr >= 0 && (pcrPid < 0 || pid == pcrPid)) {
            if (pcrPid < 0) pcrPid = pid
            lastPcr = pcr
        }
        // Many streams don't mark where pictures start (seen on TF1: the first piece waited
        // 7.6 s to be cut by length, and the replay's picture with it). The video itself says
        // so: a picture the player can start from begins with the stream's settings (SPS) or
        // is a key picture (IDR).
        if (!randomAccess && pusi && pid == videoPid && startsKeyPicture(p)) randomAccess = true
        // Carried on over a connection that sent other bytes: dropped until it's back where it stopped.
        if (rejoining && !rejoinPasses(pid, pcr, randomAccess)) return

        // Time to start a new piece?
        if (out == null) {
            // Wait for the tables before the first piece, so it plays on its own.
            if (pat == null || pmts.isEmpty()) return
            openPiece()
        } else {
            // A piece opened before the stream's clock came: measured by the clock from its
            // first tick (the wall clock instead would be wrong when the provider sends faster
            // than real time, in the playlist's lengths too).
            if (pieceStartPcr < 0 && lastPcr >= 0) pieceStartPcr = lastPcr
            val len = currentLengthSec()
            val target = if (pieces.isEmpty()) 2.0 else targetSec
            val cleanCut = randomAccess && len >= target
            // At the start of a video frame (or of what carries the clock), so the cut at least
            // falls between frames; a stream whose clock has a packet id of its own never got one.
            val forcedCut = len >= target * 2 && pusi && (pid == pcrPid || pid == videoPid)
            if (cleanCut || forcedCut || len >= target * 3) {
                lastCutClean = cleanCut
                closePiece()
                openPiece()
            }
        }
        out?.write(p)
        pieceBytes += PACKET
    }

    private fun currentLengthSec(): Double {
        if (pieceStartPcr >= 0 && lastPcr >= pieceStartPcr) {
            val d = (lastPcr - pieceStartPcr) / 90_000.0
            if (d < 120) return d
        }
        return (clock() - pieceStartWall) / 1000.0
    }

    private fun openPiece() {
        val f = File(dir, "seg$seq.ts")
        outFile = f
        out = BufferedOutputStream(FileOutputStream(f), 64 * 1024).also { o ->
            pat?.let { o.write(it) }
            pmts.values.forEach { o.write(it) }
        }
        pieceStartPcr = lastPcr
        pieceStartWall = clock()
        pieceBytes = 0
        pieceDiscontinuity = nextIsDiscontinuity
        nextIsDiscontinuity = false
    }

    private fun closePiece() {
        val o = out ?: return
        val f = outFile ?: return
        out = null
        outFile = null
        // A piece must be fully on disk before its playlist entry or callback exposes it.
        // Propagate a failed flush to the recorder instead of publishing a truncated piece.
        o.close()
        if (pieceBytes == 0L) { f.delete(); return }
        val len = currentLengthSec().coerceAtLeast(0.1)
        pieces += Piece(seq, f.name, len, discontinuity = pieceDiscontinuity)
        maxPieceDurationSec = maxOf(maxPieceDurationSec, len)
        totalSec += len
        seq++
        // Rewind buffer: drop what's past the time limit.
        keepSec?.let { keep ->
            var kept = pieces.sumOf { it.durationSec }
            var removedLongest = false
            while (pieces.size > 3 && kept > keep) {
                val first = pieces.removeAt(0)
                if (first.durationSec == maxPieceDurationSec) removedLongest = true
                kept -= first.durationSec
                trimmedSec += first.durationSec
                runCatching { File(dir, first.name).delete() }
            }
            if (removedLongest) maxPieceDurationSec = pieces.maxOf { it.durationSec }
        }
        writePlaylist(ended = false)
    }

    private fun writePlaylist(ended: Boolean) {
        if (pieces.isEmpty()) return
        // A steady target length from the start (pieces can run longer than planned when the
        // stream rarely marks a clean cut): a target that grows mid-way, or pieces longer than
        // it, made the player think the playlist had stalled and give up.
        val target = fixedTargetSec ?: maxOf(20, kotlin.math.ceil(maxPieceDurationSec).toInt())
        runCatching {
            LivePlaylistWriter.write(playlist) { writer ->
                writer.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target.toString()).append('\n')
                writer.append("#EXT-X-MEDIA-SEQUENCE:").append(pieces.first().seq.toString()).append('\n')
                // A rewind buffer drops pieces, which an EVENT playlist cannot do.
                if (keepSec == null) writer.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                pieces.forEach { writer.write(it.playlistEntry) }
                if (ended) writer.append("#EXT-X-ENDLIST\n")
            }
        }
    }

    /** The PMT packet ids listed in the PAT. */
    private fun readPat(p: ByteArray) {
        val start = payloadStart(p) ?: return
        var i = start + 1 + (p[start].toInt() and 0xFF) // pointer field
        if (i + 8 > PACKET) return
        val sectionLen = ((p[i + 1].toInt() and 0x0F) shl 8) or (p[i + 2].toInt() and 0xFF)
        val endOfPrograms = minOf(i + 3 + sectionLen - 4, PACKET)
        i += 8
        val found = HashSet<Int>()
        while (i + 4 <= endOfPrograms) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (program != 0) found += pid
            i += 4
        }
        if (found.isNotEmpty() && found != pmtPids) {
            pmtPids.clear(); pmtPids += found
            pmts.keys.retainAll(found)
        }
    }

    /**
     * The table packet [p] (on a PAT, PMT or other table's packet id) is whole enough to pass on.
     * A damaged one in a provider's replay made the player give up on the copy ("the saved copy
     * didn't play"): where its table starts pointed past the end of the packet, which the player
     * doesn't check. And a damaged PAT or PMT was kept as the copy to repeat, so every piece after
     * it started with it. Dropped: marked damaged in transit, a table that would start past the
     * packet, the wrong kind of table, and a PAT / PMT whose checksum is wrong when a good one is
     * already kept (some providers get the checksum wrong all along: their first one is kept).
     */
    private fun tableOk(p: ByteArray, pid: Int, pusi: Boolean): Boolean {
        if ((p[1].toInt() and 0x80) != 0) return false
        val afc = (p[3].toInt() shr 4) and 0x3
        val s = payloadStart(p)
        // Its adaptation field runs past the packet: the player would read past it too.
        if (afc == 3 && s == null) return false
        if (!pusi || s == null) return true
        val start = s + 1 + (p[s].toInt() and 0xFF)
        if (start + 3 > PACKET) return false
        val expected = when (pid) { 0 -> 0x00; in pmtPids -> 0x02; else -> -1 }
        if (expected < 0) return true
        if ((p[start].toInt() and 0xFF) != expected) return false
        val len = ((p[start + 1].toInt() and 0x0F) shl 8) or (p[start + 2].toInt() and 0xFF)
        // A table going on in the next packets: its checksum can't be checked from this one.
        if (start + 3 + len > PACKET) return true
        if (crc32(p, start, 3 + len) == 0) return true
        return if (pid == 0) pat == null else pmts[pid] == null
    }

    /** MPEG's CRC-32 over [len] bytes of [p] from [from]: 0 over a whole table with its checksum. */
    private fun crc32(p: ByteArray, from: Int, len: Int): Int {
        var crc = -1
        for (i in from until from + len) {
            crc = crc xor ((p[i].toInt() and 0xFF) shl 24)
            for (b in 0 until 8) crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
        }
        return crc
    }

    /** The PCR packet id from a PMT (the stream's clock), and the video stream's. */
    private fun readPmt(p: ByteArray) {
        val start = payloadStart(p) ?: return
        val i = start + 1 + (p[start].toInt() and 0xFF)
        if (i + 10 > PACKET) return
        val pid = ((p[i + 8].toInt() and 0x1F) shl 8) or (p[i + 9].toInt() and 0xFF)
        if (pid != 0x1FFF) pcrPid = pid
        // The streams, listed after the program's own descriptors: the first video one.
        if (i + 12 > PACKET) return
        val sectionLen = ((p[i + 1].toInt() and 0x0F) shl 8) or (p[i + 2].toInt() and 0xFF)
        val end = minOf(i + 3 + sectionLen - 4, PACKET)
        val infoLen = ((p[i + 10].toInt() and 0x0F) shl 8) or (p[i + 11].toInt() and 0xFF)
        var j = i + 12 + infoLen
        var videoFound = false
        val sections = HashSet<Int>()
        while (j + 5 <= end) {
            val type = p[j].toInt() and 0xFF
            val esPid = ((p[j + 1].toInt() and 0x1F) shl 8) or (p[j + 2].toInt() and 0xFF)
            val esInfo = ((p[j + 3].toInt() and 0x0F) shl 8) or (p[j + 4].toInt() and 0xFF)
            if (!videoFound && type in VIDEO_TYPES) { videoPid = esPid; videoType = type; videoFound = true }
            if (type in SECTION_TYPES) sections += esPid
            j += 5 + esInfo
        }
        sectionPids = sections
    }

    /**
     * The video packet [p] starts a picture the player can start from: its first bytes hold the
     * stream's settings or a key picture (H.264: SPS or IDR; HEVC: VPS / SPS / PPS or a key
     * picture; MPEG-2: a sequence or group header).
     */
    private fun startsKeyPicture(p: ByteArray): Boolean {
        val s = payloadStart(p) ?: return false
        // The PES header first: 00 00 01, stream id, length (2), flags (2), header length.
        if (s + 9 > PACKET || p[s].toInt() != 0 || p[s + 1].toInt() != 0 || p[s + 2].toInt() != 1) return false
        var k = s + 9 + (p[s + 8].toInt() and 0xFF)
        while (k + 3 < PACKET) {
            if (p[k].toInt() != 0 || p[k + 1].toInt() != 0 || p[k + 2].toInt() != 1) { k++; continue }
            val b = p[k + 3].toInt() and 0xFF
            when (videoType) {
                0x1B -> (b and 0x1F).let { if (it == 5 || it == 7) return true }
                0x24 -> ((b shr 1) and 0x3F).let { if (it in 16..21 || it in 32..34) return true }
                0x01, 0x02 -> if (b == 0xB3 || b == 0xB8) return true
            }
            k += 3
        }
        return false
    }

    private fun payloadStart(p: ByteArray): Int? {
        val afc = (p[3].toInt() shr 4) and 0x3
        return when (afc) {
            1 -> 4
            3 -> (5 + (p[4].toInt() and 0xFF)).takeIf { it < PACKET }
            else -> null
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        /** PMT stream types for video: MPEG-1, MPEG-2, H.264, HEVC. */
        val VIDEO_TYPES = setOf(0x01, 0x02, 0x1B, 0x24)
        /** PMT stream types the player reads as tables: SCTE-35 ad markers. */
        val SECTION_TYPES = setOf(0x86)
        /** Rejoining: a clock further than this (10 min, 90 kHz) from where the save stopped is another one. */
        const val REJOIN_RANGE = 10L * 60 * 90_000
        /** Rejoining: catching up for longer than this is given up (the picture then jumps back instead). */
        const val REJOIN_MAX_WAIT_MS = 30_000L
        /** Rejoining: packets to wait for a picture the player can start from (about 7 MB). */
        const val REJOIN_KEY_PACKETS = 40_000
    }
}
