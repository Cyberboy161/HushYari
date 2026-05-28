package dev.hushyari.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.hushyari.ui.screens.GameConfigScreen
import dev.hushyari.ui.screens.GameScreen
import dev.hushyari.ui.screens.HistoryScreen
import dev.hushyari.ui.screens.HomeScreen
import dev.hushyari.ui.screens.ModelManagerScreen
import dev.hushyari.ui.screens.SettingsScreen
import dev.hushyari.ui.screens.SkillEditorScreen

sealed class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector? = null,
    val unselectedIcon: ImageVector? = null,
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Game : Screen("game/{gamePackage}?taskDescription={taskDescription}", "Game")
    data object Skills : Screen("skills/{skillId}", "Skills", Icons.Filled.Star, Icons.Outlined.Star)
    data object SkillEditor : Screen("skill_editor?skillId={skillId}", "Skill Editor")
    data object GameConfig : Screen("game_config/{gamePackage}", "Game Config")
    data object History : Screen("history", "History", Icons.Filled.History, Icons.Outlined.History)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    data object ModelManager : Screen("model_manager", "Model Manager")
}

val bottomNavScreens = listOf(Screen.Home, Screen.Skills, Screen.History, Screen.Settings)

@Composable
fun NavGraph(
    navController: androidx.navigation.NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in bottomNavScreens.map { it.route }
            || currentDestination?.route?.startsWith("skills/") == true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        val isSelected = currentDestination?.hierarchy?.any {
                            when (screen) {
                                Screen.Skills -> it.route?.startsWith("skills") == true || it.route == "skill_editor"
                                else -> it.route == screen.route
                            }
                        } == true

                        NavigationBarItem(
                            selected = isSelected,
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
                                Icon(
                                    imageVector = if (isSelected) screen.selectedIcon ?: screen.unselectedIcon!!
                                    else screen.unselectedIcon!!,
                                    contentDescription = screen.label,
                                )
                            },
                            label = { Text(screen.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToGame = { pkg ->
                        navController.navigate("game/$pkg")
                    },
                    onNavigateToSkills = {
                        navController.navigate("skills/0")
                    },
                    onNavigateToHistory = {
                        navController.navigate(Screen.History.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                )
            }

            composable(
                route = Screen.Game.route,
                arguments = listOf(
                    navArgument("gamePackage") { type = NavType.StringType },
                    navArgument("taskDescription") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val gamePackage = backStackEntry.arguments?.getString("gamePackage") ?: ""
                val taskDescription = backStackEntry.arguments?.getString("taskDescription") ?: ""

                GameScreen(
                    gamePackage = gamePackage,
                    taskDescription = taskDescription,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.Skills.route,
                arguments = listOf(
                    navArgument("skillId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val skillId = backStackEntry.arguments?.getString("skillId") ?: ""

                SkillEditorScreen(
                    skillId = skillId.ifEmpty { null },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.SkillEditor.route,
                arguments = listOf(
                    navArgument("skillId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val skillId = backStackEntry.arguments?.getString("skillId") ?: ""

                SkillEditorScreen(
                    skillId = skillId.ifEmpty { null },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.GameConfig.route,
                arguments = listOf(
                    navArgument("gamePackage") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val gamePackage = backStackEntry.arguments?.getString("gamePackage")

                GameConfigScreen(
                    gamePackage = gamePackage,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.History.route) {
                HistoryScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.ModelManager.route) {
                ModelManagerScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
