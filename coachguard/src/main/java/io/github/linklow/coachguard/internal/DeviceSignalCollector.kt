package io.github.linklow.coachguard.internal

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.github.linklow.coachguard.behavior.BehaviorAnalysis
import io.github.linklow.coachguard.signals.AccessibilityServiceSignal
import io.github.linklow.coachguard.signals.CallState
import io.github.linklow.coachguard.signals.DeviceSignals
import io.github.linklow.coachguard.signals.KnownRemoteAccessApps
import io.github.linklow.coachguard.signals.ScreenCaptureState

/**
 * Reads device-wide signals that do not depend on a particular screen. Every read is defensive:
 * a failure degrades that one signal to "unknown" instead of failing the whole assessment.
 */
internal class DeviceSignalCollector(context: Context) {

    private val appContext = context.applicationContext

    fun collect(
        screenCapture: ScreenCaptureState,
        obscuredTouchCount: Int,
        partiallyObscuredTouchCount: Int,
        behavior: BehaviorAnalysis?,
    ): DeviceSignals = DeviceSignals(
        callState = callState(),
        screenCapture = screenCapture,
        remoteAccessApps = installedRemoteAccessApps(),
        accessibilityServices = enabledAccessibilityServices(),
        obscuredTouchCount = obscuredTouchCount,
        partiallyObscuredTouchCount = partiallyObscuredTouchCount,
        adbEnabled = adbEnabled(),
        collectedAtMillis = System.currentTimeMillis(),
        behavior = behavior,
    )

    private fun callState(): CallState = try {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        if (audioManager == null) CallState.UNKNOWN else callStateFromAudioMode(audioManager.mode)
    } catch (e: RuntimeException) {
        CallState.UNKNOWN
    }

    private fun installedRemoteAccessApps(): List<String> {
        val packageManager = appContext.packageManager
        return KnownRemoteAccessApps.packages.filter { isInstalled(packageManager, it) }.sorted()
    }

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            getPackageInfoLegacy(packageManager, packageName)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoLegacy(packageManager: PackageManager, packageName: String) {
        packageManager.getPackageInfo(packageName, 0)
    }

    private fun enabledAccessibilityServices(): List<AccessibilityServiceSignal> = try {
        val accessibilityManager = appContext.getSystemService(AccessibilityManager::class.java)
        accessibilityManager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .mapNotNull(::toSignal)
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun toSignal(info: AccessibilityServiceInfo): AccessibilityServiceSignal? {
        val serviceInfo = info.resolveInfo?.serviceInfo ?: return null
        val appFlags = serviceInfo.applicationInfo?.flags ?: 0
        val capabilities = info.capabilities
        return AccessibilityServiceSignal(
            packageName = serviceInfo.packageName,
            serviceName = serviceInfo.name,
            isSystemApp = (appFlags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
            canRetrieveWindowContent =
                (capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0,
            canPerformGestures = (capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0,
            isAccessibilityTool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.isAccessibilityTool else null,
        )
    }

    private fun adbEnabled(): Boolean? = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (e: RuntimeException) {
        null
    }
}
