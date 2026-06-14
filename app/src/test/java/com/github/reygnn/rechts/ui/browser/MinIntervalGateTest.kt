package com.github.reygnn.rechts.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinIntervalGateTest {

    @Test
    fun `first event is always accepted`() {
        val gate = MinIntervalGate(3_000L)
        // Even a "now" smaller than the interval (e.g. just after boot) passes.
        assertTrue(gate.accept(100L))
    }

    @Test
    fun `events within the interval are rejected`() {
        val gate = MinIntervalGate(3_000L)
        assertTrue(gate.accept(10_000L))
        assertFalse(gate.accept(10_001L))
        assertFalse(gate.accept(12_999L))
    }

    @Test
    fun `event exactly at the interval boundary is accepted`() {
        val gate = MinIntervalGate(3_000L)
        assertTrue(gate.accept(10_000L))
        assertTrue(gate.accept(13_000L))
    }

    @Test
    fun `accepting advances the reference point`() {
        val gate = MinIntervalGate(3_000L)
        assertTrue(gate.accept(10_000L))   // accepted → ref = 10_000
        assertFalse(gate.accept(12_000L))  // within 3s of 10_000 → rejected
        assertTrue(gate.accept(13_000L))   // 3s after 10_000 → accepted, ref = 13_000
        assertFalse(gate.accept(15_000L))  // within 3s of 13_000 → rejected
    }

    @Test
    fun `a flood collapses to a single accepted event`() {
        val gate = MinIntervalGate(3_000L)
        val accepted = (0 until 6).count { gate.accept(20_000L + it) }
        assertTrue("expected exactly one accept in a tight burst", accepted == 1)
    }
}
