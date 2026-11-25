package com.nuvio.tv.livetv.data

import java.io.InputStream

/**
 * Takes out of a guide file, before the XML reader sees them, the shows it would only skip: a
 * channel nobody uses, or a time outside the days kept ([leaveOut] says which). The XML reader
 * went through every one of them character by character, and that was most of a guide update:
 * on a TV box a 72 MB guide took 4.8 s to read with nothing kept at all, against half a second
 * to find where each show starts and ends, which is all this does. With one day kept, most of a
 * guide file is shows to skip.
 *
 * Only shows whose start tag is plain are taken out: the attributes in quotes, the values in
 * plain ASCII with no character references. Anything else (and any show while [leaveOut] says
 * no) goes to the XML reader as it is, so what it reads is unchanged: only whole <programme>
 * elements are ever left out. Comments and CDATA sections are stepped over, so a "<programme"
 * or "</programme>" inside them counts for nothing. A show that's never closed ends at the next
 * show or at the end of the guide, as for the reader. The line breaks of what's left out are
 * kept, so the line numbers the reader gives for a broken guide still point at the right place.
 * Files that aren't in an ASCII-based encoding (UTF-16…) go through untouched.
 */
internal class XmltvSkipAhead(
    private val source: InputStream,
    /** Whether to leave out the show with these attribute values (null: the attribute isn't there). */
    private val leaveOut: (channel: String?, start: String?, stop: String?) -> Boolean,
    size: Int = 256 * 1024
) : InputStream() {
    private val buf = ByteArray(size)
    private var pos = 0
    private var lim = 0
    private var ended = false
    private val out = ByteArray(size)
    private var outPos = 0
    private var outLen = 0
    private var state = PASS
    /** Line breaks of what was left out, still to be given out. */
    private var newlines = 0
    private var started = false
    /** Not an ASCII-based encoding: everything goes through as it is. */
    private var plain = false

    /** Shows left out so far. */
    var leftOut = 0
        private set

    override fun read(): Int {
        if (outPos >= outLen && !fill()) return -1
        return out[outPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (outPos >= outLen && !fill()) return -1
        val n = minOf(len, outLen - outPos)
        System.arraycopy(out, outPos, b, off, n)
        outPos += n
        return n
    }

    override fun available(): Int = outLen - outPos

    override fun close() = source.close()

    /** Puts what comes next in [out]; false at the end of the file. */
    private fun fill(): Boolean {
        outPos = 0
        outLen = 0
        if (!started) {
            started = true
            plain = !asciiBased()
        }
        // Half of [out] at least stays free, so a step never runs out of room for a mark.
        while (outLen < out.size / 2) {
            if (newlines > 0) {
                val n = minOf(newlines, out.size - outLen)
                java.util.Arrays.fill(out, outLen, outLen + n, NL)
                outLen += n
                newlines -= n
                continue
            }
            if (pos >= lim && !more(1)) break
            if (plain) { pos += emit(pos, lim - pos); continue }
            when (state) {
                PASS -> pass()
                PASS_COMMENT -> through(COMMENT_END, keep = true)
                PASS_CDATA -> through(CDATA_END, keep = true)
                SKIP -> skip()
                SKIP_COMMENT -> through(COMMENT_END, keep = false)
                SKIP_CDATA -> through(CDATA_END, keep = false)
            }
        }
        return outLen > 0
    }

    /** At least [n] bytes from [pos] in [buf], unless the file ends first (or [n] is more than it holds). */
    private fun more(n: Int): Boolean {
        if (lim - pos >= n) return true
        if (ended || n > buf.size) return false
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        while (lim - pos < n) {
            val k = source.read(buf, lim, buf.size - lim)
            if (k < 0) { ended = true; break }
            lim += k
        }
        return lim - pos >= n
    }

    /** Copies up to [n] bytes from [buf] at [from] to [out]: how many fitted. */
    private fun emit(from: Int, n: Int): Int {
        val k = minOf(n, out.size - outLen)
        System.arraycopy(buf, from, out, outLen, k)
        outLen += k
        return k
    }

    private fun countNewlines(from: Int, to: Int) {
        for (i in from until to) if (buf[i] == NL) newlines++
    }

    private fun at(mark: ByteArray): Boolean {
        if (!more(mark.size)) return false
        for (i in mark.indices) if (buf[pos + i] != mark[i]) return false
        return true
    }

    /** [mark] (letters in any case) followed by a space, ">" or "/". */
    private fun atTag(mark: ByteArray): Boolean {
        if (!more(mark.size + 1)) return false
        for (i in mark.indices) {
            val c = buf[pos + i]
            val m = mark[i]
            if (c != m && !(m in A_LOWER..Z_LOWER && c == (m - CASE).toByte())) return false
        }
        val after = buf[pos + mark.size]
        return isSpace(after) || after == GT || after == SLASH
    }

    private fun pass() {
        // Up to the next "<!" (a comment or CDATA section) or "<p" (perhaps a show): every other
        // tag goes through with the text around it, in one piece.
        var i = pos
        val end = lim - 1
        while (i < end) {
            if (buf[i] == LT) {
                val c = buf[i + 1]
                if (c == BANG || c == P_LOWER || c == P_UPPER) break
            }
            i++
        }
        if (i >= end) {
            // All that's here, but a last "<" whose next byte isn't read yet.
            val to = if (!ended && lim > pos && buf[lim - 1] == LT) lim - 1 else lim
            if (to > pos) pos += emit(pos, to - pos)
            else if (!more(2)) pos += emit(pos, lim - pos)
            return
        }
        if (i > pos) { pos += emit(pos, i - pos); return }
        when {
            at(COMMENT_START) -> { pos += emit(pos, COMMENT_START.size); state = PASS_COMMENT }
            at(CDATA_START) -> { pos += emit(pos, CDATA_START.size); state = PASS_CDATA }
            atTag(PROGRAMME) && startTag() -> Unit
            else -> pos += emit(pos, 1)
        }
    }

    /**
     * At a show's start tag: leaves it out when it's plain and [leaveOut] says so (true),
     * otherwise changes nothing (false: it goes through).
     */
    private fun startTag(): Boolean {
        // The whole tag, up to its ">" outside quotes.
        var k = PROGRAMME.size
        var quote: Byte = 0
        while (true) {
            if (pos + k >= lim && !more(k + 1)) return false // cut off, or too long: the reader sees it
            val c = buf[pos + k]
            if (quote != 0.toByte()) { if (c == quote) quote = 0 }
            else if (c == QUOTE || c == APOS) quote = c
            else if (c == GT) break
            else if (c == LT) return false
            k++
        }
        val close = pos + k // the ">"
        val selfClosing = buf[close - 1] == SLASH
        val end = if (selfClosing) close - 1 else close
        var channel: String? = null
        var start: String? = null
        var stop: String? = null
        var i = pos + PROGRAMME.size
        while (true) {
            while (i < end && isSpace(buf[i])) i++
            if (i >= end) break
            val nameFrom = i
            while (i < end && isNameByte(buf[i])) i++
            if (i == nameFrom) return false
            val nameTo = i
            while (i < end && isSpace(buf[i])) i++
            if (i >= end || buf[i] != EQ) return false
            i++
            while (i < end && isSpace(buf[i])) i++
            if (i >= end || (buf[i] != QUOTE && buf[i] != APOS)) return false
            val q = buf[i]
            val valueFrom = ++i
            while (i < end && buf[i] != q) i++
            if (i >= end) return false
            val valueTo = i
            i++
            val which = when {
                same(nameFrom, nameTo, CHANNEL) -> 1
                same(nameFrom, nameTo, START) -> 2
                same(nameFrom, nameTo, STOP) -> 3
                else -> 0
            }
            if (which == 0) continue
            // A value the reader would change (a character reference, a line break) isn't for here.
            for (j in valueFrom until valueTo) {
                val c = buf[j]
                if (c < SPACE || c > TILDE || c == AMP || c == LT) return false
            }
            when (which) {
                1 -> if (channel == null) channel = channelName(valueFrom, valueTo)
                2 -> if (start == null) start = String(buf, valueFrom, valueTo - valueFrom, Charsets.ISO_8859_1)
                3 -> if (stop == null) stop = String(buf, valueFrom, valueTo - valueFrom, Charsets.ISO_8859_1)
            }
        }
        if (!leaveOut(channel, start, stop)) return false
        leftOut++
        countNewlines(pos, close + 1)
        pos = close + 1
        if (!selfClosing) state = SKIP
        return true
    }

    /** Inside a show left out: up to its end tag, the next show (it was never closed) or the guide's end. */
    private fun skip() {
        // Up to the next "<!", "<p", "</p" or "</t": the only marks that can matter here.
        var i = pos
        val end = lim - 2
        var found = false
        while (i < end) {
            val c = buf[i]
            if (c == LT) {
                val c1 = buf[i + 1]
                if (c1 == BANG || c1 == P_LOWER || c1 == P_UPPER) { found = true; break }
                if (c1 == SLASH) {
                    val c2 = buf[i + 2]
                    if (c2 == P_LOWER || c2 == P_UPPER || c2 == T_LOWER || c2 == T_UPPER) { found = true; break }
                }
            } else if (c == NL) newlines++
            i++
        }
        pos = i
        if (!found) {
            // Its last bytes aren't looked at yet: more, or at the end of the file the rest of it goes.
            if (!more(3)) { countNewlines(pos, lim); pos = lim }
            return
        }
        when {
            atTag(PROGRAMME_END) -> {
                // Counted from [pos]: reading more can move what's in the buffer.
                var k = PROGRAMME_END.size
                while (true) {
                    if (pos + k >= lim && !more(k + 1)) { k = -1; break }
                    val c = buf[pos + k]
                    if (c == GT) break
                    if (!isSpace(c)) { k = -1; break }
                    k++
                }
                if (k < 0) { pos++; return }
                countNewlines(pos, pos + k + 1)
                pos += k + 1
                state = PASS
            }
            atTag(PROGRAMME) || atTag(TV_END) -> state = PASS
            at(COMMENT_START) -> { pos += COMMENT_START.size; state = SKIP_COMMENT }
            at(CDATA_START) -> { pos += CDATA_START.size; state = SKIP_CDATA }
            else -> pos++
        }
    }

    /** Inside a comment or CDATA section: up to [mark], kept or left out. */
    private fun through(mark: ByteArray, keep: Boolean) {
        val found = more(mark.size)
        var i = pos
        val last = lim - mark.size
        var hit = -1
        if (found) {
            while (i <= last) {
                if (buf[i] == mark[0]) {
                    var j = 1
                    while (j < mark.size && buf[i + j] == mark[j]) j++
                    if (j == mark.size) { hit = i; break }
                }
                i++
            }
        }
        // Not in what's here: all but its last bytes (the mark may start there), or all of it at the end of the file.
        val to = if (hit >= 0) hit + mark.size else if (found) lim - (mark.size - 1) else lim
        if (keep) {
            val k = emit(pos, to - pos)
            pos += k
            if (hit >= 0 && pos == to) state = PASS
        } else {
            countNewlines(pos, to)
            pos = to
            if (hit >= 0) state = SKIP
        }
    }

    private var lastChannel = ""
    private var lastChannelBytes = ByteArray(0)

    /** A channel id, the same string as the show before's when it's the same (guides list a channel's shows together). */
    private fun channelName(from: Int, to: Int): String {
        val n = to - from
        val last = lastChannelBytes
        if (n == last.size) {
            var i = 0
            while (i < n && buf[from + i] == last[i]) i++
            if (i == n) return lastChannel
        }
        lastChannelBytes = buf.copyOfRange(from, to)
        lastChannel = String(lastChannelBytes, Charsets.ISO_8859_1)
        return lastChannel
    }

    private fun same(from: Int, to: Int, name: ByteArray): Boolean {
        if (to - from != name.size) return false
        for (i in name.indices) if (buf[from + i] != name[i]) return false
        return true
    }

    /** False for encodings whose markup isn't plain ASCII bytes (UTF-16, UTF-32…). */
    private fun asciiBased(): Boolean {
        more(512)
        val n = lim - pos
        if (n >= 2) {
            val b0 = buf[pos].toInt() and 0xFF
            val b1 = buf[pos + 1].toInt() and 0xFF
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE) || b0 == 0 || b1 == 0) return false
        }
        var p = pos
        if (n >= 3 && buf[p] == 0xEF.toByte() && buf[p + 1] == 0xBB.toByte() && buf[p + 2] == 0xBF.toByte()) p += 3
        val head = String(buf, p, (lim - p).coerceAtLeast(0), Charsets.ISO_8859_1)
        if (!head.startsWith("<?xml")) return true
        val decl = head.substringBefore("?>")
        val encoding = Regex("""encoding\s*=\s*["']([^"']*)["']""").find(decl)?.groupValues?.get(1)?.trim()?.lowercase()
            ?: return true
        return encoding.startsWith("utf-8") || encoding == "utf8" || encoding.startsWith("iso-8859") ||
            encoding.startsWith("iso8859") || encoding.startsWith("latin") || encoding.startsWith("windows-125") ||
            encoding.startsWith("cp125") || encoding.contains("ascii")
    }

    private companion object {
        const val PASS = 0
        const val PASS_COMMENT = 1
        const val PASS_CDATA = 2
        const val SKIP = 3
        const val SKIP_COMMENT = 4
        const val SKIP_CDATA = 5

        const val NL = '\n'.code.toByte()
        const val LT = '<'.code.toByte()
        const val GT = '>'.code.toByte()
        const val SLASH = '/'.code.toByte()
        const val EQ = '='.code.toByte()
        const val QUOTE = '"'.code.toByte()
        const val APOS = '\''.code.toByte()
        const val AMP = '&'.code.toByte()
        const val SPACE = ' '.code.toByte()
        const val TILDE = '~'.code.toByte()
        const val BANG = '!'.code.toByte()
        const val P_LOWER = 'p'.code.toByte()
        const val P_UPPER = 'P'.code.toByte()
        const val T_LOWER = 't'.code.toByte()
        const val T_UPPER = 'T'.code.toByte()
        const val A_LOWER = 'a'.code.toByte()
        const val Z_LOWER = 'z'.code.toByte()
        const val CASE = ('a' - 'A')

        val PROGRAMME = "<programme".toByteArray(Charsets.US_ASCII)
        val PROGRAMME_END = "</programme".toByteArray(Charsets.US_ASCII)
        val TV_END = "</tv".toByteArray(Charsets.US_ASCII)
        val COMMENT_START = "<!--".toByteArray(Charsets.US_ASCII)
        val COMMENT_END = "-->".toByteArray(Charsets.US_ASCII)
        val CDATA_START = "<![CDATA[".toByteArray(Charsets.US_ASCII)
        val CDATA_END = "]]>".toByteArray(Charsets.US_ASCII)
        val CHANNEL = "channel".toByteArray(Charsets.US_ASCII)
        val START = "start".toByteArray(Charsets.US_ASCII)
        val STOP = "stop".toByteArray(Charsets.US_ASCII)

        fun isSpace(c: Byte) = c == SPACE || c == NL || c == '\t'.code.toByte() || c == '\r'.code.toByte()

        fun isNameByte(c: Byte) =
            (c in 'a'.code.toByte()..'z'.code.toByte()) || (c in 'A'.code.toByte()..'Z'.code.toByte()) ||
                (c in '0'.code.toByte()..'9'.code.toByte()) || c == '_'.code.toByte() || c == ':'.code.toByte() ||
                c == '-'.code.toByte() || c == '.'.code.toByte()
    }
}
