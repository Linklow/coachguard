package io.github.linklow.coachguard.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors ml/test_url_features.py; both implementations must agree on every case. */
class HostFeaturesTest {

    @Test
    fun `parses hosts the same way as the specification`() {
        val cases = mapOf(
            "https://www.Example.COM/login?x=1" to Expected("example.com", userinfo = false, port = false, ip = false),
            "http://paypal.com@evil.example/" to Expected("evil.example", userinfo = true, port = false, ip = false),
            "evil.example:8080/path" to Expected("evil.example", userinfo = false, port = true, ip = false),
            "http://192.168.1.10/admin" to Expected("192.168.1.10", userinfo = false, port = false, ip = true),
            "http://[2001:db8::1]:443/" to Expected("2001:db8::1", userinfo = false, port = true, ip = true),
            "HTTPS://Secure-Login.Bank.Example./" to Expected("secure-login.bank.example", userinfo = false, port = false, ip = false),
            "www.example.com" to Expected("example.com", userinfo = false, port = false, ip = false),
            "https://xn--pple-43d.com" to Expected("xn--pple-43d.com", userinfo = false, port = false, ip = false),
            "https://example.com#frag" to Expected("example.com", userinfo = false, port = false, ip = false),
            "https://example.com\\@evil.com" to Expected("example.com", userinfo = false, port = false, ip = false),
        )
        cases.forEach { (url, expected) ->
            val parsed = HostFeatures.parse(url)
            assertNotNull(url, parsed)
            assertEquals(url, expected, Expected(parsed!!.host, parsed.hasUserinfo, parsed.hasPort, parsed.isIp))
        }
        assertNull(HostFeatures.parse("https:///path"))
        assertNull(HostFeatures.parse(""))
    }

    @Test
    fun `numeric features`() {
        val values = HostFeatures.numericFeatures(HostFeatures.parse("http://secure-login.bank1.example")!!)

        assertEquals(listOf(26.0, 3.0, 1.0, 1.0, 12.0), values.take(5))
        assertEquals(1.0, HostFeatures.entropy("aabb"), 1e-12)
    }

    @Test
    fun `token keys`() {
        val keys = HostFeatures.tokenKeys(HostFeatures.parse("ab.cd")!!)

        val expected = setOf(
            "c:^ab", "c:ab.", "c:b.c", "c:.cd", "c:cd$",
            "c:^ab.", "c:ab.c", "c:b.cd", "c:.cd$",
            "c:^ab.c", "c:ab.cd", "c:b.cd$",
            "t:cd", "s:ab.cd", "l:ab",
        )
        assertEquals(expected, keys.toSet())
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `n-grams are cut by code point`() {
        // U+1F600 is one code point but two UTF-16 units.
        val keys = HostFeatures.tokenKeys(HostFeatures.parse("a😀b.c")!!)

        assertEquals(true, "c:a😀b" in keys)
        assertEquals(5.0, HostFeatures.numericFeatures(HostFeatures.parse("a😀b.c")!!)[0], 0.0)
    }

    @Test
    fun `bucket uses standard CRC-32`() {
        // 0xCBF43926 is the standard CRC-32 check value of "123456789".
        assertEquals((0xCBF43926L % HostFeatures.BUCKETS).toInt(), HostFeatures.bucket("123456789"))
    }

    private data class Expected(val host: String, val userinfo: Boolean, val port: Boolean, val ip: Boolean)
}
