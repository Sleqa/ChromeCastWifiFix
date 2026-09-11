package com.sleqa.wififix.strategy

import android.content.Context
import android.net.wifi.WifiManager
import com.sleqa.wififix.core.Tier

/**
 * The whole reason this app targets SDK 28.
 *
 * WifiServiceImpl.setWifiEnabled() rejects third-party callers unless
 * isTargetSdkLessThan(Q) holds. At targetSdk 28 it does, so this plain call
 * works where a modern app gets a silent `false`.
 */
class DirectStrategy(context: Context) : EnableStrategy {
    private val app = context.applicationContext
    private val wifi get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    override val tier = Tier.DIRECT

    override fun isAvailable(): Boolean = true

    override fun enable(): StrategyResult = setEnabled(true)

    /** Used only by the "Test now" flow to provoke an outage on purpose. */
    fun disable(): StrategyResult = setEnabled(false)

    fun currentState(): Int = runCatching { wifi.wifiState }.getOrDefault(4)

    @Suppress("DEPRECATION")
    private fun setEnabled(on: Boolean): StrategyResult = try {
        val returned = wifi.setWifiEnabled(on)
        StrategyResult(returned, "setWifiEnabled($on) returned $returned")
    } catch (e: SecurityException) {
        StrategyResult(false, "SecurityException: ${e.message}")
    }
}
