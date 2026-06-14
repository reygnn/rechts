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
        // On a genuine cold launch, drop any leftover private-browsing data so a
        // private session never outlives the app. The normal/default profile —
        // and therefore saved logins — is untouched. A configuration-change
        // recreation has a non-null savedInstanceState, so rotation keeps the
        // current private session intact.
        if (savedInstanceState == null) {
            WebViewConfig.clearPrivateData()
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
