package com.sleqa.wififix

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Everything that explains *why* a repair attempt failed. Surfaced on the status
 * screen because a bare "setWifiEnabled returned false" is unactionable on its own.
 */
data class Diagnostics(
    val targetSdk: Int,
    val changeWifiStateOp: String,
    val airplaneMode: Boolean,
    val lowPowerStandby: String,
    val deviceOwner: Boolean,
    val androidRelease: String,
) {
    /** True when nothing obvious stands in the way of Tier 1 working. */
    val looksHealthy: Boolean
        get() = targetSdk < 29 && !airplaneMode && changeWifiStateOp != "IGNORED"

    companion object {
        fun collect(ctx: Context): Diagnostics {
            val cr = ctx.contentResolver
            return Diagnostics(
                targetSdk = ctx.applicationInfo.targetSdkVersion,
                changeWifiStateOp = changeWifiStateOp(ctx),
                airplaneMode = isAirplaneModeOn(ctx),
                // A prime suspect for a TV dongle whose radio switches itself
                // off: Android 13+ low power standby can cut networking when the
                // device sleeps.
                // Android 12+ restricts some hidden Settings.Global keys to
                // system apps.  This is diagnostic information only, so a
                // restricted read must never prevent the control screen from
                // opening.
                lowPowerStandby = runCatching {
                    Settings.Global.getString(cr, "low_power_standby_enabled") ?: "unset"
                }.getOrDefault("restricted"),
                deviceOwner = isDeviceOwner(ctx),
                androidRelease = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
        }

        fun isAirplaneModeOn(ctx: Context): Boolean =
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        fun isDeviceOwner(ctx: Context): Boolean = runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName)
        }.getOrDefault(false)

        /**
         * The app-op can be set to IGNORED independently of the permission grant,
         * which produces an otherwise inexplicable `false` from setWifiEnabled.
         */
        private fun changeWifiStateOp(ctx: Context): String = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching "n/a"
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            when (
                ops.unsafeCheckOpNoThrow(
                    "android:change_wifi_state",
                    Process.myUid(),
                    ctx.packageName,
                )
            ) {
                AppOpsManager.MODE_ALLOWED -> "ALLOWED"
                AppOpsManager.MODE_IGNORED -> "IGNORED"
                AppOpsManager.MODE_ERRORED -> "ERRORED"
                AppOpsManager.MODE_DEFAULT -> "DEFAULT"
                else -> "?"
            }
        }.getOrDefault("unknown")
    }
}
