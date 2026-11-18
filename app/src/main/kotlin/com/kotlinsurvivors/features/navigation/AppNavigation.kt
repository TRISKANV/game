package com.kotlinsurvivors.features.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kotlinsurvivors.features.achievements.presentation.screen.AchievementsScreen
import com.kotlinsurvivors.features.game.presentation.screen.GameScreen
import com.kotlinsurvivors.features.menu.presentation.screen.MainMenuScreen
import com.kotlinsurvivors.features.room.presentation.screen.RoomScreen
import com.kotlinsurvivors.features.settings.presentation.screen.SettingsScreen
import com.kotlinsurvivors.features.shop.presentation.screen.ShopScreen

sealed class Screen(val route: String) {
    object MainMenu     : Screen("main_menu")
    object Game         : Screen("game")
    object Shop         : Screen("shop")
    object Achievements : Screen("achievements")
    object Settings     : Screen("settings")
    object Room         : Screen("room")
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController    = navController,
        startDestination = Screen.MainMenu.route
    ) {
        composable(Screen.MainMenu.route) {
            MainMenuScreen(
                onStartGame        = { navController.navigate(Screen.Game.route) },
                onOpenShop         = { navController.navigate(Screen.Shop.route) },
                onOpenAchievements = { navController.navigate(Screen.Achievements.route) },
                onOpenSettings     = { navController.navigate(Screen.Settings.route) },
                onOpenRoom         = { navController.navigate(Screen.Room.route) }
            )
        }

        composable(Screen.Game.route) {
            GameScreen(
                onNavigateToMenu = {
                    navController.popBackStack(Screen.MainMenu.route, inclusive = false)
                }
            )
        }

        // Level 0. The door leads back to the menu until the hallway exists.
        composable(Screen.Room.route) {
            RoomScreen(onExit = { navController.popBackStack(Screen.MainMenu.route, inclusive = false) })
        }

        composable(Screen.Shop.route) {
            ShopScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Achievements.route) {
            AchievementsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
