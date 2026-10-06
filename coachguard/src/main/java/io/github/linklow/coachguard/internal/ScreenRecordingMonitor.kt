package io.github.linklow.coachguard.internal

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import io.github.linklow.coachguard.signals.ScreenCaptureState
import java.util.function.Consumer

/**
 * Tracks whether the activity's windows are visible in a screen recording or screen share.
 * Uses `WindowManager.addScreenRecordingCallback`, available from Android 15. Must be started and
 * stopped on the main thread.
 */
internal class ScreenRecordingMonitor(private val activity: Activity) {

    @Volatile
    var state: ScreenCaptureState = ScreenCaptureState.UNKNOWN
        private set

    private var callback: Consumer<Int>? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (callback != null) return
        val granted = activity.checkSelfPermission(Manifest.permission.DETECT_SCREEN_RECORDING) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val newCallback = Consumer<Int> { recordingState -> state = toCaptureState(recordingState) }
        try {
            val initialState = activity.windowManager.addScreenRecordingCallback(activity.mainExecutor, newCallback)
            state = toCaptureState(initialState)
            callback = newCallback
        } catch (e: RuntimeException) {
            state = ScreenCaptureState.UNKNOWN
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val registered = callback ?: return
        callback = null
        state = ScreenCaptureState.UNKNOWN
        try {
            activity.windowManager.removeScreenRecordingCallback(registered)
        } catch (e: RuntimeException) {
            // The window may already be gone; nothing left to unregister.
        }
    }

    private fun toCaptureState(recordingState: Int): ScreenCaptureState =
        if (recordingState == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) {
            ScreenCaptureState.CAPTURED
        } else {
            ScreenCaptureState.NOT_CAPTURED
        }
}
