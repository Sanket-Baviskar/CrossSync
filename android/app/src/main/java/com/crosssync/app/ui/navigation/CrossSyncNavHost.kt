package com.crosssync.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.crosssync.app.ui.screens.*

/** Navigation routes. */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard  : Screen("dashboard",  "Dashboard",  Icons.Rounded.Dashboard)
    data object Transfers  : Screen("transfers",  "Transfers",  Icons.Rounded.SwapVert)
    data object Clipboard  : Screen("clipboard",  "Clipboard",  Icons.Rounded.ContentPaste)
    data object Settings   : Screen("settings",   "Settings",   Icons.Rounded.Settings)
}

private val BOTTOM_NAV_ITEMS = listOf(
    Screen.Dashboard,
    Screen.Transfers,
    Screen.Clipboard,
    Screen.Settings,
)

/**
 * Root nav host with a persistent bottom navigation bar.
 * Slide + fade transitions between top-level destinations.
 */
@Composable
fun CrossSyncNavHost() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavigationBar(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            ) {
                BOTTOM_NAV_ITEMS.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(screen.icon, contentDescription = screen.label)
                        },
                        label = { Text(screen.label) },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 4 }
            },
            exitTransition = {
                fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { -it / 4 }
            },
            popEnterTransition = {
                fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 4 }
            },
            popExitTransition = {
                fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { it / 4 }
            },
        ) {
            composable(Screen.Dashboard.route)  { DashboardScreen() }
            composable(Screen.Transfers.route)  { TransfersScreen() }
            composable(Screen.Clipboard.route)  { ClipboardScreen() }
            composable(Screen.Settings.route)   { SettingsScreen() }
        }
    }
}
