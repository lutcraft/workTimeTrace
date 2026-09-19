package com.worktime.trace.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.ui.calendar.CalendarScreen
import com.worktime.trace.ui.calendar.CalendarViewModel
import com.worktime.trace.ui.home.HomeScreen
import com.worktime.trace.ui.home.HomeViewModel
import com.worktime.trace.ui.map.MapPickerScreen
import com.worktime.trace.ui.records.RecordsScreen
import com.worktime.trace.ui.records.RecordsViewModel
import com.worktime.trace.ui.settings.SettingsScreen
import com.worktime.trace.ui.settings.SettingsViewModel
import com.worktime.trace.ui.stats.StatsScreen
import com.worktime.trace.ui.stats.StatsViewModel

object Routes {
    const val HOME = "home"
    const val RECORDS = "records"
    const val CALENDAR = "calendar"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val MAP_PICKER = "map_picker?workplaceId={workplaceId}"
    fun mapPicker(workplaceId: Long = -1L) = "map_picker?workplaceId=$workplaceId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "主页", Icons.Filled.Home),
    Tab(Routes.RECORDS, "记录", Icons.AutoMirrored.Filled.ListAlt),
    Tab(Routes.CALENDAR, "日历", Icons.Filled.CalendarMonth),
    Tab(Routes.STATS, "统计", Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings)
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel =
                    viewModel(factory = AppVmFactory(context.app()) { HomeViewModel(it) })
                HomeScreen(vm, onOpenMap = { id -> navController.navigate(Routes.mapPicker(id)) })
            }
            composable(Routes.RECORDS) {
                val vm: RecordsViewModel =
                    viewModel(factory = AppVmFactory(context.app()) { RecordsViewModel(it) })
                RecordsScreen(vm)
            }
            composable(Routes.CALENDAR) {
                val vm: CalendarViewModel =
                    viewModel(factory = AppVmFactory(context.app()) { CalendarViewModel(it) })
                CalendarScreen(vm)
            }
            composable(Routes.STATS) {
                val vm: StatsViewModel =
                    viewModel(factory = AppVmFactory(context.app()) { StatsViewModel(it) })
                StatsScreen(vm)
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel =
                    viewModel(factory = AppVmFactory(context.app()) { SettingsViewModel(it) })
                SettingsScreen(vm)
            }
            composable(
                route = Routes.MAP_PICKER,
                arguments = listOf(navArgument("workplaceId") {
                    type = androidx.navigation.NavType.LongType
                    defaultValue = -1L
                })
            ) { entry ->
                val id = entry.arguments?.getLong("workplaceId") ?: -1L
                MapPickerScreen(workplaceId = id, onBack = { navController.popBackStack() })
            }
        }
    }
}

/** 基于应用单例构造 ViewModel 的简单工厂。 */
class AppVmFactory(
    private val app: WorkTimeTraceApp,
    private val creator: (WorkTimeTraceApp) -> androidx.lifecycle.ViewModel
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : androidx.lifecycle.ViewModel> create(modelClass: Class<VM>): VM =
        creator(app) as VM
}

private fun Context.app(): WorkTimeTraceApp = applicationContext as WorkTimeTraceApp
