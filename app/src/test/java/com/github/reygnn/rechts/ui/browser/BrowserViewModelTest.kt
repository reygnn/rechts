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
    fun `openExternalUrl emits the url verbatim, without normalization`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.loadRequests.test {
                vm.openExternalUrl("https://example.com/from-other-app")
                assertEquals("https://example.com/from-other-app", awaitItem())
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
    fun `onStopLoading settles the chrome to idle`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageStarted("https://example.com", canGoBack = false, canGoForward = false)
        vm.onProgressChanged(40)
        assertTrue(vm.uiState.value.isLoading)

        vm.onStopLoading()
        assertEquals(100, vm.uiState.value.progress)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `a trailing progress tick after stop does not re-show the bar`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageStarted("https://example.com", canGoBack = false, canGoForward = false)
            vm.onStopLoading()
            // stopLoading() is async: a late tick for the stopped page arrives.
            vm.onProgressChanged(70)
            assertEquals(100, vm.uiState.value.progress)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `a new navigation after stop re-enables progress`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageStarted("https://example.com", canGoBack = false, canGoForward = false)
        vm.onStopLoading()
        // The user navigates again (reload / new URL): progress is meaningful.
        vm.onPageStarted("https://other.example", canGoBack = true, canGoForward = false)
        vm.onProgressChanged(30)
        assertEquals(30, vm.uiState.value.progress)
        assertTrue(vm.uiState.value.isLoading)
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
    fun `a trailing onPageFinished after stop keeps the chrome settled`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageStarted("https://example.com", canGoBack = false, canGoForward = false)
            vm.onStopLoading()
            // stopLoading() is async: the stopped page still emits a late
            // onPageFinished for the URL it was navigating to. It must not move
            // the address bar there, but may still update the history flags.
            vm.onPageFinished("https://example.com/never-shown", canGoBack = true, canGoForward = false)
            val state = vm.uiState.value
            assertEquals("https://example.com", state.currentUrl)
            assertEquals("https://example.com", state.addressText)
            assertEquals(100, state.progress)
            assertTrue(state.canGoBack)
        }

    @Test
    fun `onPageFinished resumes normally after a new navigation clears the stop`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageStarted("https://example.com", canGoBack = false, canGoForward = false)
            vm.onStopLoading()
            // A fresh navigation clears the stop flag, so its onPageFinished
            // settles the chrome to the new URL as usual.
            vm.onPageStarted("https://other.example", canGoBack = true, canGoForward = false)
            vm.onPageFinished("https://other.example/done", canGoBack = true, canGoForward = false)
            assertEquals("https://other.example/done", vm.uiState.value.addressText)
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

    @Test
    fun `the blur after a submit keeps the entry, not the old page url`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageFinished("https://old.example", canGoBack = false, canGoForward = false)
            vm.loadRequests.test {
                vm.onAddressFocusChanged(true)
                vm.onAddressChange("new.example")
                vm.onAddressSubmit()
                assertEquals("https://new.example", awaitItem())
                // The UI clears focus right after submit; that blur must NOT
                // restore the previous page's URL (would flash before the load).
                vm.onAddressFocusChanged(false)
                assertEquals("new.example", vm.uiState.value.addressText)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a blank submit does not suppress the next blur restore`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
            vm.onAddressFocusChanged(true)
            vm.onAddressChange("   ")
            vm.onAddressSubmit()                 // blank → no load, no suppression
            vm.onAddressFocusChanged(false)
            assertEquals("https://example.com", vm.uiState.value.addressText)
        }

    @Test
    fun `regaining focus clears a stale submit flag so the next blur restores`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
            vm.loadRequests.test {
                // A submit fires but the expected post-submit blur never arrives
                // (e.g. focus was already elsewhere), leaving submitInFlight set.
                vm.onAddressChange("new.example")
                vm.onAddressSubmit()
                assertEquals("https://new.example", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            // The user later focuses and abandons an edit: the blur must still
            // restore the page URL, not be swallowed by the stale flag.
            vm.onAddressFocusChanged(true)
            vm.onAddressChange("half-typed")
            vm.onAddressFocusChanged(false)
            assertEquals("https://example.com", vm.uiState.value.addressText)
        }

    @Test
    fun `onExternalHandoff restores the address bar to the current page url`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
            // User typed an external scheme and submitted; it was handed off to
            // another app, so no page load re-syncs the bar.
            vm.onAddressChange("mailto:a@b.com")
            vm.onExternalHandoff()
            assertEquals("https://example.com", vm.uiState.value.addressText)
        }

    @Test
    fun `onExternalHandoff leaves the entry alone while the user is editing`() =
        runTest(mainRule.dispatcher) {
            val vm = BrowserViewModel()
            vm.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
            vm.onAddressFocusChanged(true)
            vm.onAddressChange("tel:123")
            vm.onExternalHandoff()
            assertEquals("tel:123", vm.uiState.value.addressText)
        }

    @Test
    fun `onHistoryRestored mirrors url and history flags`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onHistoryRestored("https://restored.example", canGoBack = true, canGoForward = false)
        val state = vm.uiState.value
        assertEquals("https://restored.example", state.currentUrl)
        assertEquals("https://restored.example", state.addressText)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
    }

    @Test
    fun `onHistoryRestored with a null url keeps existing text`() = runTest(mainRule.dispatcher) {
        val vm = BrowserViewModel()
        vm.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
        vm.onHistoryRestored(null, canGoBack = true, canGoForward = true)
        val state = vm.uiState.value
        assertEquals("https://example.com", state.currentUrl)
        assertEquals("https://example.com", state.addressText)
        assertTrue(state.canGoBack)
        assertTrue(state.canGoForward)
    }
}
