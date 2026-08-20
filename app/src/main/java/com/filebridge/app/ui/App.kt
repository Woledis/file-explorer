package com.filebridge.app.ui

import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.filebridge.app.ui.screens.ConnectionsScreen
import com.filebridge.app.ui.screens.HomeScreen
import com.filebridge.app.ui.screens.SettingsScreen
import com.filebridge.app.ui.screens.ShareScreen
import com.filebridge.app.ui.screens.VaultScreen

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "主屏", Icons.Filled.Home),
    Share("share", "共享", Icons.Outlined.Folder),
    Vault("vault", "保险箱", Icons.Filled.Lock),
    Connections("connections", "连接", Icons.Outlined.Devices),
    Settings("settings", "设置", Icons.Filled.Settings),
}

@Composable
fun App(viewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    val selected = current?.hierarchy?.any { it.route == d.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(d.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Home.route) { HomeScreen(viewModel) }
            composable(Destination.Share.route) { ShareScreen(viewModel) }
            composable(Destination.Vault.route) { VaultScreen(viewModel) }
            composable(Destination.Connections.route) { ConnectionsScreen(viewModel) }
            composable(Destination.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}