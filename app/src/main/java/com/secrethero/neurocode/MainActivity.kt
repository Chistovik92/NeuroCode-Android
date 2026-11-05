package com.secrethero.neurocode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.secrethero.neurocode.model.ThemeMode
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.secrethero.neurocode.ui.AppViewModel
import com.secrethero.neurocode.ui.NeuroCodeApp
import com.secrethero.neurocode.ui.NeuroCodeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSharedModel()
        setContent {
            val windowSize = calculateWindowSizeClass(this)
            val viewModel: AppViewModel = viewModel()
            val settings by viewModel.settingsScreen.settings
                .collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }
            // Значки статус-бара и навигации должны читаться на фоне темы приложения,
            // а не системной темы телефона.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            NeuroCodeTheme(
                themeMode = settings.themeMode,
                appDesign = settings.appDesign,
                dynamicColor = settings.dynamicColor,
            ) {
                NeuroCodeApp(
                    viewModel = viewModel,
                    expanded = windowSize.widthSizeClass >= WindowWidthSizeClass.Medium,
                )
            }
        }
    }

    /**
     * «Поделиться моделью» из LocalDoc AI (ACTION_SEND с content://-URI GGUF):
     * импортируем как обычную модель через LocalLlamaClient.importModel.
     */
    private fun handleSharedModel() {
        val uri = if (intent?.action == android.content.Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        } else {
            intent?.data
        } as? android.net.Uri ?: return
        val app = application as NeuroCodeApplication
        app.container.scope.launch {
            runCatching { app.container.localLlama.importModel(uri) }
                .onSuccess {
                    app.container.bus.showNotice(
                        getString(R.string.notice_model_imported_format, it.name),
                    )
                }
                .onFailure(app.container.bus::showError)
        }
    }
}
