package io.github.linklow.coachguard.signals

/**
 * Apps that let another person see or control this device. Tools that only control *other*
 * devices (TeamViewer Remote Control, Chrome Remote Desktop, RealVNC Viewer, ...) are left out
 * on purpose: having them installed says nothing about this device being controlled.
 *
 * Inclusion criterion: the app can capture this device's screen (a MediaProjection service) or
 * control it (an accessibility service). For example, TeamViewer Remote Control 15.82 declares
 * neither `FOREGROUND_SERVICE_MEDIA_PROJECTION` nor an accessibility service, as checked on a
 * device; the variant scammers ask victims to install is TeamViewer QuickSupport.
 *
 * Keep in sync with the `<queries>` element in the library's AndroidManifest.xml; a unit test
 * checks this.
 */
internal object KnownRemoteAccessApps {
    val packages: Set<String> = setOf(
        "com.anydesk.anydeskandroid", // AnyDesk
        "com.anydesk.adcontrol.ad1", // AnyDesk control plugin
        "com.teamviewer.quicksupport.market", // TeamViewer QuickSupport
        "com.teamviewer.host.market", // TeamViewer Host
        "com.carriez.flutter_hbb", // RustDesk (F-Droid / GitHub, not on Google Play)
        "com.sand.airdroid", // AirDroid
        "com.sand.airdroidbiz", // AirDroid Business Daemon
        "com.sand.aircast", // AirDroid Cast
        "com.zoho.assist.agent", // Zoho Assist customer app
        "com.splashtop.sos", // Splashtop SOS
        "com.splashtop.streamer.addon.knox", // Splashtop add-on for Samsung
        "com.realvnc.server", // RealVNC Server
    )
}
