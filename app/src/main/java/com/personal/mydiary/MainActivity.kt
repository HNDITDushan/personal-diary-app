package com.personal.mydiary

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.personal.mydiary.ui.screens.BackupScreen
import com.personal.mydiary.ui.screens.DiaryScreen
import com.personal.mydiary.ui.screens.EventsScreen
import com.personal.mydiary.ui.screens.MoneyScreen
import com.personal.mydiary.ui.screens.MoreScreen
import com.personal.mydiary.ui.screens.PlacesScreen
import com.personal.mydiary.ui.screens.ReportScreen
import com.personal.mydiary.ui.screens.SheetScreen
import com.personal.mydiary.ui.screens.TasksScreen
import com.personal.mydiary.ui.screens.TodayScreen
import com.personal.mydiary.ui.theme.MyDiaryTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MyDiaryTheme { AppRoot() } }
    }
}

object Routes {
    const val TODAY = "today"
    const val TASKS = "tasks"
    const val DIARY = "diary"
    const val MONEY = "money"
    const val EVENTS = "events"
    const val MORE = "more"
    const val PLACES = "places"
    const val REPORT = "report"
    const val BACKUP = "backup"
    const val SHEET = "sheet"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TODAY, "Today", Icons.Outlined.WbSunny),
    Tab(Routes.TASKS, "Tasks", Icons.Outlined.CheckCircle),
    Tab(Routes.DIARY, "Diary", Icons.AutoMirrored.Outlined.MenuBook),
    Tab(Routes.MONEY, "Money", Icons.Outlined.AccountBalanceWallet),
    Tab(Routes.EVENTS, "Events", Icons.Outlined.Event),
    Tab(Routes.MORE, "More", Icons.Outlined.MoreHoriz),
)

/** Sub-screens reached from "More" keep that tab highlighted. */
private val moreChildren = setOf(Routes.PLACES, Routes.REPORT, Routes.BACKUP, Routes.SHEET)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route.let { if (it in moreChildren) Routes.MORE else it }
    val go: (String) -> Unit = { nav.navigate(it) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.padding(padding)) {
            composable(Routes.TODAY) { TodayScreen(onOpen = go) }
            composable(Routes.TASKS) { TasksScreen() }
            composable(Routes.DIARY) { DiaryScreen() }
            composable(Routes.MONEY) { MoneyScreen() }
            composable(Routes.EVENTS) { EventsScreen() }
            composable(Routes.MORE) { MoreScreen(onOpen = go) }
            composable(Routes.PLACES) { PlacesScreen(onBack = nav::popBackStack) }
            composable(Routes.REPORT) { ReportScreen(onBack = nav::popBackStack) }
            composable(Routes.BACKUP) { BackupScreen(onBack = nav::popBackStack) }
            composable(Routes.SHEET) { SheetScreen(onBack = nav::popBackStack) }
        }
    }
}
