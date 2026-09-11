package com.sleqa.wififix.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by [WatchdogAlarm]. Idempotent: starting an already-running service just
 * re-enters onStartCommand.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_FORCE_ENABLE, false)) {
            WifiGuardService.send(context, WifiGuardService.ACTION_FORCE_ENABLE)
        } else {
            WifiGuardService.start(context)
            WatchdogAlarm.armPeriodic(context)
        }
    }

    companion object {
        const val EXTRA_FORCE_ENABLE = "force_enable"
    }
}
