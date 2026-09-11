package com.sleqa.wififix

import com.sleqa.wififix.core.Backoff
import com.sleqa.wififix.core.GuardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private val ladder = GuardConfig().backoffLadderMs
    private val backoff = Backoff(ladder)

    @Test
    fun `first retry is immediate`() {
        assertEquals(0L, backoff.delayFor(0))
    }

    @Test
    fun `clamps at the last rung instead of overflowing`() {
        val last = ladder.last()
        assertEquals(last, backoff.delayFor(ladder.size))
        assertEquals(last, backoff.delayFor(10_000))
    }

    @Test
    fun `negative input is treated as the first attempt`() {
        assertEquals(ladder.first(), backoff.delayFor(-1))
    }

    @Test
    fun `ladder never decreases`() {
        ladder.zipWithNext { a, b -> assertTrue("ladder must not decrease: $a then $b", b >= a) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty ladder is rejected`() {
        Backoff(emptyList())
    }
}
