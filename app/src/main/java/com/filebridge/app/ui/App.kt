package com.filebridge.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filebridge.app.data.ThemeMode
import com.filebridge.app.ui.screens.ConnectionsScreen
import com.filebridge.app.ui.screens.HomeScreen
import com.filebridge.app.ui.screens.SettingsScreen
import com.filebridge.app.ui.screens.ShareScreen
import com.filebridge.app.ui.screens.VaultScreen
import com.filebridge.app.ui.theme.FileBridgeTheme
import kotlinx.coroutines.launch

private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    Home("主屏", Icons.Filled.Home),
    Share("共享", Icons.Outlined.Folder),
    Vault("保险箱", Icons.Filled.Lock),
    Connections("连接", Icons.Outlined.Devices),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
fun App(viewModel: AppViewModel = viewModel()) {
    val config by viewModel.config.collectAsState()
    val darkTheme = when (config.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    FileBridgeTheme(darkTheme = darkTheme) { AppContent(viewModel) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppContent(viewModel: AppViewModel) {
    val pagerState = rememberPagerState(initialPage = 0) { Destination.entries.size }
    val currentPage by derivedStateOf { pagerState.currentPage }
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEachIndexed { index, d ->
                    NavigationBarItem(
                        selected = currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding),
        ) { page ->
            when (Destination.entries[page]) {
                Destination.Home -> HomeScreen(viewModel)
                Destination.Share -> ShareScreen(viewModel)
                Destination.Vault -> VaultScreen(viewModel)
                Destination.Connections -> ConnectionsScreen(viewModel)
                Destination.Settings -> SettingsScreen(viewModel)
            }
        }
    }
}