package com.dosely.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dosely.app.ads.AdsManager
import com.dosely.app.translate.S
import com.dosely.app.ui.calendar.CalendarScreen
import com.dosely.app.ui.coach.CoachScreen
import com.dosely.app.ui.components.AdBanner
import com.dosely.app.ui.doses.DosesScreen
import com.dosely.app.ui.home.HomeScreen
import com.dosely.app.ui.legal.LegalScreen
import com.dosely.app.ui.onboarding.OnboardingFlow
import com.dosely.app.ui.settings.SettingsScreen
import com.dosely.app.ui.theme.DoselyTheme
import com.dosely.app.ui.theme.ThemeMode
import com.dosely.app.ui.weight.WeightScreen
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val HOME = "home"
    const val DOSES = "doses"
    const val WEIGHT = "weight"
    const val CALENDAR = "calendar"
    const val COACH = "coach"
    const val SETTINGS = "settings"
    const val LEGAL_TOS = "legal/tos"
    const val LEGAL_PRIVACY = "legal/privacy"
}

private data class Tab(
    val route: String,
    val labelKey: String,
    val icon: ImageVector,
    val iconSelected: ImageVector,
)

private val tabs = listOf(
    Tab(Routes.HOME, "nav_home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.DOSES, "nav_doses", Icons.Outlined.Medication, Icons.Filled.Medication),
    Tab(Routes.CALENDAR, "nav_calendar", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Tab(Routes.WEIGHT, "nav_weight", Icons.Outlined.MonitorWeight, Icons.Filled.MonitorWeight),
    Tab(Routes.COACH, "nav_coach", Icons.Outlined.SelfImprovement, Icons.Filled.SelfImprovement),
    Tab(Routes.SETTINGS, "nav_settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(appViewModel: AppViewModel = koinViewModel(), initialRoute: String? = null) {
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val mode = ThemeMode.from(settings?.themeMode ?: "system")
    val context = LocalContext.current

    var adsReady by remember { mutableStateOf(AdsManager.get(context).canRequestAds) }

    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        AdsManager.get(context).gatherConsent(activity) { _ ->
            adsReady = AdsManager.get(context).canRequestAds
        }
    }

    DoselyTheme(mode) {
        when {
            settings == null -> Box(Modifier.fillMaxSize())
            !settings!!.onboarded -> OnboardingFlow()
            else -> MainScaffold(appViewModel, initialRoute, adsReady)
        }
    }
}

@Composable
private fun MainScaffold(appViewModel: AppViewModel, initialRoute: String?, adsReady: Boolean) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(initialRoute) {
        if (initialRoute != null && tabs.any { it.route == initialRoute }) {
            navController.navigate(initialRoute) { launchSingleTop = true }
        }
    }

    // Hide the bottom bar while the keyboard is up so chat inputs sit flush
    // against the IME instead of floating a nav-bar height above it.
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = !imeVisible,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                Column {
                    if (adsReady) {
                        AdBanner()
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        if (selected) tab.iconSelected else tab.icon,
                                        contentDescription = S(tab.labelKey),
                                    )
                                },
                                label = { Text(S(tab.labelKey)) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                enterTransition = {
                    fadeIn(tween(240)) + slideInHorizontally(tween(280)) { it / 10 }
                },
                exitTransition = { fadeOut(tween(180)) },
                popEnterTransition = { fadeIn(tween(240)) },
                popExitTransition = {
                    fadeOut(tween(180)) + slideOutHorizontally(tween(280)) { it / 10 }
                },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        appViewModel = appViewModel,
                        onNavigateToCoach = { navController.navigate(Routes.COACH) },
                        onNavigateToDoses = { navController.navigate(Routes.DOSES) },
                    )
                }
                composable(Routes.DOSES) { DosesScreen() }
                composable(Routes.CALENDAR) { CalendarScreen() }
                composable(Routes.WEIGHT) { WeightScreen() }
                composable(Routes.COACH) { CoachScreen() }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onOpenLegal = { privacy ->
                        navController.navigate(if (privacy) Routes.LEGAL_PRIVACY else Routes.LEGAL_TOS)
                    })
                }
                composable(Routes.LEGAL_TOS) { LegalScreen(isPrivacy = false, onBack = { navController.popBackStack() }) }
                composable(Routes.LEGAL_PRIVACY) { LegalScreen(isPrivacy = true, onBack = { navController.popBackStack() }) }
            }
        }
    }
}
