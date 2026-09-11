package com.sleqa.wififix.core

/**
 * All the decision-making, with no Android dependencies and no clock of its own:
 * every event carries its own timestamp, so tests drive it with a fake clock and
 * assert on the emitted [GuardAction]s.
 *
 * The load-bearing rule, and the reason confirmation exists at all:
 *
 *   `WifiManager.setWifiEnabled(true)` returning `true` does NOT mean Wi-Fi is on.
 *
 * Since Android 13, WifiServiceImpl has a branch specifically aimed at legacy
 * targetSdk apps like this one: it posts a user confirmation dialog and returns
 * `true` without touching the radio. So success is defined here as *observing*
 * WIFI_STATE_ENABLED within [GuardConfig.confirmTimeoutMs] -- never as a return
 * value.
 */
class GuardStateMachine(
    private val cfg: GuardConfig = GuardConfig(),
    private val env: GuardEnvironment,
) {
    private val backoff = Backoff(cfg.backoffLadderMs)

    private var phase = Phase.IDLE
    private var wifiState = WifiState.UNKNOWN
    private var debounceUntil = 0L
    private var confirmDeadline = 0L
    private var retryAt = 0L
    private var attemptIndex = 0
    private var escalationLevel = 0
    private var failuresOnTier = 0
    private var snoozeUntil = 0L
    private var enabledSince = 0L
    private var selfTestArmed = false
    private var status = "Starting up"

    /** Timestamps of recent successful repairs, for the auto-snooze heuristic. */
    private val recentRepairs = ArrayDeque<Long>()

    fun snapshot() = GuardSnapshot(
        phase = phase,
        wifiState = wifiState,
        currentTier = selectTier(),
        attemptIndex = attemptIndex,
        snoozeUntil = snoozeUntil,
        status = status,
    )

    /**
     * How soon the caller should deliver the next [GuardEvent.Tick]. Fast while
     * a repair is in flight, lazy when nothing is happening.
     */
    fun nextTickHintMs(): Long =
        if (phase == Phase.IDLE || phase == Phase.GAVE_UP || phase == Phase.SNOOZED) 10_000L else 1_000L

    fun onEvent(event: GuardEvent): List<GuardAction> {
        val out = mutableListOf<GuardAction>()
        when (event) {
            is GuardEvent.Snooze -> {
                snoozeUntil = event.untilMs
                phase = Phase.SNOOZED
                resetCounters()
                out += GuardAction.Record(Kind.SNOOZED, null, "snoozed until ${event.untilMs}")
                out += setStatus("Snoozed - not watching Wi-Fi")
            }

            is GuardEvent.Resume -> {
                snoozeUntil = 0
                phase = Phase.IDLE
                resetCounters()
                recentRepairs.clear()
                out += GuardAction.Record(Kind.RESUMED, null, "watchdog resumed")
                out += setStatus("Watching")
            }

            is GuardEvent.SelfTestArmed -> {
                selfTestArmed = true
                out += GuardAction.Record(Kind.SELF_TEST, null, "self-test armed by user")
            }

            is GuardEvent.WifiChanged -> {
                wifiState = event.state
                advance(event.at, out)
            }

            is GuardEvent.Tick -> advance(event.at, out)

            is GuardEvent.AttemptOutcome -> {
                out += GuardAction.Record(
                    if (event.accepted) Kind.ATTEMPT else Kind.ATTEMPT_REJECTED,
                    event.tier,
                    event.detail,
                )
                if (!event.accepted) {
                    // An outright rejection is conclusive; no point waiting for a
                    // confirmation that cannot come.
                    failAttempt(event.at, out)
                }
                // If accepted we stay in AWAITING_CONFIRM and let the radio prove it.
            }
        }
        return out
    }

    private fun advance(now: Long, out: MutableList<GuardAction>) {
        if (snoozeUntil > 0) {
            if (now < snoozeUntil) return
            snoozeUntil = 0
            phase = Phase.IDLE
            out += GuardAction.Record(Kind.SNOOZE_EXPIRED, null, "snooze expired")
            out += setStatus("Watching")
        }

        // Confirmation timeout is checked independently of the current radio
        // state so a radio stuck in ENABLING still times out.
        if (phase == Phase.AWAITING_CONFIRM && wifiState != WifiState.ENABLED && now >= confirmDeadline) {
            out += GuardAction.Record(
                Kind.TIMEOUT,
                currentTier(),
                "no WIFI_STATE_ENABLED within ${cfg.confirmTimeoutMs}ms (call may have posted a dialog)",
            )
            failAttempt(now, out)
            return
        }

        when (wifiState) {
            WifiState.ENABLED -> onEnabled(now, out)
            // A radio on its way up or down is transient. Never act on it.
            WifiState.ENABLING, WifiState.DISABLING -> Unit
            else -> onDisabled(now, out)
        }
    }

    private fun onEnabled(now: Long, out: MutableList<GuardAction>) {
        if (enabledSince == 0L) enabledSince = now

        if (phase == Phase.AWAITING_CONFIRM) {
            val tier = currentTier()
            out += GuardAction.Record(Kind.CONFIRMED, tier, "radio confirmed back on")
            out += setStatus("Recovered via ${tier?.label ?: "?"}")
            phase = Phase.IDLE
            resetCounters()
            if (registerRepair(now)) {
                snoozeUntil = now + cfg.autoSnoozeDurationMs
                phase = Phase.SNOOZED
                out += GuardAction.Record(
                    Kind.AUTO_SNOOZE,
                    null,
                    "re-enabled ${cfg.autoSnoozeAfterRepairs}x in ${cfg.autoSnoozeWindowMs / 1000}s - " +
                        "backing off for ${cfg.autoSnoozeDurationMs / 60_000} min in case you meant to turn it off",
                )
                out += setStatus("Paused ${cfg.autoSnoozeDurationMs / 60_000} min - Wi-Fi kept going off")
            }
            return
        }

        if (phase != Phase.IDLE) {
            phase = Phase.IDLE
            out += setStatus("Watching")
        }

        // Only a sustained good spell clears the counters; otherwise a flapping
        // radio would reset the backoff on every blip and defeat it.
        if (attemptIndex != 0 && now - enabledSince >= cfg.stableResetMs) {
            resetCounters()
            out += GuardAction.Record(Kind.STABLE, null, "Wi-Fi stable, counters reset")
        }
    }

    private fun onDisabled(now: Long, out: MutableList<GuardAction>) {
        enabledSince = 0
        when (phase) {
            Phase.IDLE -> {
                phase = Phase.DEBOUNCING
                debounceUntil = now + cfg.debounceMs
                out += GuardAction.Record(
                    Kind.WIFI_OFF,
                    null,
                    if (selfTestArmed) "Wi-Fi off (self-test)" else "Wi-Fi switched off",
                )
                out += setStatus("Wi-Fi off - confirming")
            }

            Phase.DEBOUNCING -> if (now >= debounceUntil) tryAttempt(now, out)
            Phase.BACKING_OFF -> if (now >= retryAt) tryAttempt(now, out)
            Phase.AWAITING_CONFIRM, Phase.GAVE_UP, Phase.SNOOZED -> Unit
        }
    }

    private fun tryAttempt(now: Long, out: MutableList<GuardAction>) {
        // The framework rejects setWifiEnabled outright in airplane mode, so
        // retrying would just burn the attempt budget.
        if (env.isAirplaneModeOn()) {
            phase = Phase.BACKING_OFF
            retryAt = now + 60_000
            out += GuardAction.Record(Kind.BLOCKED, null, "airplane mode on - not touching Wi-Fi")
            out += setStatus("Paused - airplane mode is on")
            return
        }

        if (attemptIndex >= cfg.maxConsecutiveAttempts) {
            giveUp(now, out, "reached ${cfg.maxConsecutiveAttempts} attempts")
            return
        }

        val tier = selectTier()
        if (tier == null) {
            out += GuardAction.Record(Kind.NO_TIER, null, "no repair tier is available on this device")
            giveUp(now, out, "no usable repair tier")
            return
        }

        attemptIndex++
        phase = Phase.AWAITING_CONFIRM
        confirmDeadline = now + cfg.confirmTimeoutMs
        out += GuardAction.Attempt(tier)
        out += setStatus("Turning Wi-Fi back on (attempt $attemptIndex, ${tier.label})")
    }

    private fun failAttempt(now: Long, out: MutableList<GuardAction>) {
        failuresOnTier++
        if (failuresOnTier >= cfg.failuresBeforeTierEscalation) {
            failuresOnTier = 0
            escalationLevel++
        }
        if (attemptIndex >= cfg.maxConsecutiveAttempts) {
            giveUp(now, out, "reached ${cfg.maxConsecutiveAttempts} attempts")
            return
        }
        val delay = backoff.delayFor(attemptIndex)
        phase = Phase.BACKING_OFF
        retryAt = now + delay
        out += setStatus("Retrying in ${delay / 1000}s")
    }

    private fun giveUp(now: Long, out: MutableList<GuardAction>, why: String) {
        phase = Phase.GAVE_UP
        out += GuardAction.Record(Kind.GAVE_UP, Tier.HUMAN, why)
        out += setStatus("Could not turn Wi-Fi back on - needs a human")
    }

    /**
     * The tier to use now: escalation walks along the tiers this device can
     * actually use and then *stays* on the last one. It must not run off the
     * end of the ladder -- on a typical device only DIRECT is available, and
     * exhausting the ladder would mean giving up after two attempts instead of
     * working through the full backoff schedule.
     */
    private fun selectTier(): Tier? {
        val available = Tier.REPAIR_LADDER.filter { env.isTierAvailable(it) }
        if (available.isEmpty()) return null
        return available[escalationLevel.coerceAtMost(available.lastIndex)]
    }

    private fun currentTier(): Tier? = selectTier()

    /** Returns true when the repair rate crosses the auto-snooze threshold. */
    private fun registerRepair(now: Long): Boolean {
        if (selfTestArmed) {
            // A repair we provoked ourselves must not count towards the heuristic.
            selfTestArmed = false
            return false
        }
        recentRepairs.addLast(now)
        while (recentRepairs.isNotEmpty() && now - recentRepairs.first() > cfg.autoSnoozeWindowMs) {
            recentRepairs.removeFirst()
        }
        if (recentRepairs.size >= cfg.autoSnoozeAfterRepairs) {
            recentRepairs.clear()
            return true
        }
        return false
    }

    private fun resetCounters() {
        attemptIndex = 0
        escalationLevel = 0
        failuresOnTier = 0
    }

    private fun setStatus(text: String): GuardAction.Status {
        status = text
        return GuardAction.Status(text)
    }
}
