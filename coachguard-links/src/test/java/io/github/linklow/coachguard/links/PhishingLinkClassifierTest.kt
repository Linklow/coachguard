package io.github.linklow.coachguard.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.math.abs

class PhishingLinkClassifierTest {

    private val classifier = PhishingLinkClassifier.create()

    @Test
    fun `matches the reference scorer on every golden URL`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden-predictions.tsv")
        assertNotNull("golden-predictions.tsv is produced by ml/train_phishing_model.py", stream)
        val rows = stream!!.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("expected golden rows", rows.size >= 200)

        var maxDifference = 0.0
        var popularRows = 0
        for (row in rows) {
            val (url, expectedProbability, expectedPopular) = row.split('\t')
            val assessment = classifier.assess(url)
            if (expectedProbability == "none") {
                assertNull(url, assessment.host)
                continue
            }
            assertNotNull(url, assessment.host)
            maxDifference = maxOf(maxDifference, abs(assessment.probability - expectedProbability.toDouble()))
            assertEquals(url, expectedProbability.toDouble(), assessment.probability, 1e-9)
            assertEquals(url, expectedPopular.toBooleanStrict(), assessment.isPopularDomain)
            if (assessment.isPopularDomain) popularRows++
        }
        assertTrue("golden data should cover popular domains", popularRows > 0)
        println("golden rows: ${rows.size}, popular: $popularRows, max difference: $maxDifference")
    }

    @Test
    fun `popular domains match on label boundaries and respect abused hosts`() {
        // Same cases as test_popular in ml/test_url_features.py.
        val popular = PopularDomains(
            domains = setOf("google.com", "example.co.uk", "windows.net"),
            abusedHosts = setOf("sites.google.com"),
            boundaries = setOf("web.core.windows.net"),
        )
        val cases = mapOf(
            "https://google.com" to true,
            "https://mail.google.com/x" to true,
            "https://sites.google.com/view/x" to false,
            "https://google.com.evil.example" to false,
            "https://notgoogle.com" to false,
            "https://shop.example.co.uk" to true,
            "https://co.uk" to false,
            "http://8.8.8.8" to false,
            "https://login.windows.net" to true,
            "https://evil.z1.web.core.windows.net" to false,
            "https://web.core.windows.net" to false,
        )
        cases.forEach { (url, expected) ->
            assertEquals(url, expected, popular.contains(HostFeatures.parse(url)!!))
        }
    }

    @Test
    fun `popular host is low risk without reasons but keeps the model score`() {
        val assessment = classifier.assess("https://mail.google.com/mail/u/0/")

        assertTrue(assessment.isPopularDomain)
        assertEquals(LinkRiskLevel.LOW, assessment.level)
        assertTrue(assessment.reasons.isEmpty())
        assertTrue(assessment.probability > 0.0)
    }

    @Test
    fun `look-alike of a popular domain is not treated as popular`() {
        val assessment = classifier.assess("https://google.com.account-verify.example/")

        assertEquals(false, assessment.isPopularDomain)
        assertTrue(assessment.level != LinkRiskLevel.LOW)
    }

    @Test
    fun `reasons explain the push toward phishing, strongest first`() {
        val assessment = classifier.assess("https://appleid.apple.com.verify-account-session.xyz/")

        assertTrue(assessment.reasons.isNotEmpty())
        assertTrue(assessment.reasons.size <= 5)
        assertTrue(assessment.reasons.all { it.contribution > 0 })
        val contributions = assessment.reasons.map { it.contribution }
        assertEquals(contributions.sortedDescending(), contributions)
    }

    @Test
    fun `scheme, www and path do not change the result`() {
        val variants = listOf(
            "https://www.example-bank.com/",
            "http://example-bank.com",
            "example-bank.com/login/step2?session=1",
            "HTTPS://WWW.EXAMPLE-BANK.COM",
        )
        val probabilities = variants.map { classifier.assess(it).probability }.toSet()

        assertEquals(1, probabilities.size)
    }

    @Test
    fun `url without a host is not judged`() {
        val assessment = classifier.assess("https:///nothing")

        assertNull(assessment.host)
        assertEquals(LinkRiskLevel.LOW, assessment.level)
        assertEquals(0.0, assessment.probability, 0.0)
        assertTrue(assessment.reasons.isEmpty())
    }

    @Test
    fun `corrupt model files are rejected`() {
        val valid = javaClass.classLoader!!
            .getResourceAsStream("io/github/linklow/coachguard/links/host-model.bin")!!
            .use { it.readBytes() }

        expectIOException { HostModel.parse(valid.copyOf(valid.size - 1)) }
        expectIOException { HostModel.parse(valid + byteArrayOf(0)) }
        expectIOException { HostModel.parse(valid.copyOf().also { it[0] = 'X'.code.toByte() }) }
        expectIOException { HostModel.parse(valid.copyOf().also { it[8] = 99 }) } // feature spec version
        HostModel.parse(valid)
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
