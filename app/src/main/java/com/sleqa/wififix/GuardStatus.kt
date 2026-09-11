package com.sleqa.wififix

import com.sleqa.wififix.core.GuardSnapshot

/**
 * In-process handoff from the service to the status screen. Both live in the
 * same process, so a volatile reference is enough and avoids dragging in a
 * broadcast or binder round trip.
 */
object GuardStatus {
    @Volatile
    var snapshot: GuardSnapshot? = null
        private set

    @Volatile
    var serviceRunning: Boolean = false

    @Volatile
    var updatedAt: Long = 0L

    fun publish(s: GuardSnapshot) {
        snapshot = s
        updatedAt = System.currentTimeMillis()
    }

    fun cleared() {
        serviceRunning = false
        snapshot = null
    }
}
