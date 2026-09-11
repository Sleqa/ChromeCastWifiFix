package com.sleqa.wififix.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sleqa.wififix.Prefs

/**
 * Brings the watchdog back after a reboot or an app update. Alarms do not
 * survive a reboot either, so this is also where the periodic alarm is re-armed.
 *
 * Starting a foreground service from a broadcast is legal here because the app
 * targets SDK 28 -- Android 12's ForegroundServiceStartNotAllowedException only
 * applies from targetSdk 31.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!Prefs(context).guardEnabled) return
                WifiGuardService.start(context)
                WatchdogAlarm.armPeriodic(context)
            }
        }
    }
}
