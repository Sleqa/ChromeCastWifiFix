package com.sleqa.wififix.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Wakes the device periodically so the watchdog still works while the dongle is
 * in standby, and resurrects the service if the system killed it.
 *
 * setExactAndAllowWhileIdle needs no SCHEDULE_EXACT_ALARM permission here: that
 * requirement only applies to apps targeting API 31+, and this one targets 28.
 * Exact alarms are one-shot, so each firing re-arms the next.
 */
object WatchdogAlarm {
    private const val PERIODIC_INTERVAL_MS = 15 * 60_000L

    private const val RC_PERIODIC = 100
    private const val RC_DEAD_MAN = 101

    fun armPeriodic(ctx: Context, delayMs: Long = PERIODIC_INTERVAL_MS) {
        schedule(ctx, RC_PERIODIC, delayMs, forceEnable = false)
    }

    /**
     * A one-shot safety net used by the "Test now" flow: it turns the radio back
     * on unconditionally, independently of the state machine, so a bug in the
     * watchdog cannot strand the device with no network.
     */
    fun armDeadMansSwitch(ctx: Context, delayMs: Long) {
        schedule(ctx, RC_DEAD_MAN, delayMs, forceEnable = true)
    }

    private fun schedule(ctx: Context, requestCode: Int, delayMs: Long, forceEnable: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, WatchdogReceiver::class.java)
            .putExtra(WatchdogReceiver.EXTRA_FORCE_ENABLE, forceEnable)
        val pi = PendingIntent.getBroadcast(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = System.currentTimeMillis() + delayMs
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
    }
}
