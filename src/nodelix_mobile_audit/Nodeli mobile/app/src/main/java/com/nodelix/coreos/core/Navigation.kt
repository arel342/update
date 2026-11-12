package com.nodelix.coreos.core

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nodelix.coreos.ui.connection.ConnectionScreen
import com.nodelix.coreos.ui.connection.ConnectionViewModel
import com.nodelix.coreos.ui.dashboard.DashboardScreen
import com.nodelix.coreos.ui.dashboard.DashboardViewModel
import com.nodelix.coreos.ui.devices.DevicesScreen
import com.nodelix.coreos.ui.devices.DevicesViewModel
import com.nodelix.coreos.ui.devices.DoorDetailScreen
import com.nodelix.coreos.ui.devices.Esp32DetailScreen
import com.nodelix.coreos.ui.diagnostics.DiagnosticsScreen
import com.nodelix.coreos.ui.diagnostics.DiagnosticsViewModel
import com.nodelix.coreos.ui.login.LoginScreen
import com.nodelix.coreos.ui.login.LoginViewModel
import com.nodelix.coreos.ui.monitor.SystemMonitorScreen
import com.nodelix.coreos.ui.monitor.SystemMonitorViewModel
import com.nodelix.coreos.ui.notifications.NotificationsScreen
import com.nodelix.coreos.ui.notifications.NotificationsViewModel
import com.nodelix.coreos.ui.settings.RegisteredDevicesScreen
import com.nodelix.coreos.ui.settings.ServerSettingsScreen
import com.nodelix.coreos.ui.settings.ServerSettingsViewModel
import com.nodelix.coreos.ui.settings.SettingsScreen
import com.nodelix.coreos.ui.settings.SettingsViewModel
import com.nodelix.coreos.ui.splash.BootDestination
import com.nodelix.coreos.ui.splash.BootSplashScreen
import com.nodelix.coreos.ui.splash.BootViewModel
import com.nodelix.coreos.ui.storage.StorageScreen
import com.nodelix.coreos.ui.storage.StorageViewModel
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixBackgroundSecondary
import com.nodelix.coreos.ui.theme.NodelixCardSecondary
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.ui.updater.UpdateCenterScreen
import com.nodelix.coreos.ui.updater.UpdateCenterViewModel

object NodelixRoutes {
    const val BOOT = "boot"
    const val CONNECTION = "connection"
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val MONITOR = "monitor"
    const val DEVICES = "devices"
    const val ESP32_DETAIL = "devices/esp32"
    const val DOOR_DETAIL = "devices/door"
    const val STORAGE = "storage"
    const val NOTIFICATIONS = "notifications"
    const val UPDATE_CENTER = "update_center"
    const val SETTINGS = "settings"
    const val SERVER_SETTINGS = "settings/server"
    const val REGISTERED_DEVICES = "settings/registered_devices"
    const val DIAGNOSTICS = "diagnostics"
}

private data class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val primaryTabs = listOf(
    BottomNavTab(NodelixRoutes.DASHBOARD, "Dashboard", Icons.Outlined.Dashboard),
    BottomNavTab(NodelixRoutes.MONITOR, "System", Icons.Outlined.MonitorHeart),
    BottomNavTab(NodelixRoutes.DEVICES, "Devices", Icons.Outlined.DeveloperBoard),
    BottomNavTab(NodelixRoutes.STORAGE, "Storage", Icons.Outlined.Folder),
    BottomNavTab(NodelixRoutes.SETTINGS, "Settings", Icons.Outlined.Settings)
)

private val primaryTabRoutes = primaryTabs.map { it.route }.toSet()

@Composable
fun NodelixRootApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in primaryTabRoutes

    // Shared DevicesViewModel across Devices list and ESP32/Door detail views
    val sharedDevicesViewModel: DevicesViewModel = viewModel(factory = DevicesViewModel.factory(container))
    val sharedSettingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))

    Scaffold(
        containerColor = NodelixBackground,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = NodelixBackgroundSecondary,
                    tonalElevation = 0.dp
                ) {
                    val currentDestination = backStackEntry?.destination
                    primaryTabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NodelixPrimary,
                                selectedTextColor = NodelixPrimary,
                                indicatorColor = NodelixCardSecondary,
                                unselectedIconColor = NodelixTextSecondary,
                                unselectedTextColor = NodelixTextSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { rootPadding ->
        NavHost(
            navController = navController,
            startDestination = NodelixRoutes.BOOT,
            modifier = Modifier.padding(bottom = rootPadding.calculateBottomPadding())
        ) {
            composable(NodelixRoutes.BOOT) {
                val vm: BootViewModel = viewModel(factory = BootViewModel.factory(container))
                BootSplashScreen(
                    viewModel = vm,
                    onBootFinished = { dest ->
                        val route = when (dest) {
                            BootDestination.CONNECTION -> NodelixRoutes.CONNECTION
                            BootDestination.LOGIN -> NodelixRoutes.LOGIN
                            BootDestination.DASHBOARD -> NodelixRoutes.DASHBOARD
                        }
                        navController.navigate(route) {
                            popUpTo(NodelixRoutes.BOOT) { inclusive = true }
                        }
                    }
                )
            }

            composable(NodelixRoutes.CONNECTION) {
                val vm: ConnectionViewModel = viewModel(factory = ConnectionViewModel.factory(container))
                val canGoBack = navController.previousBackStackEntry != null
                ConnectionScreen(
                    viewModel = vm,
                    canNavigateBack = canGoBack,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToLogin = {
                        navController.navigate(NodelixRoutes.LOGIN) {
                            if (!canGoBack) {
                                popUpTo(NodelixRoutes.CONNECTION) { inclusive = true }
                            }
                        }
                    },
                    onNavigateToDashboard = {
                        navController.navigate(NodelixRoutes.DASHBOARD) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(NodelixRoutes.LOGIN) {
                val vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(container))
                LoginScreen(
                    viewModel = vm,
                    onAuthenticated = {
                        navController.navigate(NodelixRoutes.DASHBOARD) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenServerConfig = {
                        navController.navigate(NodelixRoutes.CONNECTION)
                    }
                )
            }

            composable(NodelixRoutes.DASHBOARD) {
                val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
                DashboardScreen(
                    viewModel = vm,
                    onOpenNotifications = { navController.navigate(NodelixRoutes.NOTIFICATIONS) },
                    onOpenServerConfig = { navController.navigate(NodelixRoutes.CONNECTION) },
                    onOpenLogin = {
                        navController.navigate(NodelixRoutes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenEsp32Detail = { navController.navigate(NodelixRoutes.ESP32_DETAIL) },
                    onOpenDoorDetail = { navController.navigate(NodelixRoutes.DOOR_DETAIL) },
                    onOpenUpdateCenter = { navController.navigate(NodelixRoutes.UPDATE_CENTER) }
                )
            }

            composable(NodelixRoutes.MONITOR) {
                val vm: SystemMonitorViewModel = viewModel(factory = SystemMonitorViewModel.factory(container))
                SystemMonitorScreen(viewModel = vm)
            }

            composable(NodelixRoutes.DEVICES) {
                DevicesScreen(
                    viewModel = sharedDevicesViewModel,
                    onOpenEsp32Detail = { navController.navigate(NodelixRoutes.ESP32_DETAIL) },
                    onOpenDoorDetail = { navController.navigate(NodelixRoutes.DOOR_DETAIL) },
                    onOpenFirmwareUpdater = { navController.navigate(NodelixRoutes.UPDATE_CENTER) }
                )
            }

            composable(NodelixRoutes.ESP32_DETAIL) {
                Esp32DetailScreen(
                    viewModel = sharedDevicesViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFirmwareUpdater = { navController.navigate(NodelixRoutes.UPDATE_CENTER) }
                )
            }

            composable(NodelixRoutes.DOOR_DETAIL) {
                DoorDetailScreen(
                    viewModel = sharedDevicesViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFirmwareUpdater = { navController.navigate(NodelixRoutes.UPDATE_CENTER) }
                )
            }

            composable(NodelixRoutes.STORAGE) {
                val vm: StorageViewModel = viewModel(factory = StorageViewModel.factory(container))
                StorageScreen(viewModel = vm)
            }

            composable(NodelixRoutes.NOTIFICATIONS) {
                val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(container))
                NotificationsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(NodelixRoutes.UPDATE_CENTER) {
                val vm: UpdateCenterViewModel = viewModel(factory = UpdateCenterViewModel.factory(container))
                UpdateCenterScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(NodelixRoutes.SERVER_SETTINGS) {
                val vm: ServerSettingsViewModel = viewModel(factory = ServerSettingsViewModel.factory(container))
                ServerSettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(NodelixRoutes.SETTINGS) {
                SettingsScreen(
                    viewModel = sharedSettingsViewModel,
                    onOpenConnectionManager = { navController.navigate(NodelixRoutes.CONNECTION) },
                    onOpenServerSettings = { navController.navigate(NodelixRoutes.SERVER_SETTINGS) },
                    onOpenRegisteredDevices = { navController.navigate(NodelixRoutes.REGISTERED_DEVICES) },
                    onOpenUpdateCenter = { navController.navigate(NodelixRoutes.UPDATE_CENTER) },
                    onOpenDiagnostics = { navController.navigate(NodelixRoutes.DIAGNOSTICS) },
                    onLoggedOut = {
                        navController.navigate(NodelixRoutes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(NodelixRoutes.REGISTERED_DEVICES) {
                RegisteredDevicesScreen(
                    viewModel = sharedSettingsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onSelfRevoked = {
                        navController.navigate(NodelixRoutes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(NodelixRoutes.DIAGNOSTICS) {
                val vm: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.factory(container))
                DiagnosticsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
