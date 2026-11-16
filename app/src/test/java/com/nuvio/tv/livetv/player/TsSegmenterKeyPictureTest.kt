package com.nuvio.tv.livetv.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream like TF1's replays: no "picture starts here" marks, and the clock on a packet id of
 * its own. H.264 at 25 frames a second, a key picture (with its SPS) every second.
 */
class TsSegmenterKeyPictureTest {
    private val dir: File = Files.createTempDirectory("ts-seg").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun firstPieceIsCutAtTheFirstKeyPictureAfterTwoSeconds() {
        val seg = TsSegmenter(dir, fixedTargetSec = 10)
        feed(seg, stream(seconds = 3.0, keyEvery = 25))
        assertEquals(2.0, seg.totalSec, 0.05)
        assertTrue(seg.lastCutClean)
        assertTrue(File(dir, "index.m3u8").exists())
    }

    @Test
    fun withoutKeyPicturesTheFirstPieceIsCutByLengthOnAVideoFrame() {
        val seg = TsSegmenter(dir, fixedTargetSec = 10)
        feed(seg, stream(seconds = 5.0, keyEvery = 0))
        assertEquals(4.0, seg.totalSec, 0.05)
        assertFalse(seg.lastCutClean)
    }

    private fun feed(seg: TsSegmenter, bytes: ByteArray) {
        // In odd sizes, as it comes off the network.
        var i = 0
        while (i < bytes.size) {
            val n = minOf(1_000, bytes.size - i)
            seg.write(bytes, i, n)
            i += n
        }
    }

    private fun stream(seconds: Double, keyEvery: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pat())
        out.write(pmt())
        val frames = (seconds * 25).toInt()
        for (n in 0 until frames) {
            val base = 900_000L + n * 3_600L // 90 kHz clock, 40 ms a frame
            out.write(pcrPacket(base))
            out.write(videoPacket(key = keyEvery > 0 && n % keyEvery == 0))
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
        if (adaptation != null) {
            System.arraycopy(adaptation, 0, p, 4, adaptation.size)
        } else {
            System.arraycopy(body, 0, p, 4, body.size)
        }
        return p
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun pat() = packet(
        0, true, 0,
        // pointer, table 0, length 13, stream id, version, section numbers, program 1 -> PMT 0x1000, CRC
        bytes(0x00, 0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xF0, 0x00, 0, 0, 0, 0)
    )

    private fun pmt() = packet(
        0x1000, true, 0,
        // pointer, table 2, length 23, program 1, version, sections, PCR on 0x101, no program
        // descriptors, H.264 on 0x100, AAC on 0x102, CRC
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
        a[0] = 183.toByte() // adaptation field length: the whole packet
        a[1] = 0x10 // PCR present, no random access mark
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
