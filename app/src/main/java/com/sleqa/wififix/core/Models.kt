package com.sleqa.wififix.core

/**
 * Mirrors [android.net.wifi.WifiManager]'s WIFI_STATE_* constants so that the
 * decision-making core carries no Android imports and stays JVM-unit-testable.
 */
object WifiState {
    const val DISABLING = 0
    const val DISABLED = 1
    const val ENABLING = 2
    const val ENABLED = 3
    const val UNKNOWN = 4

    fun name(state: Int): String = when (state) {
        DISABLING -> "DISABLING"
        DISABLED -> "DISABLED"
        ENABLING -> "ENABLING"
        ENABLED -> "ENABLED"
        else -> "UNKNOWN"
    }
}

/** Ways of turning the radio back on, most preferred first. */
enum class Tier(val label: String) {
    DIRECT("WifiManager (targetSdk 28)"),
    DEVICE_OWNER("Device Owner"),
    HUMAN("Ask a human");

    companion object {
        /** Tiers that can actually flip the radio. HUMAN is escalation, not repair. */
        val REPAIR_LADDER = listOf(DIRECT, DEVICE_OWNER)
    }
}

/** Event-log record kinds. Kept as constants so the UI and tests agree on spelling. */
object Kind {
    const val WIFI_OFF = "WIFI_OFF"
    const val CONFIRMED = "CONFIRMED"
    const val ATTEMPT = "ATTEMPT"
    const val ATTEMPT_REJECTED = "ATTEMPT_REJECTED"
    const val TIMEOUT = "TIMEOUT"
    const val GAVE_UP = "GAVE_UP"
    const val AUTO_SNOOZE = "AUTO_SNOOZE"
    const val SNOOZED = "SNOOZED"
    const val SNOOZE_EXPIRED = "SNOOZE_EXPIRED"
    const val RESUMED = "RESUMED"
    const val STABLE = "STABLE"
    const val BLOCKED = "BLOCKED"
    const val NO_TIER = "NO_TIER"
    const val SELF_TEST = "SELF_TEST"
    const val SERVICE_START = "SERVICE_START"
    const val PROBE = "PROBE"
}

sealed interface GuardEvent {
    val at: Long

    /** The radio reported a new state, from a broadcast or from the poll loop. */
    data class WifiChanged(val state: Int, override val at: Long) : GuardEvent

    /**
     * A strategy finished its call. [accepted] means the call did not obviously
     * fail -- it is NEVER proof the radio is on. See GuardStateMachine.
     */
    data class AttemptOutcome(
        val tier: Tier,
        val accepted: Boolean,
        val detail: String,
        override val at: Long,
    ) : GuardEvent

    /** Periodic nudge so time-based transitions (debounce, backoff, timeout) can fire. */
    data class Tick(override val at: Long) : GuardEvent

    data class Snooze(val untilMs: Long, override val at: Long) : GuardEvent
    data class Resume(override val at: Long) : GuardEvent

    /** The user pressed "Test now"; the next outage is ours, not the bug's. */
    data class SelfTestArmed(override val at: Long) : GuardEvent
}

sealed interface GuardAction {
    /** Run this tier's strategy now. */
    data class Attempt(val tier: Tier) : GuardAction

    /** Append to the event log (and logcat). */
    data class Record(val kind: String, val tier: Tier?, val detail: String) : GuardAction

    /** One-line human-readable state for the notification and the status screen. */
    data class Status(val text: String) : GuardAction
}

enum class Phase { IDLE, DEBOUNCING, AWAITING_CONFIRM, BACKING_OFF, SNOOZED, GAVE_UP }

data class GuardConfig(
    val debounceMs: Long = 3_000,
    val confirmTimeoutMs: Long = 15_000,
    val stableResetMs: Long = 60_000,
    val maxConsecutiveAttempts: Int = 8,
    val failuresBeforeTierEscalation: Int = 2,
    /** Attempt 0 is immediate: the common case is a one-shot glitch. */
    val backoffLadderMs: List<Long> =
        listOf(0, 5_000, 15_000, 45_000, 120_000, 300_000, 600_000, 900_000),
    /** If we repair this many times inside [autoSnoozeWindowMs], back off. */
    val autoSnoozeAfterRepairs: Int = 3,
    val autoSnoozeWindowMs: Long = 120_000,
    val autoSnoozeDurationMs: Long = 600_000,
)

/** Facts the machine needs but must not fetch itself, so tests can fake them. */
interface GuardEnvironment {
    fun isAirplaneModeOn(): Boolean
    fun isTierAvailable(tier: Tier): Boolean
}

data class GuardSnapshot(
    val phase: Phase,
    val wifiState: Int,
    val currentTier: Tier?,
    val attemptIndex: Int,
    val snoozeUntil: Long,
    val status: String,
)
