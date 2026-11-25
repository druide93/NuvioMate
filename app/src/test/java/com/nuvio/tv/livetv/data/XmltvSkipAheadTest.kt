package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class XmltvSkipAheadTest {

    /** What the XML reader gets, with the shows of channel "B" left out. */
    private fun filtered(xml: String, size: Int = 256 * 1024, leave: (String?, String?, String?) -> Boolean = { c, _, _ -> c == "B" }): String {
        val input = XmltvSkipAhead(xml.byteInputStream(Charsets.UTF_8), leave, size)
        return String(input.readBytes(), Charsets.UTF_8)
    }

    @Test
    fun leavesOutWholeShowsAndKeepsTheirLineBreaks() {
        val xml = "<tv>\n<programme channel=\"A\" start=\"1\"><title>a</title></programme>\n" +
            "<programme start=\"2\"\n channel='B'>\n<title>b</title>\n</programme>\n" +
            "<programme channel=\"C\"><title>c</title></programme>\n</tv>\n"
        assertEquals(
            "<tv>\n<programme channel=\"A\" start=\"1\"><title>a</title></programme>\n" +
                "\n\n\n\n" +
                "<programme channel=\"C\"><title>c</title></programme>\n</tv>\n",
            filtered(xml)
        )
    }

    @Test
    fun endTagsInsideCdataAndCommentsDontEndTheShow() {
        val xml = "<tv><programme channel=\"B\"><desc><![CDATA[x </programme> <programme channel=\"A\">]]></desc>" +
            "<!-- </programme> --></programme><programme channel=\"A\"/></tv>"
        assertEquals("<tv><programme channel=\"A\"/></tv>", filtered(xml))
    }

    @Test
    fun showsInsideCommentsAreLeftAlone() {
        val xml = "<tv><!-- <programme channel=\"B\"></programme> --><programme channel=\"A\"></programme></tv>"
        assertEquals(xml, filtered(xml))
    }

    @Test
    fun aShowNeverClosedEndsAtTheNextShowOrTheGuidesEnd() {
        val xml = "<tv><programme channel=\"B\"><title>b</title>\n<programme channel=\"A\"></programme>" +
            "<programme channel=\"B\"><title>b2</title></tv>"
        assertEquals("<tv>\n<programme channel=\"A\"></programme></tv>", filtered(xml))
    }

    @Test
    fun startTagsThatArentPlainGoThroughAsTheyAre() {
        val tags = listOf(
            "<programme channel=\"B&amp;\"></programme>", // a character reference
            "<programme channel=B></programme>", // no quotes
            "<programme channel=\"B\" broken></programme>", // an attribute without a value
            "<programme channel=\"Bé\"></programme>" // not plain ASCII
        )
        val leave: (String?, String?, String?) -> Boolean = { _, _, _ -> true }
        tags.forEach { tag -> assertEquals(tag, filtered("<tv>$tag</tv>", leave = leave).removePrefix("<tv>").removeSuffix("</tv>")) }
    }

    @Test
    fun valuesAndMissingAttributesReachTheTest() {
        val seen = ArrayList<Triple<String?, String?, String?>>()
        filtered("<tv><programme stop='9' channel = \"A\" start=\"1\"/><programme/></tv>", leave = { c, s, e -> seen += Triple(c, s, e); false })
        assertEquals(listOf(Triple("A", "1", "9"), Triple(null, null, null)), seen)
    }

    @Test
    fun theSameWhateverTheBufferSize() {
        val sb = StringBuilder("<?xml version=\"1.0\"?>\n<tv>\n")
        repeat(400) { i ->
            val ch = if (i % 3 == 0) "B" else "A"
            sb.append("<programme start=\"$i\" channel=\"$ch\">\n<title>t$i</title>")
            if (i % 7 == 0) sb.append("<desc><![CDATA[</programme>]]></desc>")
            if (i % 11 == 0) sb.append("<!-- <programme channel=\"A\"> -->")
            sb.append("\n</programme>\n")
        }
        sb.append("</tv>\n")
        val xml = sb.toString()
        val expected = filtered(xml)
        listOf(300, 301, 512, 1000, 4096).forEach { size -> assertEquals("buffer $size", expected, filtered(xml, size)) }
    }

    @Test
    fun utf16GoesThroughUntouched() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><tv><programme channel=\"B\"></programme></tv>"
        val bytes = xml.toByteArray(Charsets.UTF_16)
        val out = XmltvSkipAhead(bytes.inputStream(), { _, _, _ -> true }).readBytes()
        assertEquals(xml, String(out, Charsets.UTF_16))
    }
}
