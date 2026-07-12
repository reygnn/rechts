package com.github.reygnn.rechts.web

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the security-relevant hardening in [ExternalScheme.dispatch]: it builds
 * the external `ACTION_VIEW` / `intent:` intent and is the gate that hands web
 * links off to other apps (CLAUDE.md hard rules 6/7).
 *
 * Robolectric doesn't resolve activities — `startActivity` just records the
 * intent — so we assert on the *built* intent via `ShadowApplication`, not on
 * whether an app actually launched (that stays manual/instrumented).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalSchemeDispatchTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun lastLaunched(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `intent url is stripped of component and selector and forced BROWSABLE`() {
        // A hostile intent: that tries to target an internal component must not
        // be honoured: component/selector cleared, BROWSABLE required, NEW_TASK
        // set so it launches from a non-activity context.
        val url = "intent://evil/#Intent;scheme=foo;" +
            "component=com.victim/.SecretActivity;end"
        assertTrue(ExternalScheme.dispatch(app, url, fromWebContent = true))

        val launched = lastLaunched()!!
        assertNull("component must be cleared", launched.component)
        assertNull("selector must be cleared", launched.selector)
        assertTrue("BROWSABLE must be present", launched.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(
            "NEW_TASK must be set",
            launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `dangerous intent action is neutralised to VIEW`() {
        // A hostile intent: that names an action able to act without further
        // confirmation (auto-dial) must be neutralised — even with BROWSABLE.
        val url = "intent://evil/#Intent;scheme=foo;" +
            "action=android.intent.action.CALL;end"
        assertTrue(ExternalScheme.dispatch(app, url, fromWebContent = true))

        val launched = lastLaunched()!!
        assertEquals("dangerous action must be forced to VIEW", Intent.ACTION_VIEW, launched.action)
        assertTrue("BROWSABLE must still be present", launched.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `legitimate app-switch action is preserved`() {
        // Regression: rechts used to clamp every intent: action to VIEW, which
        // stripped TWINT's custom app-switch action and broke the browser→TWINT
        // hand-off once rechts was the default browser. A non-dangerous action
        // must survive so its app's (BROWSABLE) activity can match on it — while
        // the component/selector are still cleared and BROWSABLE still required.
        val url = "intent://payment/#Intent;scheme=twint;" +
            "action=ch.twint.action.TWINT_PAYMENT;" +
            "component=com.victim/.SecretActivity;end"
        assertTrue(ExternalScheme.dispatch(app, url, fromWebContent = true))

        val launched = lastLaunched()!!
        assertEquals(
            "custom app-switch action must be preserved",
            "ch.twint.action.TWINT_PAYMENT",
            launched.action,
        )
        assertNull("component must still be cleared", launched.component)
        assertNull("selector must still be cleared", launched.selector)
        assertTrue("BROWSABLE must still be present", launched.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `intent url with no action defaults to VIEW`() {
        // A bare intent: (no action=) is a plain view of its data, not a
        // null-action dispatch that would resolve unpredictably.
        val url = "intent://host/path#Intent;scheme=https;end"
        assertTrue(ExternalScheme.dispatch(app, url, fromWebContent = true))

        val launched = lastLaunched()!!
        assertEquals("absent action must default to VIEW", Intent.ACTION_VIEW, launched.action)
    }

    @Test
    fun `intent url is stripped of URI-permission grant flags`() {
        // A hostile intent: must not smuggle in URI-permission grants; rechts
        // exposes none of its data to the target app.
        val url = "intent://evil/#Intent;scheme=foo;" +
            "launchFlags=0x3;end" // 0x1|0x2 = GRANT_READ|GRANT_WRITE
        assertTrue(ExternalScheme.dispatch(app, url, fromWebContent = true))

        val launched = lastLaunched()!!
        val grantMask = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals("grant flags must be cleared", 0, launched.flags and grantMask)
        // The hardening must not have dropped NEW_TASK.
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `web-content view launch requires BROWSABLE`() {
        // A page-initiated launch only matches activities that opted into being
        // triggered from the web.
        assertTrue(ExternalScheme.dispatch(app, "tel:+49123", fromWebContent = true))
        val launched = lastLaunched()!!
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("tel:+49123", launched.dataString)
        assertTrue(launched.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `typed-input view launch keeps the broader match without BROWSABLE`() {
        // Typed input is an explicit user action, so handlers that don't declare
        // BROWSABLE (e.g. some tel:/mailto: apps) still resolve.
        assertTrue(ExternalScheme.dispatch(app, "tel:+49123", fromWebContent = false))
        val launched = lastLaunched()!!
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertFalse(launched.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
