package com.sleqa.wififix

import com.sleqa.wififix.core.GuardAction
import com.sleqa.wififix.core.GuardConfig
import com.sleqa.wififix.core.GuardEnvironment
import com.sleqa.wififix.core.GuardEvent
import com.sleqa.wififix.core.GuardStateMachine
import com.sleqa.wififix.core.Kind
import com.sleqa.wififix.core.Phase
import com.sleqa.wififix.core.Tier
import com.sleqa.wififix.core.WifiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEnv(
    var airplane: Boolean = false,
    var available: MutableSet<Tier> = mutableSetOf(Tier.DIRECT),
) : GuardEnvironment {
    override fun isAirplaneModeOn() = airplane
    override fun isTierAvailable(tier: Tier) = tier in available
}

/**
 * Drives the machine with an explicit clock and collects emitted actions, so
 * every timing rule is asserted without sleeping.
 */
private class Driver(
    val env: FakeEnv = FakeEnv(),
    cfg: GuardConfig = GuardConfig(),
) {
    val machine = GuardStateMachine(cfg, env)
    var now = 1_000L
    val actions = mutableListOf<GuardAction>()

    fun advanceTo(t: Long) { now = t }
    fun elapse(ms: Long) { now += ms }

    fun wifi(state: Int) = fire(GuardEvent.WifiChanged(state, now))
    fun tick() = fire(GuardEvent.Tick(now))
    fun outcome(tier: Tier, accepted: Boolean) =
        fire(GuardEvent.AttemptOutcome(tier, accepted, "test", now))

    fun fire(e: GuardEvent): List<GuardAction> =
        machine.onEvent(e).also { actions += it }

    fun attempts() = actions.filterIsInstance<GuardAction.Attempt>()
    fun records() = actions.filterIsInstance<GuardAction.Record>()
    fun kinds() = records().map { it.kind }
    fun clear() = actions.clear()

    /** Runs the loop the service runs: tick, then report the attempt as failed. */
    fun failNextAttempt(tier: Tier = Tier.DIRECT) {
        tick()
        outcome(tier, accepted = false)
    }
}

class GuardStateMachineTest {

    @Test
    fun `disabling alone never triggers an attempt`() {
        val d = Driver()
        d.wifi(WifiState.DISABLING)
        d.elapse(60_000)
        d.tick()
        assertTrue("DISABLING is transient and must be ignored", d.attempts().isEmpty())
    }

    @Test
    fun `attempt waits for the debounce window`() {
        val d = Driver()
        d.wifi(WifiState.DISABLED)
        assertTrue("must not act on the first sighting", d.attempts().isEmpty())

        d.elapse(2_000)
        d.tick()
        assertTrue("still inside the 3s debounce", d.attempts().isEmpty())

        d.elapse(1_500)
        d.tick()
        assertEquals(1, d.attempts().size)
        assertEquals(Tier.DIRECT, d.attempts().first().tier)
    }

    @Test
    fun `wifi recovering on its own inside the debounce cancels the repair`() {
        val d = Driver()
        d.wifi(WifiState.DISABLED)
        d.elapse(1_000)
        d.wifi(WifiState.ENABLED)
        d.elapse(30_000)
        d.tick()
        assertTrue("a self-healing blip must not be touched", d.attempts().isEmpty())
    }

    /**
     * The regression test that matters most. Android 13+ can post a confirmation
     * dialog and return true without enabling anything, so an accepted call that
     * is never confirmed by the radio must still be treated as a failure.
     */
    @Test
    fun `accepted call that never reaches ENABLED is retried`() {
        val d = Driver()
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.tick()
        d.outcome(Tier.DIRECT, accepted = true)
        d.clear()

        d.elapse(14_000); d.tick()
        assertTrue("must still be waiting for the radio", d.attempts().isEmpty())

        d.elapse(2_000); d.tick()
        assertTrue("confirmation timeout must be logged", Kind.TIMEOUT in d.kinds())

        // First retry rung is 5s.
        d.elapse(4_000); d.tick()
        assertTrue(d.attempts().isEmpty())
        d.elapse(2_000); d.tick()
        assertEquals(1, d.attempts().size)
    }

    @Test
    fun `success is recorded only when the radio confirms`() {
        val d = Driver()
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.tick()
        d.outcome(Tier.DIRECT, accepted = true)
        d.clear()

        d.elapse(4_000)
        d.wifi(WifiState.ENABLED)
        assertTrue(Kind.CONFIRMED in d.kinds())
        assertEquals(Phase.IDLE, d.machine.snapshot().phase)
    }

    @Test
    fun `escalates to the next available tier after two failures`() {
        val d = Driver(FakeEnv(available = mutableSetOf(Tier.DIRECT, Tier.DEVICE_OWNER)))
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.failNextAttempt()
        d.elapse(6_000); d.failNextAttempt()
        d.clear()

        d.elapse(16_000); d.tick()
        assertEquals(Tier.DEVICE_OWNER, d.attempts().single().tier)
    }

    @Test
    fun `single available tier is reused instead of exhausting the ladder`() {
        val d = Driver(FakeEnv(available = mutableSetOf(Tier.DIRECT)))
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.failNextAttempt()
        d.elapse(6_000); d.failNextAttempt()
        d.clear()

        d.elapse(16_000); d.tick()
        assertEquals(
            "with only one tier we must keep retrying it, not give up",
            Tier.DIRECT, d.attempts().single().tier,
        )
        assertTrue(Kind.GAVE_UP !in d.kinds())
    }

    @Test
    fun `gives up exactly once after the attempt budget is spent`() {
        val cfg = GuardConfig(backoffLadderMs = listOf(0, 1_000))
        val d = Driver(cfg = cfg)
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500)
        repeat(cfg.maxConsecutiveAttempts) {
            d.failNextAttempt()
            d.elapse(2_000)
        }
        assertEquals(cfg.maxConsecutiveAttempts, d.attempts().size)
        assertEquals(1, d.kinds().count { it == Kind.GAVE_UP })

        d.clear()
        d.elapse(600_000); d.tick()
        assertTrue("GAVE_UP is terminal until Wi-Fi returns", d.attempts().isEmpty())
    }

    @Test
    fun `counters reset only after a sustained good spell`() {
        val d = Driver()
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.failNextAttempt()

        d.wifi(WifiState.ENABLED)
        d.elapse(59_000); d.tick()
        assertTrue("59s is not yet stable", Kind.STABLE !in d.kinds())

        d.elapse(2_000); d.tick()
        assertTrue(Kind.STABLE in d.kinds())
        assertEquals(0, d.machine.snapshot().attemptIndex)
    }

    @Test
    fun `snoozed watchdog does nothing`() {
        val d = Driver()
        d.fire(GuardEvent.Snooze(d.now + 30 * 60_000, d.now))
        d.clear()
        d.wifi(WifiState.DISABLED)
        d.elapse(120_000); d.tick()
        assertTrue(d.attempts().isEmpty())
    }

    @Test
    fun `snooze expiry resumes watching`() {
        val d = Driver()
        d.fire(GuardEvent.Snooze(d.now + 60_000, d.now))
        d.wifi(WifiState.DISABLED)
        d.elapse(61_000); d.tick()
        d.elapse(4_000); d.tick()
        assertTrue(Kind.SNOOZE_EXPIRED in d.kinds())
        assertEquals(1, d.attempts().size)
    }

    @Test
    fun `airplane mode blocks attempts instead of burning the budget`() {
        val d = Driver(FakeEnv(airplane = true))
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.tick()
        assertTrue(d.attempts().isEmpty())
        assertTrue(Kind.BLOCKED in d.kinds())
        assertTrue(Kind.GAVE_UP !in d.kinds())
    }

    @Test
    fun `no usable tier escalates straight to a human`() {
        val d = Driver(FakeEnv(available = mutableSetOf()))
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.tick()
        assertNull(d.machine.snapshot().currentTier)
        assertTrue(Kind.NO_TIER in d.kinds())
        assertTrue(Kind.GAVE_UP in d.kinds())
    }

    @Test
    fun `repeated repairs trigger an auto-snooze`() {
        val d = Driver()
        repeat(3) {
            d.wifi(WifiState.DISABLED)
            d.elapse(3_500); d.tick()
            d.outcome(Tier.DIRECT, accepted = true)
            d.elapse(2_000)
            d.wifi(WifiState.ENABLED)
            d.elapse(2_000)
        }
        assertTrue("three repairs inside two minutes means back off", Kind.AUTO_SNOOZE in d.kinds())
        assertEquals(Phase.SNOOZED, d.machine.snapshot().phase)
    }

    @Test
    fun `a self-test repair does not count towards the auto-snooze`() {
        val d = Driver()
        d.fire(GuardEvent.SelfTestArmed(d.now))
        d.wifi(WifiState.DISABLED)
        d.elapse(3_500); d.tick()
        d.outcome(Tier.DIRECT, accepted = true)
        d.elapse(2_000); d.wifi(WifiState.ENABLED)

        repeat(2) {
            d.elapse(2_000)
            d.wifi(WifiState.DISABLED)
            d.elapse(3_500); d.tick()
            d.outcome(Tier.DIRECT, accepted = true)
            d.elapse(2_000); d.wifi(WifiState.ENABLED)
        }
        assertTrue("only two real repairs happened", Kind.AUTO_SNOOZE !in d.kinds())
    }

    @Test
    fun `tick cadence speeds up while a repair is in flight`() {
        val d = Driver()
        assertEquals(10_000L, d.machine.nextTickHintMs())
        d.wifi(WifiState.DISABLED)
        assertEquals(1_000L, d.machine.nextTickHintMs())
    }
}
