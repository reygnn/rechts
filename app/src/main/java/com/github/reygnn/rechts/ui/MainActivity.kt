package com.github.reygnn.rechts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.reygnn.rechts.ui.browser.BrowserScreen
import com.github.reygnn.rechts.ui.browser.BrowserViewModel
import com.github.reygnn.rechts.ui.theme.RechtsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RechtsTheme {
                val viewModel: BrowserViewModel = viewModel()
                BrowserScreen(viewModel = viewModel)
            }
        }
    }
}
