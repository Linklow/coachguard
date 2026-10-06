package io.github.linklow.coachguard.internal

import android.content.Context
import android.util.AtomicFile
import io.github.linklow.coachguard.behavior.BehaviorProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Persists behavioral profiles in the app's no-backup directory, so they are never copied to cloud
 * backups or restored onto another device. Each file holds only running averages and variances
 * of a few timing statistics.
 *
 * Methods do disk I/O; call them on [ioExecutor].
 */
internal class BehaviorProfileStore(context: Context) {

    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun load(profileId: String): BehaviorProfile? {
        val file = AtomicFile(fileFor(profileId))
        return try {
            file.openRead().use { stream ->
                val properties = Properties().apply { load(stream) }
                BehaviorProfile.fromProperties(properties.stringPropertyNames().associateWith { properties.getProperty(it) })
            }
        } catch (e: IOException) {
            null // Includes FileNotFoundException: no profile has been saved yet.
        } catch (e: IllegalArgumentException) {
            null // Malformed escape sequence in the file.
        }
    }

    fun save(profileId: String, profile: BehaviorProfile) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val file = AtomicFile(fileFor(profileId))
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            Properties().apply { putAll(profile.toProperties()) }.store(stream, null)
            file.finishWrite(stream)
        } catch (e: IOException) {
            if (stream != null) file.failWrite(stream)
        }
    }

    fun delete(profileId: String) {
        AtomicFile(fileFor(profileId)).delete()
    }

    private fun fileFor(profileId: String) = File(directory, profileFileName(profileId))

    private companion object {
        const val DIRECTORY_NAME = "coachguard"
    }
}

/**
 * File name for [profileId]. The ID is hashed so that user identifiers passed by the host app do
 * not appear in the file system.
 */
internal fun profileFileName(profileId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
    val hex = buildString {
        for (byte in digest.take(16)) {
            val value = byte.toInt() and 0xff
            append(Character.forDigit(value shr 4, 16))
            append(Character.forDigit(value and 0x0f, 16))
        }
    }
    return "behavior-$hex.properties"
}

/** Single background thread for profile I/O, so reads and writes happen in order and off the main thread. */
internal val ioExecutor: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "coachguard-io").apply { isDaemon = true }
    }
}
