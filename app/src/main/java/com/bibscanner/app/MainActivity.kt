package com.bibscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bibscanner.app.data.AppSettings
import com.bibscanner.app.data.SettingsRepository
import com.bibscanner.app.ui.ResultsScreen
import com.bibscanner.app.ui.ScannerScreen
import com.bibscanner.app.ui.ScannerViewModel
import com.bibscanner.app.ui.SettingsScreen
import com.bibscanner.app.ui.VideoScreen
import com.bibscanner.app.ui.theme.BibScannerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BibScannerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scannerVm: ScannerViewModel = viewModel()

    val nav = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        NavHost(
            navController = nav,
            startDestination = "scanner",
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            composable("scanner") {
                ScannerScreen(
                    vm = scannerVm,
                    settings = settings,
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenResults = { nav.navigate("results") },
                    onOpenVideo = { nav.navigate("video") },
                )
            }
            composable("video") {
                VideoScreen(
                    vm = scannerVm,
                    settings = settings,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    current = settings,
                    onSave = { updated -> scope.launch { repo.save(updated) } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("results") {
                ResultsScreen(
                    vm = scannerVm,
                    settings = settings,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
