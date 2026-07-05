package com.github.reygnn.rechts.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.github.reygnn.rechts.domain.UrlNormalizer
import com.github.reygnn.rechts.ui.browser.BrowserScreen
import com.github.reygnn.rechts.ui.browser.BrowserViewModel
import com.github.reygnn.rechts.ui.theme.RechtsTheme
import com.github.reygnn.rechts.web.WebViewConfig

class MainActivity : ComponentActivity() {

    // Activity-scoped so onNewIntent and the Composable share one instance.
    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drop any leftover private-browsing data so a private session never
        // outlives the app process. With android:configChanges set, rotation no
        // longer reaches onCreate at all, so this runs only on a genuine cold
        // launch or an OS process-death restore — in both, the private session
        // must start fresh (and BrowserScreen also refuses to restore private
        // history from disk). The normal/default profile — and therefore saved
        // logins — is untouched.
        WebViewConfig.clearPrivateData()
        enableEdgeToEdge()
        setContent {
            RechtsTheme {
                BrowserScreen(viewModel = viewModel, initialUrl = inboundUrl(intent))
            }
        }
    }

    /** A web link or shared text delivered while rechts is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inboundUrl(intent)?.let { viewModel.openExternalUrl(it) }
    }

    /**
     * The URL an inbound intent should open, or null for a normal launch:
     *  - `ACTION_VIEW`: the tapped link verbatim (hard rule 7 — inbound links
     *    load as-is, and the web layer's scheme gate still vets non-http(s)).
     *  - `ACTION_SEND` (`text/plain`): the shared text run through
     *    [UrlNormalizer], so a shared link opens and shared words become a
     *    search — exactly like typed address-bar input. The URL still passes the
     *    scheme gate in [BrowserViewModel]'s load path before it reaches the WebView.
     */
    private fun inboundUrl(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.let { UrlNormalizer.toUrlOrSearch(it) }
        else -> null
    }
}
