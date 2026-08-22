package com.secrethero.neurocode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.secrethero.neurocode.ui.AppViewModel
import com.secrethero.neurocode.ui.NeuroCodeApp
import com.secrethero.neurocode.ui.NeuroCodeTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSize = calculateWindowSizeClass(this)
            val viewModel: AppViewModel = viewModel()
            val settings by viewModel.settingsScreen.settings
                .collectAsStateWithLifecycle()
            NeuroCodeTheme(themeMode = settings.themeMode) {
                NeuroCodeApp(
                    viewModel = viewModel,
                    expanded = windowSize.widthSizeClass >= WindowWidthSizeClass.Medium,
                )
            }
        }
    }
}
