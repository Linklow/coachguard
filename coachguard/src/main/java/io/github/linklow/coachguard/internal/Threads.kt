package io.github.linklow.coachguard.internal

import android.os.Looper

internal fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Must be called on the main thread" }
}
