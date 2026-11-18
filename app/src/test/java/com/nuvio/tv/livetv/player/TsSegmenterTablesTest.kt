package com.nuvio.tv.livetv.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Damaged program tables in a provider's replay: the player gave up on the copy when a PAT's
 * "table starts here" pointed past the packet, and the copy repeated a damaged table at the
 * start of every piece after it.
 */
class TsSegmenterTablesTest {
    private val dir: File = Files.createTempDirectory("ts-tables").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aPatPointingPastThePacketIsNotPassedOn() {
        val seg = TsSegmenter(dir, fixedTargetSec = 10)
        val bad = pat().also { it[4] = 200.toByte() } // pointer field past the packet
        feed(seg, stream(seconds = 10.0, extraAt = 4.0, extra = bad))
        seg.finish()
        val pieces = pieces()
        assertTrue(pieces.size >= 2)
        pieces.forEach { piece ->
            assertTrue(packets(piece).none { it.contentEquals(bad) })
            assertArrayEquals(pat(), packets(piece).first())
        }
    }

    @Test
    fun aPatWithAWrongChecksumDoesNotReplaceTheGoodOne() {
        val seg = TsSegmenter(dir, fixedTargetSec = 10)
        val bad = pat().also { it[16] = 0x07 } // program 1's PMT on 0x1007: checksum now wrong
        feed(seg, stream(seconds = 10.0, extraAt = 4.0, extra = bad))
        seg.finish()
        val pieces = pieces()
        assertTrue(pieces.size >= 2)
        pieces.forEach { piece ->
            assertTrue(packets(piece).none { it.contentEquals(bad) })
            assertArrayEquals(pat(), packets(piece).first())
        }
    }

    @Test
    fun aGoodNewPmtStillReplacesTheKeptOne() {
        val seg = TsSegmenter(dir, fixedTargetSec = 10)
        val pmt2 = pmt(version = 1, audioPid = 0x103)
        feed(seg, stream(seconds = 10.0, extraAt = 4.0, extra = pmt2))
        seg.finish()
        val pieces = pieces()
        assertTrue(pieces.size >= 2)
        assertArrayEquals(pmt(), packets(pieces.first())[1])
        assertArrayEquals(pmt2, packets(pieces.last())[1])
    }

    @Test
    fun goodTablesStillCutTheCopyAsBefore() {
        val seg = TsSegmenter(dir, fixedTargetSec = 10)
        feed(seg, stream(seconds = 3.0))
        assertEquals(2.0, seg.totalSec, 0.05)
    }

    private fun pieces(): List<File> =
        dir.listFiles { f -> f.name.startsWith("seg") && f.name.endsWith(".ts") }!!.sortedBy { it.name.removePrefix("seg").removeSuffix(".ts").toInt() }

    private fun packets(f: File): List<ByteArray> {
        val b = f.readBytes()
        return (0 until b.size / 188).map { b.copyOfRange(it * 188, it * 188 + 188) }
    }

    private fun feed(seg: TsSegmenter, bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val n = minOf(1_000, bytes.size - i)
            seg.write(bytes, i, n)
            i += n
        }
    }

    /** H.264 at 25 frames a second, a key picture every second; [extra] once at [extraAt] s. */
    private fun stream(seconds: Double, extraAt: Double = -1.0, extra: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pat())
        out.write(pmt())
        val frames = (seconds * 25).toInt()
        for (n in 0 until frames) {
            if (extra != null && n == (extraAt * 25).toInt()) out.write(extra)
            val base = 900_000L + n * 3_600L
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

    /** [section] (without its checksum) with its MPEG CRC-32 added. */
    private fun withCrc(section: ByteArray): ByteArray {
        var crc = -1
        for (x in section) {
            crc = crc xor ((x.toInt() and 0xFF) shl 24)
            for (b in 0 until 8) crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
        }
        return section + bytes(crc ushr 24, (crc ushr 16) and 0xFF, (crc ushr 8) and 0xFF, crc and 0xFF)
    }

    // Program 1 -> PMT on 0x1000.
    private fun pat() = packet(0, true, 0, bytes(0x00) + withCrc(bytes(0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xF0, 0x00)))

    // PCR on 0x101, H.264 on 0x100, AAC on 0x102.
    private fun pmt(version: Int = 0, audioPid: Int = 0x102) = packet(
        0x1000, true, 0,
        bytes(0x00) + withCrc(
            bytes(
                0x02, 0xB0, 0x17, 0x00, 0x01, 0xC1 or (version shl 1), 0x00, 0x00, 0xE1, 0x01, 0xF0, 0x00,
                0x1B, 0xE1, 0x00, 0xF0, 0x00,
                0x0F, 0xE0 or (audioPid shr 8), audioPid and 0xFF, 0xF0, 0x00,
            )
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
