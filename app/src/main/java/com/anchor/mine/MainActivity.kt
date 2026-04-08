package com.anchor.mine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anchor.mine.security.KeyManager
import com.anchor.mine.ui.screens.AmbientHistoryScreen
import com.anchor.mine.ui.screens.ChatScreen
import com.anchor.mine.ui.screens.HomeScreen
import com.anchor.mine.ui.screens.OnboardingScreen
import com.anchor.mine.ui.screens.ReviewScreen
import com.anchor.mine.ui.screens.SettingsScreen
import com.anchor.mine.ui.theme.MineEditorialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isOpeningFromNudge = intent.getBooleanExtra("OPEN_REVIEW", false)
        val keyManager = KeyManager(this)
        val hasCompletedOnboarding = keyManager.isOnboardingComplete()

        val startRoute = when {
            isOpeningFromNudge -> "review"
            hasCompletedOnboarding -> "home"
            else -> "onboarding"
        }

        setContent {
            MineEditorialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = startRoute
                    ) {
                        composable(
                            route = "onboarding",
                            exitTransition = { fadeOut(animationSpec = tween(400)) }
                        ) {
                            OnboardingScreen(
                                onComplete = {
                                    keyManager.completeOnboarding()
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(
                            route = "home",
                            exitTransition = { fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { -it / 10 } },
                            enterTransition = { fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)) { -it / 10 } }
                        ) {
                            HomeScreen(
                                onNavigateToChat = { initialMsg ->
                                    if (initialMsg.isNotBlank()) {
                                        // FIXED: Explicitly pass the empty sessionId so the route matches!
                                        navController.navigate("chat?initialMessage=$initialMsg&sessionId=-1")
                                    } else {
                                        navController.navigate("chat?initialMessage=&sessionId=-1")
                                    }
                                },
                                onNavigateToReview = { navController.navigate("review") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToHistory = { navController.navigate("history") }
                            )
                        }

                        // Use a consistent route pattern for both empty and parameter versions
                        composable(
                            route = "chat",
                            enterTransition = { fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)) { it / 5 } },
                            popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { it / 5 } }
                        ) {
                            ChatScreen(
                                initialMessage = "",
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = "chat?initialMessage={initialMessage}&sessionId={sessionId}",
                            arguments = listOf(
                                navArgument("initialMessage") { type = NavType.StringType; defaultValue = "" },
                                navArgument("sessionId") { type = NavType.IntType; defaultValue = -1 }
                            ),
                            enterTransition = { fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)) { it / 5 } },
                            popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { it / 5 } }
                        ) { backStackEntry ->
                            val initialMessage = backStackEntry.arguments?.getString("initialMessage") ?: ""
                            val sessionId = backStackEntry.arguments?.getInt("sessionId") ?: -1

                            ChatScreen(
                                initialMessage = initialMessage,
                                loadSessionId = if (sessionId != -1) sessionId else null, // Pass the ID!
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = "review",
                            enterTransition = { slideInVertically(animationSpec = tween(300)) { it } },
                            popExitTransition = { slideOutVertically(animationSpec = tween(300)) { it } }
                        ) {
                            ReviewScreen(
                                onCommitSuccess = {
                                    navController.navigate("home") { popUpTo(0) }
                                }
                            )
                        }

                        composable(
                            route = "settings",
                            enterTransition = { slideInVertically(animationSpec = tween(300)) { it } },
                            popExitTransition = { slideOutVertically(animationSpec = tween(300)) { it } }
                        ) {
                            SettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(
                            route = "history",
                            enterTransition = { slideInVertically(animationSpec = tween(300)) { it } },
                            popExitTransition = { slideOutVertically(animationSpec = tween(300)) { it } }
                        ) {
                            AmbientHistoryScreen(onNavigateBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}