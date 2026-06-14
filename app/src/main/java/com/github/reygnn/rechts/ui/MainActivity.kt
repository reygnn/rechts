package com.github.reygnn.rechts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.reygnn.rechts.ui.browser.BrowserScreen
import com.github.reygnn.rechts.ui.browser.BrowserViewModel
import com.github.reygnn.rechts.ui.theme.RechtsTheme
import com.github.reygnn.rechts.web.WebViewConfig

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Private by default: a genuinely fresh launch starts with no leftovers
        // from a previous session (covers the case where the app was killed
        // before onDispose could wipe). A configuration-change recreation has a
        // non-null savedInstanceState, so rotation does NOT clear the session.
        if (savedInstanceState == null) {
            WebViewConfig.clearCookiesAndStorage()
        }
        enableEdgeToEdge()
        setContent {
            RechtsTheme {
                val viewModel: BrowserViewModel = viewModel()
                BrowserScreen(viewModel = viewModel)
            }
        }
    }
}
