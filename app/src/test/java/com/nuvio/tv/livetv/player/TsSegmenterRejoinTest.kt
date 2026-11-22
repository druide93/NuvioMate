package com.nuvio.tv.livetv.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replay carried on over a new connection that sent another moment of the show (PureTv makes
 * the replay afresh for each request): the save goes on from where the stream's clock is back
 * where it stopped. H.264 at 25 frames a second, a key picture every second.
 */
class TsSegmenterRejoinTest {
    private val dir: File = Files.createTempDirectory("ts-rejoin").toFile()
    private var now = 0L

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun segmenter() = TsSegmenter(dir, fixedTargetSec = 10, clock = { now })

    @Test
    fun `sent again from 35 s earlier, it carries on where it stopped`() {
        val seg = segmenter()
        feed(seg, stream(fromSec = 0.0, toSec = 60.0))
        seg.rejoin()
        assertTrue(seg.isRejoining)
        feed(seg, stream(fromSec = 25.0, toSec = 90.0))
        seg.finish()
        assertFalse(seg.isRejoining)
        // 60 s, then 30 s more: not the 35 s sent again.
        assertEquals(90.0, seg.totalSec, 0.5)
        val note = seg.takeRejoinNote()
        assertNotNull(note)
        assertTrue(note!!, note.startsWith("carried on where the save stopped"))
        assertTrue(note, note.contains("35 s"))
        assertTrue(File(dir, "index.m3u8").readText().contains("#EXT-X-DISCONTINUITY"))
    }

    @Test
    fun `another clock is taken as it comes, from a key picture`() {
        val seg = segmenter()
        feed(seg, stream(fromSec = 0.0, toSec = 60.0))
        seg.rejoin()
        // Restarted from 0 hours later on the stream's clock: can't be gone by.
        feed(seg, stream(fromSec = 0.0, toSec = 20.0, clockSec = 5 * 3600.0))
        seg.finish()
        assertEquals(80.0, seg.totalSec, 0.5)
        assertTrue(seg.takeRejoinNote()!!.contains("isn't the one saved"))
    }

    @Test
    fun `ahead of where it stopped, it goes on at once`() {
        val seg = segmenter()
        feed(seg, stream(fromSec = 0.0, toSec = 60.0))
        seg.rejoin()
        feed(seg, stream(fromSec = 70.0, toSec = 80.0))
        seg.finish()
        assertEquals(70.0, seg.totalSec, 0.5)
        assertTrue(seg.takeRejoinNote()!!.contains("10 s past where the save stopped"))
    }

    @Test
    fun `catching up too slowly gives up and takes it as it comes`() {
        val seg = segmenter()
        feed(seg, stream(fromSec = 0.0, toSec = 60.0))
        seg.rejoin()
        feed(seg, stream(fromSec = 20.0, toSec = 30.0))
        now += 31_000L
        feed(seg, stream(fromSec = 30.0, toSec = 40.0))
        seg.finish()
        // Taken from 30 s: 60 + 10.
        assertEquals(70.0, seg.totalSec, 0.5)
        assertTrue(seg.takeRejoinNote()!!.contains("took too long"))
    }

    @Test
    fun `a second new connection while catching up still goes by where the save stopped`() {
        val seg = segmenter()
        feed(seg, stream(fromSec = 0.0, toSec = 60.0))
        seg.rejoin()
        feed(seg, stream(fromSec = 25.0, toSec = 40.0))
        seg.rejoin()
        feed(seg, stream(fromSec = 30.0, toSec = 80.0))
        seg.finish()
        assertEquals(80.0, seg.totalSec, 0.5)
    }

    private fun feed(seg: TsSegmenter, bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val n = minOf(1_000, bytes.size - i)
            seg.write(bytes, i, n)
            i += n
        }
    }

    /** The show from [fromSec] to [toSec], its clock reading [clockSec] more. */
    private fun stream(fromSec: Double, toSec: Double, clockSec: Double = 0.0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pat())
        out.write(pmt())
        val first = (fromSec * 25).toInt()
        val last = (toSec * 25).toInt()
        for (n in first until last) {
            val base = 900_000L + ((n / 25.0 + clockSec) * 90_000).toLong()
            out.write(pcrPacket(base))
            out.write(videoPacket(key = n % 25 == 0))
        }
        return out.toByteArray()
    }

    private fun packet(pid: Int, pusi: Boolean, cc: Int, body: ByteArray, adaptation: ByteArray? = null): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (((if (pusi) 0x40 else 0) or (pid shr 8)) and 0xFF).toByte()
        p[2] = (pid and 0xFF).toByte()
        val afc = if (adaptation != null) 2 else 1
        p[3] = ((afc shl 4) or (cc and 0x0F)).toByte()
        if (adaptation != null) System.arraycopy(adaptation, 0, p, 4, adaptation.size)
        else System.arraycopy(body, 0, p, 4, body.size)
        return p
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun pat() = packet(
        0, true, 0,
        bytes(0x00, 0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xF0, 0x00, 0, 0, 0, 0)
    )

    private fun pmt() = packet(
        0x1000, true, 0,
        bytes(
            0x00, 0x02, 0xB0, 0x17, 0x00, 0x01, 0xC1, 0x00, 0x00, 0xE1, 0x01, 0xF0, 0x00,
            0x1B, 0xE1, 0x00, 0xF0, 0x00,
            0x0F, 0xE1, 0x02, 0xF0, 0x00,
            0, 0, 0, 0
        )
    )

    private var pcrCc = 0
    private fun pcrPacket(base: Long): ByteArray {
        val a = ByteArray(184) { 0xFF.toByte() }
        a[0] = 183.toByte()
        a[1] = 0x10
        a[2] = (base shr 25).toByte()
        a[3] = (base shr 17).toByte()
        a[4] = (base shr 9).toByte()
        a[5] = (base shr 1).toByte()
        a[6] = (((base and 1L) shl 7) or 0x7E).toByte()
        a[7] = 0
        return packet(0x101, false, pcrCc++, ByteArray(0), adaptation = a)
    }

    private var videoCc = 0
    private fun videoPacket(key: Boolean): ByteArray {
        val pes = bytes(0x00, 0x00, 0x01, 0xE0, 0x00, 0x00, 0x80, 0x80, 0x05, 0x21, 0x00, 0x01, 0x00, 0x01)
        val aud = bytes(0x00, 0x00, 0x00, 0x01, 0x09, 0xF0)
        val nal = if (key) bytes(0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x28) else bytes(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A)
        return packet(0x100, true, videoCc++, pes + aud + nal)
    }
}
