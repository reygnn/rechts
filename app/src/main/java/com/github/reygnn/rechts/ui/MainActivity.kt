package com.github.reygnn.rechts.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.github.reygnn.rechts.ui.browser.BrowserScreen
import com.github.reygnn.rechts.ui.browser.BrowserViewModel
import com.github.reygnn.rechts.ui.theme.RechtsTheme
import com.github.reygnn.rechts.web.WebViewConfig

class MainActivity : ComponentActivity() {

    // Activity-scoped so onNewIntent and the Composable share one instance.
    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On a genuine cold launch, drop any leftover private-browsing data so a
        // private session never outlives the app. The normal/default profile —
        // and therefore saved logins — is untouched. With android:configChanges
        // set, rotation no longer calls onCreate at all.
        if (savedInstanceState == null) {
            WebViewConfig.clearPrivateData()
        }
        enableEdgeToEdge()
        setContent {
            RechtsTheme {
                BrowserScreen(viewModel = viewModel, initialUrl = viewIntentUrl(intent))
            }
        }
    }

    /** A web link tapped while rechts is already running (singleTask reuse). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewIntentUrl(intent)?.let { viewModel.openExternalUrl(it) }
    }

    /** The URL of an inbound ACTION_VIEW intent, or null for a normal launch. */
    private fun viewIntentUrl(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
}
