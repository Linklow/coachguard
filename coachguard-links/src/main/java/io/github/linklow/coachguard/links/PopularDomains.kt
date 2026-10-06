package io.github.linklow.coachguard.links

import java.io.IOException
import java.io.InputStream

/**
 * Popular registered domains whose hosts are reported as low risk. Port of `is_popular` in
 * `ml/url_features.py`.
 *
 * File format, one entry per line (lines starting with `#` are comments):
 * - `+domain`: a popular registered domain;
 * - `-host`: a host under a popular domain that was seen hosting phishing, left to the model;
 * - `!suffix`: a public suffix inside a popular domain (`web.core.windows.net` in `windows.net`),
 *   below which names belong to the platform's customers, not to the popular domain's owner.
 */
internal class PopularDomains(
    private val domains: Set<String>,
    private val abusedHosts: Set<String>,
    private val boundaries: Set<String>,
) {

    /**
     * Walks the host's label-boundary suffixes from longest to shortest: `mail.google.com` matches
     * `google.com`, `google.com.evil.example` does not, and the walk stops at a boundary.
     */
    fun contains(parsed: ParsedHost): Boolean {
        if (parsed.isIp || parsed.host in abusedHosts) return false
        val host = parsed.host
        var start = 0
        while (true) {
            val dot = host.indexOf('.', start)
            if (dot < 0) return false // only the last label is left: never a registered domain
            val suffix = host.substring(start)
            if (suffix in boundaries) return false
            if (suffix in domains) return true
            start = dot + 1
        }
    }

    companion object {
        fun read(input: InputStream): PopularDomains {
            val domains = HashSet<String>()
            val abused = HashSet<String>()
            val boundaries = HashSet<String>()
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                when {
                    line.isBlank() || line.startsWith("#") -> Unit
                    line.startsWith("+") -> domains += line.substring(1)
                    line.startsWith("-") -> abused += line.substring(1)
                    line.startsWith("!") -> boundaries += line.substring(1)
                    else -> throw IOException("Malformed popular-domain entry: $line")
                }
            }
            if (domains.isEmpty()) throw IOException("Popular-domain list is empty")
            return PopularDomains(domains, abused, boundaries)
        }
    }
}
