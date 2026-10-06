package io.github.linklow.coachguard.links

import java.util.zip.CRC32
import kotlin.math.log2

/** The part of a URL the model judges, plus structural flags read from the rest of the authority. */
internal class ParsedHost(
    val host: String,
    val hasUserinfo: Boolean,
    val hasPort: Boolean,
    val isIp: Boolean,
)

/**
 * Feature extraction for the host model. This is a line-by-line port of `ml/url_features.py`,
 * which is the specification; a golden-file test checks that both produce the same scores.
 *
 * Only ASCII letters are lower-cased and strings are processed as Unicode code points, so the
 * result does not depend on platform-specific Unicode rules.
 */
internal object HostFeatures {

    const val FEATURE_SPEC_VERSION = 1
    const val BUCKETS = 1 shl 18
    private val NGRAM_SIZES = intArrayOf(3, 4, 5)
    private val IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")

    /** Names of the numeric features, in model order. */
    val NUMERIC_FEATURES = listOf(
        "host_length",
        "label_count",
        "hyphen_count",
        "digit_count",
        "max_label_length",
        "entropy",
        "is_ip",
        "has_punycode",
        "has_userinfo",
        "has_port",
    )

    fun parse(url: String): ParsedHost? {
        var text = url.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd >= 0) text = text.substring(schemeEnd + 3)

        var end = text.length
        for (delimiter in charArrayOf('/', '?', '#', '\\')) {
            val index = text.indexOf(delimiter)
            if (index in 0 until end) end = index
        }
        var authority = text.substring(0, end)

        val hasUserinfo = '@' in authority
        if (hasUserinfo) authority = authority.substring(authority.lastIndexOf('@') + 1)

        var host: String
        val hasPort: Boolean
        val isIp: Boolean
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            host = if (close > 0) authority.substring(1, close) else authority.substring(1)
            val rest = if (close > 0) authority.substring(close + 1) else ""
            hasPort = rest.startsWith(":") && rest.length > 1
            isIp = true
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                hasPort = authority.length > colon + 1
                host = authority.substring(0, colon)
            } else {
                hasPort = false
                host = authority
            }
            host = lowerAscii(host).trimEnd('.')
            isIp = IPV4.matches(host)
        }

        host = lowerAscii(host)
        if (host.startsWith("www.")) host = host.substring(4)
        if (host.isEmpty()) return null
        return ParsedHost(host, hasUserinfo, hasPort, isIp)
    }

    fun numericFeatures(parsed: ParsedHost): DoubleArray {
        val host = parsed.host
        val labels = host.split('.')
        return doubleArrayOf(
            codePointLength(host).toDouble(),
            labels.size.toDouble(),
            host.count { it == '-' }.toDouble(),
            host.count { it in '0'..'9' }.toDouble(),
            labels.maxOf { codePointLength(it) }.toDouble(),
            entropy(host),
            if (parsed.isIp) 1.0 else 0.0,
            if ("xn--" in host) 1.0 else 0.0,
            if (parsed.hasUserinfo) 1.0 else 0.0,
            if (parsed.hasPort) 1.0 else 0.0,
        )
    }

    /** Character n-grams of the host and whole-label tokens, sorted by code point, without duplicates. */
    fun tokenKeys(parsed: ParsedHost): List<String> {
        val host = parsed.host
        val keys = HashSet<String>()
        val marked = ("^$host$").codePoints().toArray()
        for (n in NGRAM_SIZES) {
            for (start in 0..marked.size - n) {
                keys += "c:" + String(marked, start, n)
            }
        }
        val labels = host.split('.')
        if (labels.size >= 2) {
            keys += "t:" + labels.last()
            keys += "s:" + labels[labels.size - 2] + "." + labels.last()
        }
        for (label in labels.dropLast(1)) {
            keys += "l:$label"
        }
        return keys.sortedWith(CodePointOrder)
    }

    fun bucket(key: String): Int {
        val crc = CRC32()
        crc.update(key.toByteArray(Charsets.UTF_8))
        return (crc.value % BUCKETS).toInt()
    }

    fun entropy(text: String): Double {
        val counts = LinkedHashMap<Int, Int>()
        text.codePoints().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val total = codePointLength(text).toDouble()
        return -counts.values.sumOf { count -> (count / total) * log2(count / total) }
    }

    private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

    private fun lowerAscii(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in 'A'..'Z') c + 32 else c)
    }

    /** Orders strings by Unicode code point, as Python does, rather than by UTF-16 unit. */
    private object CodePointOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val left = a.codePoints().toArray()
            val right = b.codePoints().toArray()
            for (i in 0 until minOf(left.size, right.size)) {
                if (left[i] != right[i]) return left[i].compareTo(right[i])
            }
            return left.size.compareTo(right.size)
        }
    }
}
