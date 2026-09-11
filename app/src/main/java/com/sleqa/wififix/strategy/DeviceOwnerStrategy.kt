package com.sleqa.wififix.strategy

import android.content.Context
import com.sleqa.wififix.Diagnostics
import com.sleqa.wififix.core.Tier

/**
 * Same call as [DirectStrategy]; it just clears WifiServiceImpl's permission
 * check by a different branch (isDeviceOrProfileOwner rather than the legacy
 * targetSdk one), so it keeps working even if the legacy hatch is ever closed.
 *
 * Unavailable unless the app was provisioned as device owner, which requires a
 * device with no accounts on it -- see README. Reports as unavailable and is
 * skipped otherwise, costing nothing.
 */
class DeviceOwnerStrategy(
    context: Context,
    private val direct: DirectStrategy,
) : EnableStrategy {
    private val app = context.applicationContext

    override val tier = Tier.DEVICE_OWNER

    override fun isAvailable(): Boolean = Diagnostics.isDeviceOwner(app)

    override fun enable(): StrategyResult = direct.enable()
}
