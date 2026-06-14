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
        assertEquals(0, state.progress)
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
    fun `onPageStarted marks loading and mirrors url into address bar`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageStarted("https://example.com")
        val state = vm.uiState.value
        assertEquals("https://example.com", state.currentUrl)
        assertEquals("https://example.com", state.addressText)
        assertEquals(0, state.progress)
        assertTrue(state.isLoading)
    }

    @Test
    fun `onProgressChanged below 100 keeps loading`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onProgressChanged(40)
        assertEquals(40, vm.uiState.value.progress)
        assertTrue(vm.uiState.value.isLoading)
    }

    @Test
    fun `onProgressChanged at 100 clears loading`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onProgressChanged(100)
        assertEquals(100, vm.uiState.value.progress)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `onPageFinished settles to loaded state`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageStarted("https://example.com")
        vm.onPageFinished("https://example.com/after-redirect")
        val state = vm.uiState.value
        assertEquals("https://example.com/after-redirect", state.currentUrl)
        assertEquals(100, state.progress)
        assertFalse(state.isLoading)
    }

    @Test
    fun `onNavStateChanged updates history flags`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onNavStateChanged(canGoBack = true, canGoForward = false)
        assertTrue(vm.uiState.value.canGoBack)
        assertFalse(vm.uiState.value.canGoForward)
    }
}
