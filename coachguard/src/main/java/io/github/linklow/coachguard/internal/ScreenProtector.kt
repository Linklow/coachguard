package io.github.linklow.coachguard.internal

import android.view.Window
import android.view.WindowManager

/**
 * Marks a window as secure (`FLAG_SECURE`), so its content shows up black in screenshots, screen
 * recordings, screen sharing and remote-access viewers. Works on every Android version, which makes
 * it the counterpart to screen-capture detection, available only from Android 15.
 */
internal object ScreenProtector {

    /**
     * Returns `true` if this call set the flag and [restore] should clear it later; `false` if the
     * window was already secure (the app manages the flag itself) or the flag could not be set.
     */
    fun protect(window: Window): Boolean {
        val alreadySecure = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (alreadySecure) return false
        return try {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun restore(window: Window) {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (e: RuntimeException) {
            // The window may already be gone.
        }
    }
}
