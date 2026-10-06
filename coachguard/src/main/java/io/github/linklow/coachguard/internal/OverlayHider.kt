package io.github.linklow.coachguard.internal

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.Window

/**
 * Hides other apps' overlay windows (`TYPE_APPLICATION_OVERLAY`) while the activity is visible.
 * Available from Android 12 and requires the normal `HIDE_OVERLAY_WINDOWS` permission.
 */
internal object OverlayHider {

    /** Returns `true` if overlays are now hidden and [restore] should be called later. */
    fun hide(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val granted = activity.checkSelfPermission(Manifest.permission.HIDE_OVERLAY_WINDOWS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return try {
            activity.window.setHideOverlayWindows(true)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun restore(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            window.setHideOverlayWindows(false)
        } catch (e: RuntimeException) {
            // The window may already be gone.
        }
    }
}
