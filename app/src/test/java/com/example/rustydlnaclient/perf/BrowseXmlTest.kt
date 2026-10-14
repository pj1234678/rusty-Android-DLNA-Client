package com.example.rustydlnaclient.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * JVM-only tests pinning byte-level parity between the legacy browse-parse
 * chain and the fast path. If any of these fail, the optimization changed
 * behavior and must not ship.
 */
class BrowseXmlTest {

    private val didlInner =
        "&lt;container id=&quot;64&quot;&gt;&lt;dc:title&gt;All Grown Up &eacute;t&eacute;&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;&lt;/container&gt;" +
            "&lt;item id=&quot;65&quot;&gt;&lt;dc:title&gt;Fish &amp; Chips&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
            "&lt;res&gt;http://x/v?i=1&amp;k=2&lt;/res&gt;&lt;/item&gt;" +
            "&lt;item id=&quot;66&quot;&gt;&lt;dc:title&gt;Salt &amp; Pepper&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;/item&gt;" +
            "&lt;item id=&quot;67&quot;&gt;&lt;dc:title&gt;Tom & Jerry&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;/item&gt;"

    private fun envelope(resultOpen: String = "<Result>", resultClose: String = "</Result>"): String {
        return "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
            resultOpen + didlInner + resultClose +
            "<NumberReturned>3</NumberReturned></u:BrowseResponse></s:Body></s:Envelope>"
    }

    /** The exact legacy chained replaces, as the test oracle. */
    private fun legacyDecodeChain(s: String): String {
        return s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    @Test
    fun extract_plainResult_returnsPayload() {
        val payload = BrowseXml.extractResultPayload(envelope())
        assertTrue(payload != null && payload.startsWith("&lt;container"))
        assertTrue(payload!!.endsWith("&lt;/item&gt;"))
    }

    @Test
    fun extract_prefixedResult_returnsPayload() {
        val payload = BrowseXml.extractResultPayload(envelope("<u:Result>", "</u:Result>"))
        assertTrue(payload != null && payload.startsWith("&lt;container"))
    }

    @Test
    fun extract_missingResult_returnsNullForDomFallback() {
        assertNull(BrowseXml.extractResultPayload("<Envelope><Body/></Envelope>"))
    }

    @Test
    fun decode_namedEntities_matchesLegacyChain() {
        val raw = "&lt;a&gt; &quot;q&quot; &apos;x&apos; plain"
        assertEquals(legacyDecodeChain(raw), BrowseXml.decodeEntities(raw))
        assertEquals("<a> \"q\" 'x' plain", BrowseXml.decodeEntities(raw))
    }

    @Test
    fun decode_doubleEncodedAmp_matchesLegacyDoubleDecode() {
        assertEquals("&", BrowseXml.decodeEntities("&amp;amp;"))
        assertEquals(legacyDecodeChain("&amp;amp;"), BrowseXml.decodeEntities("&amp;amp;"))
    }

    @Test
    fun decode_doubleEncodedWithSuffix_matchesLegacy() {
        assertEquals("&lt;", BrowseXml.decodeEntities("&amp;amp;lt;"))
        assertEquals(legacyDecodeChain("&amp;amp;lt;"), BrowseXml.decodeEntities("&amp;amp;lt;"))
    }

    @Test
    fun decode_eacute_becomesChar() {
        assertEquals("étè", BrowseXml.decodeEntities("&eacute;t&egrave;".replace("&egrave;", "è")))
    }

    @Test
    fun decode_leavesBareAmpersand_forFixPass() {
        assertEquals("Fish & Chips", BrowseXml.decodeEntities("Fish & Chips"))
    }

    @Test
    fun fixBareAmpersands_repairsBare_keepsEntities() {
        assertEquals("Fish &amp; Chips", BrowseXml.fixBareAmpersands("Fish & Chips"))
        assertEquals("a&amp;b &eacute; c", BrowseXml.fixBareAmpersands("a&amp;b &eacute; c"))
    }

    @Test
    fun pipeline_fastPath_matchesLegacyDomPath() {
        // Legacy production path: whole-response amp fix + eacute conversion,
        // envelope DOM parse, textContent, chained decode, amp fix.
        val fixed = envelope()
            .replace("&(?![a-zA-Z0-9#]{1,7};)".toRegex(), "&amp;")
            .replace("&eacute;", "é")
        val soapDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(fixed.toByteArray()))
        val legacyResult = BrowseXml.fixBareAmpersands(
            legacyDecodeChain(soapDoc.getElementsByTagName("Result").item(0).textContent),
        )

        // Fast path: regex extract, single-pass decode, amp fix.
        val fastPayload = BrowseXml.extractResultPayload(envelope())!!
        val fastResult = BrowseXml.fixBareAmpersands(BrowseXml.decodeEntities(fastPayload))

        assertEquals(legacyResult, fastResult)
        assertTrue(fastResult.contains("<dc:title>All Grown Up été</dc:title>"))
        assertTrue(fastResult.contains("<dc:title>Fish &amp; Chips</dc:title>"))
        assertTrue(fastResult.contains("<dc:title>Tom &amp; Jerry</dc:title>"))
    }

    @Test
    fun builder_isReusedPerThread_andDropRefreshes() {
        val first = BrowseXml.documentBuilder()
        assertSame(first, BrowseXml.documentBuilder())
        BrowseXml.dropBuilder()
        assertNotSame(first, BrowseXml.documentBuilder())
    }

    @Test
    fun childText_readsFirstDirectChild_likeItemZero() {
        val xml = "<item id=\"7\"><dc:title>T &amp; Co</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"x\">u1</res><res>u2</res></item>"
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        val item = doc.getElementsByTagName("item").item(0) as Element
        // Same values the legacy getElementsByTagName(..).item(0) calls returned.
        assertEquals(
            (item.getElementsByTagName("dc:title").item(0) as Element).textContent,
            BrowseXml.childText(item, "dc:title"),
        )
        assertEquals("T & Co", BrowseXml.childText(item, "dc:title"))
        assertEquals("object.item.videoItem", BrowseXml.childText(item, "upnp:class"))
        assertEquals("u1", BrowseXml.childText(item, "res"))
        assertNull(BrowseXml.childText(item, "missing"))
    }

    @Test
    fun decode_largePayload_matchesLegacyChain() {
        // Note: no &eacute; here — the legacy chain never decoded it (the old
        // code handled é via pre/post conversion outside the chain); eacute
        // mapping is pinned by decode_eacute_becomesChar instead.
        val item = "&lt;item id=&quot;123&quot;&gt;&lt;dc:title&gt;T &amp; M vie&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
            "&lt;res&gt;http://h/v?a=1&amp;b=2&lt;/res&gt;&lt;/item&gt;"
        val sb = StringBuilder("&lt;DIDL-Lite&gt;")
        repeat(1200) { sb.append(item) }
        sb.append("&lt;/DIDL-Lite&gt;")
        val payload = sb.toString()
        assertTrue(payload.length > 200_000)
        assertEquals(legacyDecodeChain(payload), BrowseXml.decodeEntities(payload))
    }

    // ---------- Streaming fast path: parity with the legacy chain ----------

    /** Legacy sanitize core (amp-escape allowlist + NUL strip, no fragment wrap). */
    private fun legacySanitizeCore(s: String): String {
        return s.replace("&(?!(?:lt|gt|amp|quot|apos);)".toRegex(), "&amp;")
            .replace("\u0000", "")
    }

    /**
     * Full production-equivalent chain for one Result payload
     * (decodeEntities -> amp fix -> sanitize core). Uses decodeEntities —
     * not the ancient chained replaces — because that is what the shipped
     * code ran (eacute pinned by decode_eacute_becomesChar).
     */
    private fun legacyDecodeFix(s: String): String {
        return legacySanitizeCore(
            BrowseXml.fixBareAmpersands(BrowseXml.decodeEntities(s)),
        )
    }

    @Test
    fun extractFast_matchesRegex_plainPrefixedAndEdgeCases() {
        // Plain + prefixed agree.
        assertEquals(BrowseXml.extractResultPayload(envelope()), BrowseXml.extractResultPayloadFast(envelope()))
        assertEquals(
            BrowseXml.extractResultPayload(envelope("<u:Result>", "</u:Result>")),
            BrowseXml.extractResultPayloadFast(envelope("<u:Result>", "</u:Result>")),
        )
        // Missing / multiple -> null on both.
        assertNull(BrowseXml.extractResultPayloadFast("<Envelope><Body/></Envelope>"))
        val two = envelope() + envelope()
        assertNull(BrowseXml.extractResultPayload(two))
        assertNull(BrowseXml.extractResultPayloadFast(two))
        // Attributes on Result: regex is strict (no match) -> fast must also decline.
        val attr = envelope("<Result foo=\"1\">", "</Result>")
        assertNull(BrowseXml.extractResultPayload(attr))
        assertNull(BrowseXml.extractResultPayloadFast(attr))
        // Stray close before the open element still yields the payload on both.
        val stray = "<Envelope></Result>" + envelope() + "</Envelope>"
        assertEquals(BrowseXml.extractResultPayload(stray), BrowseXml.extractResultPayloadFast(stray))
        // Empty payload.
        val emptyEnv = envelope().replace(didlInner, "")
        assertEquals("", BrowseXml.extractResultPayload(emptyEnv))
        assertEquals("", BrowseXml.extractResultPayloadFast(emptyEnv))
    }

    @Test
    fun decodeAndFix_matchesLegacyChain() {
        val cases = listOf(
            "plain title",
            "Fish & Chips & Tom & Jerry",
            "&lt;tag&gt; &quot;q&quot; &apos;x&apos;",
            "a &amp; b &amp;amp; c",
            "All Grown Up &eacute;t&eacute;",
            "double &amp;eacute; stays",
            "numeric &#233; stays literal",
            "unknown &bogus; entity",
            "trailing amp &",
            "NUL\u0000here",
            "&lt;dc:title&gt;Nested &amp; escaped&lt;/dc:title&gt;",
        )
        for (raw in cases) {
            assertEquals("case: $raw", legacyDecodeFix(raw), BrowseXml.decodeAndFix(raw))
        }
    }

    @Test
    fun decodeAndFix_largePayload_matchesLegacyChain() {
        val item = "&lt;item id=&quot;123&quot;&gt;&lt;dc:title&gt;T &amp; M vie&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
            "&lt;res&gt;http://h/v?a=1&amp;b=2&lt;/res&gt;&lt;/item&gt;"
        val sb = StringBuilder("&lt;DIDL-Lite&gt;")
        repeat(1200) { sb.append(item) }
        sb.append("&lt;/DIDL-Lite&gt;")
        val payload = sb.toString()
        assertEquals(legacyDecodeFix(payload), BrowseXml.decodeAndFix(payload))
    }

    /** Legacy DOM oracle for one DIDL document (mirrors the old MainActivity loops). */
    private fun legacyDomEntries(didl: String): Pair<List<String>, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(didl.toByteArray(Charsets.UTF_8)))
        fun kids(parent: Element, tag: String): String? {
            val nl = parent.getElementsByTagName(tag)
            return if (nl.length == 0) null else nl.item(0).textContent
        }
        val outC = mutableListOf<String>()
        val conts = doc.getElementsByTagName("container")
        for (i in 0 until conts.length) {
            val c = conts.item(i) as Element
            if (kids(c, "upnp:class")?.contains("object.container.storageFolder") == true) {
                outC.add("${c.getAttribute("id")}|${kids(c, "dc:title") ?: "Unknown Folder"}")
            }
        }
        val outV = mutableListOf<String>()
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val it = items.item(i) as Element
            if (kids(it, "upnp:class")?.contains("object.item.videoItem") == true) {
                val t = (kids(it, "dc:title") ?: "Unknown Video").replace("&eacute;", "é")
                outV.add("${it.getAttribute("id")}|$t|${kids(it, "res")}")
            }
        }
        return outC to outV
    }

    /** New streaming pipeline mapped with the same filters/fallbacks as MainActivity. */
    private fun streamEntries(didl: String): Pair<List<String>, List<String>> {
        val (cs, vs) = BrowseXml.parseDidl(didl)
        val outC = mutableListOf<String>()
        for (e in cs) {
            if (e.upnpClass?.contains("object.container.storageFolder") == true) {
                outC.add("${e.id}|${e.title ?: "Unknown Folder"}")
            }
        }
        val outV = mutableListOf<String>()
        for (e in vs) {
            if (e.upnpClass?.contains("object.item.videoItem") == true) {
                val t = (e.title ?: "Unknown Video").replace("&eacute;", "é")
                outV.add("${e.id}|$t|${e.res}")
            }
        }
        return outC to outV
    }

    private fun sampleDidl(): String {
        return "<DIDL-Lite xmlns:dc=\"d\" xmlns:upnp=\"u\">" +
            "<container id=\"64\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>All Grown Up été</dc:title>" +
            "<upnp:class>object.container.storageFolder</upnp:class></container>" +
            "<container id=\"70\"><dc:title>No class here</dc:title></container>" +
            "<container id=\"71\"><upnp:class>object.container.storageFolder</upnp:class></container>" +
            "<container id=\"72\"><dc:title></dc:title>" +
            "<upnp:class>object.container.album</upnp:class></container>" +
            "<item id=\"65\" parentID=\"64\" restricted=\"1\">" +
            "<dc:title>Fish &amp; Chips</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/mp4:*\">http://x/v?i=1&amp;k=2</res>" +
            "<res>http://x/second</res></item>" +
            "<item id=\"66\"><dc:title>Salt &amp; Pepper</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class></item>" +
            "<item id=\"67\"><dc:title>Tom &amp; Jerry</dc:title>" +
            "<upnp:class>object.item.audioItem</upnp:class>" +
            "<res>http://x/a</res></item>" +
            "<item id=\"68\"><dc:title><![CDATA[Raw <brackets> & stuff]]></dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res>http://x/cdata</res></item>" +
            "<!-- <item id=\"999\"><dc:title>Fake</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class></item> -->" +
            "<item id=\"69\"/>" +
            "</DIDL-Lite>"
    }

    @Test
    fun parseDidl_matchesDom_oracle() {
        // Both oracles take the same valid DIDL (i.e. post-chain state).
        val didl = sampleDidl()
        assertEquals(legacyDomEntries(didl), streamEntries(didl))
    }

    @Test
    fun parseDidl_fieldSemantics() {
        val (cs, vs) = BrowseXml.parseDidl(sampleDidl())
        // Parser itself applies no class filter: all entries returned in document order.
        assertEquals(listOf("64", "70", "71", "72"), cs.map { it.id })
        assertEquals(listOf("65", "66", "67", "68", "69"), vs.map { it.id })
        // Missing title -> null (caller falls back), empty res element handling.
        assertEquals("All Grown Up été", cs[0].title)
        assertNull(cs[1].upnpClass)
        assertNull(cs[2].title)
        // First res wins, protocolInfo attrs tolerated, entities decoded like textContent.
        val v65 = vs.first { it.id == "65" }
        assertEquals("Fish & Chips", v65.title)
        assertEquals("http://x/v?i=1&k=2", v65.res)
        assertNull(vs.first { it.id == "66" }.res)
        // CDATA content unwrapped.
        assertEquals("Raw <brackets> & stuff", vs.first { it.id == "68" }.title)
        // Commented-out fake item ignored.
        assertTrue(vs.none { it.id == "999" })
        // Self-closing item: all fields absent.
        val v69 = vs.first { it.id == "69" }
        assertNull(v69.title)
        assertNull(v69.res)
        assertNull(v69.upnpClass)
    }

    @Test
    fun parseDidl_singleQuotedAndMissingId() {
        val didl = "<DIDL-Lite><container id='42'>" +
            "<dc:title>Q</dc:title><upnp:class>object.container.storageFolder</upnp:class>" +
            "</container><item><dc:title>N</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class></item></DIDL-Lite>"
        val (cs, vs) = BrowseXml.parseDidl(didl)
        assertEquals("42", cs[0].id)
        assertEquals("", vs[0].id)
    }

    @Test
    fun parseDidl_fragmentWithoutRoot_isWrapped() {
        val frag = "<container id=\"1\"><dc:title>A</dc:title>" +
            "<upnp:class>object.container.storageFolder</upnp:class></container>"
        val (cs, _) = BrowseXml.parseDidl(frag)
        assertEquals(1, cs.size)
        assertEquals("A", cs[0].title)
    }

    @Test
    fun pipeline_endToEnd_legacyVsStreaming() {
        // Build an 800-entry SOAP envelope: the realistic DLNA shape
        // (single DIDL-Lite root, as servers send it).
        val itemSb = StringBuilder("&lt;DIDL-Lite xmlns:dc=&quot;d&quot; xmlns:upnp=&quot;u&quot;&gt;")
        repeat(400) { k ->
            itemSb.append("&lt;container id=&quot;c$k&quot; parentID=&quot;0&quot;&gt;")
                .append("&lt;dc:title&gt;Folder $k &amp; Buddies&lt;/dc:title&gt;")
                .append("&lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;")
                .append("&lt;/container&gt;")
        }
        repeat(400) { k ->
            itemSb.append("&lt;item id=&quot;v$k&quot; parentID=&quot;0&quot;&gt;")
                .append("&lt;dc:title&gt;Video $k &amp; Pals &eacute;té&lt;/dc:title&gt;")
                .append("&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;")
                .append("&lt;res protocolInfo=&quot;http-get:*:video/mp4:*&quot;&gt;")
                .append("http://srv/v$k?a=1&amp;b=2&lt;/res&gt;&lt;/item&gt;")
        }
        itemSb.append("&lt;/DIDL-Lite&gt;")
        val env = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"x\"><s:Body>" +
            "<u:BrowseResponse xmlns:u=\"y\"><Result>" + itemSb.toString() +
            "</Result><NumberReturned>800</NumberReturned></u:BrowseResponse></s:Body></s:Envelope>"

        // Legacy chain.
        val legacyPayload = BrowseXml.extractResultPayload(env)!!
        val legacyDidl = legacyDecodeFix(legacyPayload)
        val legacy = legacyDomEntries(legacyDidl)

        // Streaming chain.
        val fastPayload = BrowseXml.extractResultPayloadFast(env)!!
        assertEquals(legacyPayload, fastPayload)
        val streamDidl = BrowseXml.decodeAndFix(fastPayload)
        assertEquals(legacyDidl, streamDidl)
        assertEquals(legacy, streamEntries(streamDidl))
        assertEquals(400, streamEntries(streamDidl).first.size)
        assertEquals(400, streamEntries(streamDidl).second.size)
    }

    @Test
    fun perf_legacyVsStreaming_printOnly() {
        // No speed assert (JVM != Android ART); prints numbers into the test
        // report so the algorithmic win is visible. On-device proof comes
        // from PerfBenchmark xml rows (same columns before/after).
        val itemSb = StringBuilder("&lt;DIDL-Lite&gt;")
        repeat(400) { k ->
            itemSb.append("&lt;container id=&quot;c$k&quot; parentID=&quot;0&quot;&gt;")
                .append("&lt;dc:title&gt;Folder $k &amp; Buddies &eacute;té&lt;/dc:title&gt;")
                .append("&lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;")
                .append("&lt;/container&gt;")
        }
        repeat(400) { k ->
            itemSb.append("&lt;item id=&quot;v$k&quot; parentID=&quot;0&quot;&gt;")
                .append("&lt;dc:title&gt;Video $k &amp; Pals&lt;/dc:title&gt;")
                .append("&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;")
                .append("&lt;res&gt;http://srv/v$k?a=1&amp;b=2&lt;/res&gt;&lt;/item&gt;")
        }
        itemSb.append("&lt;/DIDL-Lite&gt;")
        val env = "<s:Envelope><s:Body><u:BrowseResponse><Result>" + itemSb.toString() +
            "</Result></u:BrowseResponse></s:Body></s:Envelope>"
        assertTrue(env.length > 150_000)

        fun legacyRun(): Long {
            val t0 = System.nanoTime()
            val p = BrowseXml.extractResultPayload(env)!!
            val d = legacyDecodeFix(p)
            legacyDomEntries(d)
            return (System.nanoTime() - t0) / 1_000_000
        }

        fun streamRun(): Long {
            val t0 = System.nanoTime()
            val p = BrowseXml.extractResultPayloadFast(env)!!
            val d = BrowseXml.decodeAndFix(p)
            streamEntries(d)
            return (System.nanoTime() - t0) / 1_000_000
        }

        // Warmup.
        repeat(2) { legacyRun() }
        repeat(2) { streamRun() }
        val legacyMs = (0 until 5).map { legacyRun() }.sorted()
        val streamMs = (0 until 5).map { streamRun() }.sorted()
        println("PERF800 legacy_ms_median=${legacyMs[2]} stream_ms_median=${streamMs[2]} env_chars=${env.length}")
        println("PERF800 legacy_runs=$legacyMs stream_runs=$streamMs")
    }
}
