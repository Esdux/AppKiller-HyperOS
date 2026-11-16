package com.yg.appkiller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yg.appkiller.ui.app.AppContent
import com.yg.appkiller.ui.app.AppState
import com.yg.appkiller.ui.app.AppTheme
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.app.LocalUpdateAppState
import com.yg.appkiller.ui.utils.BackgroundManager
import com.yg.appkiller.ui.utils.MiuixBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { AppRepo.syncSystemList(ctx) }
            runCatching { BackgroundManager.init(ctx) }
        }
    }
    var appState by remember { mutableStateOf(AppState()) }
    AppTheme(
        uiStyle = appState.uiStyle,
        colorMode = appState.colorMode,
        paletteStyle = appState.themePaletteStyle,
        colorSpec = appState.themeColorSpec,
    ) {
        CompositionLocalProvider(
            LocalAppState provides appState,
            LocalUpdateAppState provides { transform -> appState = transform(appState) },
        ) {
            MiuixBackground {
                AppContent()
            }
        }
    }
}