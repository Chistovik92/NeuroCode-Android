package com.secrethero.neurocode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.secrethero.neurocode.ui.AppViewModel
import com.secrethero.neurocode.ui.NeuroCodeApp
import com.secrethero.neurocode.ui.NeuroCodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeuroCodeTheme {
                val viewModel: AppViewModel = viewModel()
                NeuroCodeApp(viewModel)
            }
        }
    }
}
