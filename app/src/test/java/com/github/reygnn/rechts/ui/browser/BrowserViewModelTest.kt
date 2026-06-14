package com.github.reygnn.rechts.ui.browser

import app.cash.turbine.test
import com.github.reygnn.rechts.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `initial state is empty and idle`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        val state = vm.uiState.value
        assertEquals("", state.addressText)
        assertEquals("", state.currentUrl)
        assertEquals(100, state.progress)
        assertFalse(state.isLoading)
        assertFalse(state.canGoBack)
        assertFalse(state.canGoForward)
    }

    @Test
    fun `onAddressChange updates address text`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onAddressChange("github.com")
        assertEquals("github.com", vm.uiState.value.addressText)
    }

    @Test
    fun `onAddressClear empties address text`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onAddressChange("github.com")
        vm.onAddressClear()
        assertEquals("", vm.uiState.value.addressText)
    }

    @Test
    fun `onAddressSubmit emits normalized url`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.loadRequests.test {
            vm.onAddressChange("example.com")
            vm.onAddressSubmit()
            assertEquals("https://example.com", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onAddressSubmit with a query emits a search url`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.loadRequests.test {
            vm.onAddressChange("kotlin coroutines")
            vm.onAddressSubmit()
            assertEquals("https://duckduckgo.com/?q=kotlin+coroutines", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onAddressSubmit with blank input emits nothing`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.loadRequests.test {
            vm.onAddressChange("   ")
            vm.onAddressSubmit()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a request submitted before the collector attaches is still delivered`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            // Emit first, subscribe later — the buffered Channel must not drop it.
            vm.onAddressChange("example.com")
            vm.onAddressSubmit()
            vm.loadRequests.test {
                assertEquals("https://example.com", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onPageStarted marks loading and mirrors url and history`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageStarted("https://example.com", canGoBack = true, canGoForward = false)
        val state = vm.uiState.value
        assertEquals("https://example.com", state.currentUrl)
        assertEquals("https://example.com", state.addressText)
        assertEquals(0, state.progress)
        assertTrue(state.isLoading)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
    }

    @Test
    fun `onProgressChanged below 100 is loading, at 100 is idle`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onProgressChanged(40)
        assertEquals(40, vm.uiState.value.progress)
        assertTrue(vm.uiState.value.isLoading)

        vm.onProgressChanged(100)
        assertEquals(100, vm.uiState.value.progress)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `onPageFinished settles to loaded state and history`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageStarted("https://example.com", canGoBack = false, canGoForward = false)
        vm.onPageFinished("https://example.com/after-redirect", canGoBack = true, canGoForward = true)
        val state = vm.uiState.value
        assertEquals("https://example.com/after-redirect", state.currentUrl)
        assertEquals(100, state.progress)
        assertFalse(state.isLoading)
        assertTrue(state.canGoBack)
        assertTrue(state.canGoForward)
    }

    @Test
    fun `page events do not clobber the address bar while the user is editing`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onAddressFocusChanged(true)
            vm.onAddressChange("github.c")               // user mid-keystroke
            // A background redirect fires while the user types.
            vm.onPageStarted("https://ads.example/redirect", canGoBack = false, canGoForward = false)
            assertEquals("github.c", vm.uiState.value.addressText)
            // ...but the real page URL is still tracked underneath.
            assertEquals("https://ads.example/redirect", vm.uiState.value.currentUrl)
        }

    @Test
    fun `blurring the address bar restores the current page url`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
        vm.onAddressFocusChanged(true)
        vm.onAddressChange("half-typed")
        vm.onAddressFocusChanged(false)
        assertEquals("https://example.com", vm.uiState.value.addressText)
    }
}
