package io.github.linklow.coachguard.signals

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class KnownRemoteAccessAppsTest {

    @Test
    fun `manifest queries match the known remote access apps`() {
        // Unit tests run with the module directory as the working directory.
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val packages = document.getElementsByTagName("package")
        val queried = (0 until packages.length)
            .map { packages.item(it).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue }
            .toSet()

        assertEquals(KnownRemoteAccessApps.packages, queried)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
