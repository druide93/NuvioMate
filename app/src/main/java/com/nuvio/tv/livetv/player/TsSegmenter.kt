package com.nuvio.tv.livetv.player

import java.io.File
import java.io.FileOutputStream

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
    /** A piece was saved (its length in seconds); the playlist is already updated. */
    private val onSegment: (Double) -> Unit = {}
) {
    data class Piece(val seq: Long, val name: String, val durationSec: Double, val discontinuity: Boolean = false)

    val playlist: File = File(dir, "index.m3u8")

    private val pieces = ArrayList<Piece>()
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

    // The piece being written.
    private var seq = 0L
    private var out: FileOutputStream? = null
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

    /** Closes the last piece and marks the playlist finished (no more to come). */
    fun finish(ended: Boolean = true) {
        closePiece()
        writePlaylist(ended)
    }

    private fun packet(p: ByteArray) {
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val pusi = (p[1].toInt() and 0x40) != 0
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
        return (System.currentTimeMillis() - pieceStartWall) / 1000.0
    }

    private fun openPiece() {
        val f = File(dir, "seg$seq.ts")
        outFile = f
        out = FileOutputStream(f).also { o ->
            pat?.let { o.write(it) }
            pmts.values.forEach { o.write(it) }
        }
        pieceStartPcr = lastPcr
        pieceStartWall = System.currentTimeMillis()
        pieceBytes = 0
        pieceDiscontinuity = nextIsDiscontinuity
        nextIsDiscontinuity = false
    }

    private fun closePiece() {
        val o = out ?: return
        val f = outFile ?: return
        runCatching { o.flush(); o.close() }
        out = null
        outFile = null
        if (pieceBytes == 0L) { f.delete(); return }
        val len = currentLengthSec().coerceAtLeast(0.1)
        pieces += Piece(seq, f.name, len, discontinuity = pieceDiscontinuity)
        totalSec += len
        seq++
        // Rewind buffer: drop what's past the time limit.
        keepSec?.let { keep ->
            var kept = pieces.sumOf { it.durationSec }
            while (pieces.size > 3 && kept > keep) {
                val first = pieces.removeAt(0)
                kept -= first.durationSec
                trimmedSec += first.durationSec
                runCatching { File(dir, first.name).delete() }
            }
        }
        writePlaylist(ended = false)
        onSegment(len)
    }

    private fun writePlaylist(ended: Boolean) {
        if (pieces.isEmpty()) return
        // A steady target length from the start (pieces can run longer than planned when the
        // stream rarely marks a clean cut): a target that grows mid-way, or pieces longer than
        // it, made the player think the playlist had stalled and give up.
        val target = fixedTargetSec ?: maxOf(20, kotlin.math.ceil(pieces.maxOf { it.durationSec }).toInt())
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(pieces.first().seq).append('\n')
        // A rewind buffer drops its oldest pieces, which an "EVENT" playlist isn't allowed to do.
        if (keepSec == null) sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        pieces.forEach { pc ->
            if (pc.discontinuity) sb.append("#EXT-X-DISCONTINUITY\n")
            sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", pc.durationSec)).append(",\n")
            sb.append(pc.name).append('\n')
        }
        if (ended) sb.append("#EXT-X-ENDLIST\n")
        val tmp = File(dir, playlist.name + ".tmp")
        runCatching {
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(playlist)) { tmp.copyTo(playlist, overwrite = true); tmp.delete() }
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
        while (j + 5 <= end) {
            val type = p[j].toInt() and 0xFF
            val esPid = ((p[j + 1].toInt() and 0x1F) shl 8) or (p[j + 2].toInt() and 0xFF)
            val esInfo = ((p[j + 3].toInt() and 0x0F) shl 8) or (p[j + 4].toInt() and 0xFF)
            if (type in VIDEO_TYPES) { videoPid = esPid; videoType = type; return }
            j += 5 + esInfo
        }
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
    }
}
