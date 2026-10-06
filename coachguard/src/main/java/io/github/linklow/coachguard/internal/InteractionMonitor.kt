package io.github.linklow.coachguard.internal

import android.os.Build
import android.os.SystemClock
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import io.github.linklow.coachguard.behavior.BehaviorFeatures
import io.github.linklow.coachguard.behavior.BehaviorRecorder

// MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, added in API 29. On older versions the system
// never sets this bit, so checking it there is harmless.
private const val FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x2

/**
 * Observes how the user interacts with one window:
 * - touches that arrive while another app's window covers the screen, the mark of overlay
 *   (tapjacking) attacks;
 * - the timing of touches and focus changes, which feeds the behavioral model.
 *
 * Works by wrapping the window's [Window.Callback], the same technique crash-reporting and
 * analytics SDKs use. Must be started and stopped on the main thread.
 */
internal class InteractionMonitor(private val window: Window) {

    @Volatile
    var obscuredCount: Int = 0
        private set

    @Volatile
    var partiallyObscuredCount: Int = 0
        private set

    private var recorder: BehaviorRecorder? = null
    private var wrapper: InteractionRecordingCallback? = null

    fun start() {
        if (wrapper != null) return
        val original = window.callback ?: return
        val focused = window.peekDecorView()?.hasWindowFocus() ?: false
        recorder = BehaviorRecorder(startedAt = SystemClock.uptimeMillis(), initiallyFocused = focused)
        val newWrapper = InteractionRecordingCallback(original, ::onTouch, ::onFocusChanged)
        window.callback = newWrapper
        wrapper = newWrapper
    }

    fun stop() {
        val current = wrapper ?: return
        wrapper = null
        current.active = false
        // If another library wrapped the callback after us, unwrapping would drop its wrapper.
        // In that case we stay in the chain as a transparent pass-through.
        if (window.callback === current) {
            window.callback = current.delegate
        }
    }

    /** Behavior since [start], or `null` if the monitor could not attach to the window. */
    fun behavior(): BehaviorFeatures? = recorder?.snapshot(SystemClock.uptimeMillis())

    private fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recorder?.onTouchDown(event.eventTime)
                val flags = event.flags
                if ((flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0) {
                    obscuredCount++
                } else if ((flags and FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) {
                    partiallyObscuredCount++
                }
            }
            MotionEvent.ACTION_UP -> recorder?.onTouchUp(event.eventTime, event.downTime)
        }
    }

    private fun onFocusChanged(hasFocus: Boolean) {
        recorder?.onFocusChanged(hasFocus, SystemClock.uptimeMillis())
    }
}

/**
 * Forwards every call to [delegate] and, while [active], reports touch events and focus changes.
 */
internal class InteractionRecordingCallback(
    val delegate: Window.Callback,
    private val onTouch: (MotionEvent) -> Unit,
    private val onFocusChanged: (Boolean) -> Unit,
) : Window.Callback by delegate {

    @Volatile
    var active: Boolean = true

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (active && event != null) onTouch(event)
        return delegate.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (active) onFocusChanged(hasFocus)
        delegate.onWindowFocusChanged(hasFocus)
    }

    // Window.Callback has two Java default methods. Forward them explicitly so the activity's own
    // implementations keep working no matter how Kotlin delegation treats default methods.

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        // The system calls this only from Android 8.0, where the delegate implements it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) delegate.onPointerCaptureChanged(hasCapture)
    }
}
