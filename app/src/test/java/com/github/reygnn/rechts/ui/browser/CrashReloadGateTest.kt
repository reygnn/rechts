package com.github.reygnn.rechts.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReloadGateTest {

    @Test
    fun `a single crash is reloaded`() {
        val gate = CrashReloadGate(windowMs = 10_000L)
        assertTrue(gate.shouldReload(1_000L))
    }

    @Test
    fun `a second crash within the window is not reloaded`() {
        val gate = CrashReloadGate(windowMs = 10_000L)
        assertTrue(gate.shouldReload(1_000L))
        assertFalse(gate.shouldReload(5_000L))
    }

    @Test
    fun `a crash after the window has elapsed is reloaded again`() {
        val gate = CrashReloadGate(windowMs = 10_000L)
        assertTrue(gate.shouldReload(1_000L))
        // 10s later the earlier crash has aged out of the window.
        assertTrue(gate.shouldReload(11_000L))
    }

    @Test
    fun `a tight crash loop keeps refusing to reload`() {
        val gate = CrashReloadGate(windowMs = 10_000L)
        assertTrue(gate.shouldReload(0L))
        assertFalse(gate.shouldReload(100L))
        assertFalse(gate.shouldReload(200L))
        assertFalse(gate.shouldReload(300L))
    }

    @Test
    fun `the window boundary is exclusive of the aged-out crash`() {
        val gate = CrashReloadGate(windowMs = 10_000L)
        assertTrue(gate.shouldReload(1_000L))
        // Exactly windowMs after the first crash: the first has aged out, so this
        // one is the only crash in the window and is reloaded.
        assertTrue(gate.shouldReload(11_000L))
    }

    @Test
    fun `a higher reload budget allows more reloads before giving up`() {
        val gate = CrashReloadGate(windowMs = 10_000L, maxReloadsInWindow = 2)
        assertTrue(gate.shouldReload(0L))
        assertTrue(gate.shouldReload(100L))
        assertFalse(gate.shouldReload(200L))
    }
}
