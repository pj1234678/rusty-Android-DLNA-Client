package com.example.rustydlnaclient.perf

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Pure-JVM helpers for the DLNA browse (SOAP/DIDL) parse path.
 *
 * Why this exists: the legacy path runs three whole-payload regex scans
 * plus two DOM parses per browse (~300ms on a 269KB / 800-item listing).
 * This file provides:
 * - [extractResultPayload]: single-scan string extraction of the
 *   `<Result>` payload, skipping the envelope DOM parse. Returns null when
 *   there is no unique match so callers fall back to DOM (same tolerance
 *   as getElementsByTagName("Result"), incl. prefixed `<u:Result>`).
 * - [extractResultPayloadFast]: indexOf-based equivalent of
 *   [extractResultPayload] (unique-or-null contract). No regex.
 * - [decodeEntities]: single-pass decode of the named entities the legacy
 *   chained replaces handled, plus a second `&amp;` pass that preserves the
 *   legacy double-decode of `&amp;amp;` -> `&` (SOAP layer + DIDL layer).
 *   The legacy `é` -> `&eacute;` re-encode roundtrip is intentionally
 *   dropped (net no-op; titles end identical — pinned by unit tests).
 * - [fixBareAmpersands]: the same bare-`&` repair the legacy path applied
 *   (kept as a separate pass so behavior stays identical; hot path uses
 *   [decodeAndFix] instead).
 * - [decodeAndFix]: fused `decodeEntities` + `fixBareAmpersands` +
 *   sanitize-allowlist + NUL-strip in two hand-rolled O(n) passes.
 *   Output is byte-identical to running the legacy three-stage chain
 *   (minus the `<DIDL-Lite>` fragment wrap, which [parseDidl] handles).
 * - [parseDidl]: streaming DIDL scan returning containers and videos
 *   (each in document order) with zero regex and zero DOM. Field decoding
 *   matches DOM `textContent` for the 5 predefined XML entities; anything
 *   else (e.g. `&eacute;`, numerics) stays literal, exactly as the legacy
 *   sanitize+DOM chain produced. Callers apply their own class filters
 *   and title fallbacks.
 * - [documentBuilder]: thread-local reused builder. DOM builds nothing
 *   per-browse anymore. Call [dropBuilder] after a parse exception so a
 *   poisoned builder is never reused.
 *
 * No Android imports: fully JVM-unit-testable.
 */
object BrowseXml {

    private val resultRe =
        Regex("<(?:\\w+:)?Result>(.*?)</(?:\\w+:)?Result>", RegexOption.DOT_MATCHES_ALL)

    private val bareAmpRe = Regex("&(?![a-zA-Z0-9#]{1,7};)")

    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance()
    }

    private val builders = ThreadLocal<DocumentBuilder>()

    fun extractResultPayload(response: String): String? {
        val matches = resultRe.findAll(response).toList()
        if (matches.size != 1) return null
        return matches[0].groupValues[1]
    }

    /**
     * Single-pass entity decode. Deliberately regex-free: Kotlin's
     * `Regex.replace` with a transform lambda goes superlinear on Android
     * (12ms @1KB, 344ms @34KB, 16.7s @269KB on-device; 4ms on JVM, which
     * masked it). This hand-rolled scan is O(n) by construction.
     *
     * Mapping parity with the legacy chained replaces (pinned by unit
     * tests): double-encoded `&amp;amp;` decodes to `&` inline by peeking
     * for the second `amp;`, so no trailing full-string pass is needed.
     */
    fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            when {
                text.startsWith("&lt;", i) -> { sb.append('<'); i += 4 }
                text.startsWith("&gt;", i) -> { sb.append('>'); i += 4 }
                text.startsWith("&amp;", i) -> {
                    // Double-encoded &amp;amp; -> & (was the legacy 2nd pass).
                    if (text.startsWith("amp;", i + 5)) i += 9 else i += 5
                    sb.append('&')
                }
                text.startsWith("&quot;", i) -> { sb.append('"'); i += 6 }
                text.startsWith("&apos;", i) -> { sb.append('\''); i += 6 }
                text.startsWith("&eacute;", i) -> { sb.append('é'); i += 8 }
                else -> { sb.append('&'); i++ }
            }
        }
        return sb.toString()
    }

    fun fixBareAmpersands(text: String): String {
        return bareAmpRe.replace(text, "&amp;")
    }

    /**
     * IndexOf-based equivalent of [extractResultPayload]: returns the
     * payload iff there is exactly one `<Result>` / `<prefix:Result>`
     * open tag (no attributes, `>` immediately after the name — same
     * strictness as the regex) with a matching close tag after it.
     * Anything else returns null so the caller takes the DOM fallback.
     */
    fun extractResultPayloadFast(response: String): String? {
        var openEnd = -1
        var opens = 0
        var idx = 0
        val n = response.length
        while (idx < n) {
            val lt = response.indexOf('<', idx)
            if (lt < 0 || lt + 1 >= n) break
            if (response[lt + 1] == '/') {
                idx = lt + 2
                continue
            }
            var p = lt + 1
            while (p < n && isTagNameChar(response[p])) p++
            if (p == lt + 1 || p >= n || response[p] != '>') {
                idx = lt + 1
                continue
            }
            val full = response.substring(lt + 1, p)
            if (full.substringAfterLast(':') == "Result") {
                opens++
                if (opens > 1) return null
                openEnd = p + 1
            }
            idx = p + 1
        }
        if (opens != 1) return null
        // First `</[prefix:]Result>` after the open tag (non-greedy, like the regex).
        var cidx = openEnd
        while (cidx < n) {
            val lt = response.indexOf("</", cidx)
            if (lt < 0) return null
            var p = lt + 2
            while (p < n && isTagNameChar(response[p])) p++
            if (p > lt + 2 && p < n && response[p] == '>') {
                val full = response.substring(lt + 2, p)
                if (full.substringAfterLast(':') == "Result") {
                    return response.substring(openEnd, lt)
                }
            }
            cidx = lt + 2
        }
        return null
    }

    /**
     * Fused equivalent of `decodeEntities(x)` then `fixBareAmpersands(x)`
     * then the sanitize core (amp-escape allowlist + NUL strip, minus the
     * `<DIDL-Lite>` fragment wrap handled by [parseDidl]).
     *
     * Pass A reuses [decodeEntities] verbatim. Pass B is hand-rolled O(n):
     * `&(lt|gt|amp|quot|apos);` passes through; every other `&` becomes
     * `&amp;` (this is exactly what fix-then-sanitize produced: fix kept
     * entity-like sequences and escaped bare `&`, sanitize then escaped
     * every `&` outside the 5-name allowlist); `\u0000` is dropped.
     */
    fun decodeAndFix(text: String): String {
        val decoded = decodeEntities(text)
        if (decoded.indexOf('&') < 0 && decoded.indexOf('\u0000') < 0) return decoded
        val sb = StringBuilder(decoded.length)
        var i = 0
        val n = decoded.length
        while (i < n) {
            val c = decoded[i]
            if (c == '\u0000') {
                i++
                continue
            }
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            when {
                decoded.startsWith("&lt;", i) -> { sb.append("&lt;"); i += 4 }
                decoded.startsWith("&gt;", i) -> { sb.append("&gt;"); i += 4 }
                decoded.startsWith("&amp;", i) -> { sb.append("&amp;"); i += 5 }
                decoded.startsWith("&quot;", i) -> { sb.append("&quot;"); i += 6 }
                decoded.startsWith("&apos;", i) -> { sb.append("&apos;"); i += 6 }
                else -> { sb.append("&amp;"); i++ }
            }
        }
        return sb.toString()
    }

    /** One DIDL entry. `title`/`res`/`upnpClass` are null when the element is absent ("" when present-but-empty — same as DOM textContent). */
    data class DidlEntry(
        val isContainer: Boolean,
        val title: String?,
        val id: String,
        val res: String?,
        val upnpClass: String?,
    )

    /**
     * Streaming DIDL scan. Returns `(containers, videos)`, each in document
     * order — callers concatenate containers-first to preserve the legacy
     * two-loop ordering. No regex, no DOM, no whole-string copies beyond
     * the rare fragment wrap.
     *
     * Lenient where servers vary (attributes on `res`, CDATA, comments),
     * strict where parity demands it (exact `container`/`item` tag names —
     * same as `getElementsByTagName`; missing close tag skips that element).
     */
    fun parseDidl(didl: String): Pair<List<DidlEntry>, List<DidlEntry>> {
        var xml = didl
        var k = 0
        while (k < xml.length && xml[k].isWhitespace()) k++
        if (k >= xml.length || xml[k] != '<') {
            xml = "<DIDL-Lite>$xml</DIDL-Lite>"
        }
        val containers = ArrayList<DidlEntry>()
        val items = ArrayList<DidlEntry>()
        val n = xml.length
        var i = 0
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            // Skip comments and processing instructions.
            if (xml.startsWith("<!--", lt)) {
                val e = xml.indexOf("-->", lt + 4)
                i = if (e < 0) n else e + 3
                continue
            }
            if (lt + 1 < n && xml[lt + 1] == '?') {
                val e = xml.indexOf("?>", lt + 2)
                i = if (e < 0) n else e + 2
                continue
            }
            if (lt + 1 < n && xml[lt + 1] == '/') {
                i = lt + 2
                continue
            }
            var p = lt + 1
            while (p < n && isTagNameChar(xml[p])) p++
            if (p == lt + 1) {
                i = lt + 1
                continue
            }
            val rawName = xml.substring(lt + 1, p)
            val isContainer: Boolean = when (rawName) {
                "container" -> true
                "item" -> false
                else -> {
                    i = p
                    continue
                }
            }
            val tagEnd = xml.indexOf('>', p)
            if (tagEnd < 0) break
            val openTag = xml.substring(lt + 1, tagEnd)
            val id = extractAttr(openTag, "id") ?: ""
            if (tagEnd > lt + 1 && xml[tagEnd - 1] == '/') {
                // Self-closing: all fields absent (same as empty DOM element children).
                val entry = DidlEntry(isContainer, null, id, null, null)
                if (isContainer) containers.add(entry) else items.add(entry)
                i = tagEnd + 1
                continue
            }
            val closeTag = if (isContainer) "</container>" else "</item>"
            val closeIdx = xml.indexOf(closeTag, tagEnd + 1)
            if (closeIdx < 0) {
                i = tagEnd + 1
                continue
            }
            val inner = xml.substring(tagEnd + 1, closeIdx)
            val entry = DidlEntry(
                isContainer = isContainer,
                title = extractField(inner, "dc:title"),
                id = id,
                res = extractField(inner, "res"),
                upnpClass = extractField(inner, "upnp:class"),
            )
            if (isContainer) containers.add(entry) else items.add(entry)
            i = closeIdx + closeTag.length
        }
        return containers to items
    }

    private fun isTagNameChar(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'
    }

    /** Minimal attribute reader: exact-name match, single/double quotes, entity-unescaped value (mirrors DOM getAttribute). */
    private fun extractAttr(openTag: String, name: String): String? {
        var i = 0
        val n = openTag.length
        // Skip the tag name itself.
        while (i < n && !openTag[i].isWhitespace() && openTag[i] != '/') i++
        while (i < n) {
            while (i < n && (openTag[i].isWhitespace() || openTag[i] == '/')) i++
            if (i >= n) break
            var s = i
            while (s < n && !openTag[s].isWhitespace() && openTag[s] != '=' && openTag[s] != '/') s++
            if (s == i) {
                i++
                continue
            }
            val attrName = openTag.substring(i, s)
            var j = s
            while (j < n && openTag[j].isWhitespace()) j++
            if (j >= n || openTag[j] != '=') {
                i = s
                continue
            }
            j++
            while (j < n && openTag[j].isWhitespace()) j++
            if (j >= n) break
            val q = openTag[j]
            if (q != '"' && q != '\'') {
                i = j + 1
                continue
            }
            val vStart = j + 1
            val vEnd = openTag.indexOf(q, vStart)
            if (vEnd < 0) break
            if (attrName == name) {
                return unescapeXmlText(openTag.substring(vStart, vEnd))
            }
            i = vEnd + 1
        }
        return null
    }

    /**
     * First direct `<name ...>...</name>` field (delimiter-checked, comment-
     * aware, CDATA-aware). Present-but-empty (incl. self-closing) yields ""
     * — same as DOM textContent; absent yields null.
     */
    private fun extractField(inner: String, name: String): String? {
        val open = "<$name"
        var idx = 0
        val n = inner.length
        while (idx < n) {
            // Skip comments so commented-out-looking tags never match (DOM ignores them).
            if (inner.startsWith("<!--", idx)) {
                val e = inner.indexOf("-->", idx + 4)
                if (e < 0) return null
                idx = e + 3
                continue
            }
            val lt = inner.indexOf(open, idx)
            if (lt < 0) return null
            val after = lt + open.length
            if (after >= n || (inner[after] != '>' && inner[after] != '/' &&
                    !inner[after].isWhitespace())
            ) {
                idx = after
                continue
            }
            val tagEnd = inner.indexOf('>', after)
            if (tagEnd < 0) return null
            if (inner[tagEnd - 1] == '/') return ""
            val close = "</$name>"
            val closeIdx = inner.indexOf(close, tagEnd + 1)
            if (closeIdx < 0) return null
            var content = inner.substring(tagEnd + 1, closeIdx)
            if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
                content = content.substring(9, content.length - 3)
            }
            return unescapeXmlText(content)
        }
        return null
    }

    /** DOM-textContent equivalent for the 5 predefined XML entities; everything else stays literal. */
    private fun unescapeXmlText(text: String): String {
        if (text.indexOf('&') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            when {
                text.startsWith("&lt;", i) -> { sb.append('<'); i += 4 }
                text.startsWith("&gt;", i) -> { sb.append('>'); i += 4 }
                text.startsWith("&amp;", i) -> { sb.append('&'); i += 5 }
                text.startsWith("&quot;", i) -> { sb.append('"'); i += 6 }
                text.startsWith("&apos;", i) -> { sb.append('\''); i += 6 }
                else -> { sb.append('&'); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * Returns the text of the first direct child named [tag], or null.
     * Replaces per-item `getElementsByTagName(tag).item(0)?.textContent`,
     * which re-scans the whole item subtree on every call (3-4 scans per
     * item x 800 items). One linear child walk instead.
     *
     * Matches [tag] against both qualified (`dc:title`, as produced by the
     * namespace-unaware parser used here) and local names, so behavior is
     * identical to getElementsByTagName for these flat DIDL elements.
     */
    fun childText(parent: Element, tag: String): String? {
        val kids = parent.childNodes
        var i = 0
        val n = kids.length
        while (i < n) {
            val node = kids.item(i)
            if (node.nodeType == Node.ELEMENT_NODE &&
                (tag == node.nodeName || tag == node.localName)
            ) {
                return node.textContent
            }
            i++
        }
        return null
    }

    fun documentBuilder(): DocumentBuilder {
        var builder = builders.get()
        if (builder == null) {
            builder = factory.newDocumentBuilder()
            builders.set(builder)
        }
        return builder
    }

    fun dropBuilder() {
        builders.remove()
    }
}
