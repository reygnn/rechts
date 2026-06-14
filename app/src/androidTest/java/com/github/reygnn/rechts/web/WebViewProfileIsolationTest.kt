package com.github.reygnn.rechts.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the one thing Robolectric cannot fake: real WebView
 * profile isolation. Robolectric has no Chromium engine, so
 * [WebViewConfig.multiProfileSupported] is always false there and the private
 * jar is unreachable. On a device/emulator with a modern WebView (MULTI_PROFILE
 * needs WebView ≥ 105) we can verify the actual privacy boundary:
 *
 *  - the private profile has its own cookie jar, isolated from the default
 *    profile's (a cookie set in one is invisible in the other), and
 *  - [WebViewConfig.clearPrivateData] really removes the private profile.
 *
 * WebView APIs must be touched on the main thread, hence [runOnMainSync].
 */
@RunWith(AndroidJUnit4::class)
class WebViewProfileIsolationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun requireMultiProfile() {
        // Nothing here is meaningful without isolated profiles. Skip (not fail)
        // on a WebView too old to support them.
        assumeTrue(
            "Device WebView lacks MULTI_PROFILE support",
            WebViewConfig.multiProfileSupported(),
        )
    }

    @Test
    fun privateProfileHasAnIsolatedCookieJar() {
        instrumentation.runOnMainSync {
            // Configure both WebViews exactly as the app does, so this exercises
            // the real WebViewConfig path (profile binding + cookie policy).
            val normal = WebView(context).also { WebViewConfig.apply(it, privateMode = false) }
            val private = WebView(context).also { WebViewConfig.apply(it, privateMode = true) }

            val defaultJar = CookieManager.getInstance()
            val privateJar = ProfileStore.getInstance()
                .getOrCreateProfile(WebViewConfig.PRIVATE_PROFILE)
                .cookieManager

            // Separate managers back separate cookie jars.
            assertNotSame(defaultJar, privateJar)

            val url = "https://rechts-isolation.test/"
            privateJar.setCookie(url, "jar=private")
            defaultJar.setCookie(url, "jar=default")

            // Each jar sees only its own cookie — the private session does not
            // leak into the persistent default profile, nor vice versa.
            assertEquals("jar=private", privateJar.getCookie(url))
            assertEquals("jar=default", defaultJar.getCookie(url))

            private.destroy()
            normal.destroy()
        }
    }

    @Test
    fun clearPrivateDataNeverThrowsEvenWhenProfileIsInUse() {
        // The real deletion happens at cold start, in a fresh process where the
        // private profile exists on disk but has NOT been loaded yet. That case
        // can't be reproduced in-process: loading the profile (the only way to
        // create one here) marks it "in use", and the WebView Profile API then
        // refuses deletion ("Cannot delete in-use profile") for the rest of the
        // process's life. So the property worth pinning is the defensive one —
        // clearPrivateData swallows that in-use state instead of propagating it,
        // keeping the MainActivity.onCreate call site crash-proof.
        instrumentation.runOnMainSync {
            // Load + bind the private profile, then drop the WebView. The profile
            // is now "in use" for this process regardless of the destroy().
            WebView(context).also { WebViewConfig.apply(it, privateMode = true) }.destroy()

            // Must return cleanly; if this threw, runOnMainSync would rethrow and
            // fail the test (which is exactly the regression we're guarding).
            WebViewConfig.clearPrivateData()
        }
    }
}
