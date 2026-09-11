package com.sleqa.wififix

import android.content.Context
import android.os.SystemClock

/**
 * Small settings store. Snooze deliberately understands "until reboot", which is
 * expressed as a sentinel plus a boot token rather than a timestamp.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("wififix", Context.MODE_PRIVATE)

    var guardEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var lastProbe: String
        get() = sp.getString(KEY_PROBE, "") ?: ""
        set(v) = sp.edit().putString(KEY_PROBE, v).apply()

    /**
     * Epoch millis the snooze runs to, 0 when not snoozed. An "until reboot"
     * snooze is stored as [UNTIL_REBOOT] and is cleared automatically once the
     * boot token changes.
     */
    var snoozeUntil: Long
        get() {
            val raw = sp.getLong(KEY_SNOOZE, 0L)
            if (raw == UNTIL_REBOOT) {
                return if (sp.getLong(KEY_SNOOZE_BOOT, 0L) == bootToken()) UNTIL_REBOOT else 0L
            }
            return raw
        }
        set(v) = sp.edit()
            .putLong(KEY_SNOOZE, v)
            .putLong(KEY_SNOOZE_BOOT, bootToken())
            .apply()

    fun describeSnooze(now: Long): String = when (val until = snoozeUntil) {
        0L -> "off"
        UNTIL_REBOOT -> "until reboot"
        else -> {
            val mins = ((until - now) / 60_000).coerceAtLeast(0)
            if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "${mins}m left"
        }
    }

    companion object {
        /** Sentinel for a snooze that lasts until the device restarts. */
        const val UNTIL_REBOOT = Long.MAX_VALUE

        private const val KEY_ENABLED = "guard_enabled"
        private const val KEY_SNOOZE = "snooze_until"
        private const val KEY_SNOOZE_BOOT = "snooze_boot_token"
        private const val KEY_PROBE = "last_probe"

        /**
         * Wall-clock time of the last boot, rounded to absorb clock jitter. A
         * change means the device restarted.
         */
        private fun bootToken(): Long =
            (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 10_000
    }
}
