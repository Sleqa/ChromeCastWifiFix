package com.sleqa.wififix.core

/**
 * Retry delays, clamped at the last rung so an outage that never recovers
 * settles into a slow poll instead of hammering the radio.
 */
class Backoff(private val ladderMs: List<Long>) {
    init {
        require(ladderMs.isNotEmpty()) { "backoff ladder must not be empty" }
    }

    fun delayFor(attempt: Int): Long =
        ladderMs[attempt.coerceIn(0, ladderMs.lastIndex)]
}
