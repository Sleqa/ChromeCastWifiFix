package com.sleqa.wififix.strategy

import com.sleqa.wififix.core.Tier

/**
 * [accepted] means the call did not obviously fail. It is NEVER proof that the
 * radio came on -- Android 13+ can post a confirmation dialog and return true
 * without touching it. Only GuardStateMachine's WIFI_STATE_ENABLED confirmation
 * decides success.
 */
data class StrategyResult(val accepted: Boolean, val detail: String)

interface EnableStrategy {
    val tier: Tier

    /** Cheap check: is this tier provisioned on this device at all? */
    fun isAvailable(): Boolean

    fun enable(): StrategyResult
}
