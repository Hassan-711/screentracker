package com.screentrack.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.screentrack.app.data.repository.PermissionState
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.data.repository.UsageStatsRepository
import com.screentrack.app.service.ScreenMonitorForegroundService
import com.screentrack.app.ui.navigation.Screen
import com.screentrack.app.ui.screens.AnalyticsScreen
import com.screentrack.app.ui.screens.AppUsageScreen
import com.screentrack.app.ui.screens.AuthScreen
import com.screentrack.app.ui.screens.DashboardScreen
import com.screentrack.app.ui.screens.OnboardingScreen
import com.screentrack.app.ui.screens.RemoteMonitorScreen
import com.screentrack.app.ui.screens.RemoteSharingScreen
import com.screentrack.app.ui.screens.SettingsScreen
import com.screentrack.app.ui.theme.ScreenTrackTheme
import com.screentrack.app.worker.UsageSyncWorker

class MainActivity : ComponentActivity() {

    private lateinit var usageRepo: UsageStatsRepository
    private lateinit var supabaseRepo: SupabaseRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkAndStartLiveService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        usageRepo = UsageStatsRepository(this)
        supabaseRepo = SupabaseRepository(this)

        // Ensure live 10-second monitoring is enabled by default
        supabaseRepo.setForegroundMonitorEnabled(true)

        // 1. Immediately request Notification permission (Android 13+)
        requestNotificationPermissionIfNeeded()

        // 2. Automatically launch Usage Access settings immediately on install
        if (usageRepo.checkPermissionState() !is PermissionState.Granted) {
            try {
                startActivity(usageRepo.createUsageAccessSettingsIntent())
            } catch (_: Throwable) {
            }
        } else {
            requestBatteryOptimizationExemptionIfNeeded()
            checkAndStartLiveService()
        }

        setContent {
            ScreenTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenTrackMainApp(
                        usageRepo = usageRepo,
                        supabaseRepo = supabaseRepo
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (usageRepo.checkPermissionState() is PermissionState.Granted) {
            requestBatteryOptimizationExemptionIfNeeded()
            checkAndStartLiveService()
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {}
        }
    }

    private fun checkAndStartLiveService() {
        if (usageRepo.checkPermissionState() is PermissionState.Granted) {
            try {
                ScreenMonitorForegroundService.start(this)
            } catch (_: Throwable) {
            }
            try {
                UsageSyncWorker.triggerImmediateSync(this)
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Throwable) {
            }
        }
    }
}

@Composable
fun ScreenTrackMainApp(
    usageRepo: UsageStatsRepository,
    supabaseRepo: SupabaseRepository
) {
    val navController = rememberNavController()
    var permissionState by remember { mutableStateOf(usageRepo.checkPermissionState()) }
    var currentSession by remember { mutableStateOf(supabaseRepo.getCurrentSession()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val newState = usageRepo.checkPermissionState()
                permissionState = newState
                currentSession = supabaseRepo.getCurrentSession()
                if (newState is PermissionState.Granted) {
                    try {
                        if (navController.currentDestination?.route == Screen.Onboarding.route) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val startDestination = remember {
        if (usageRepo.checkPermissionState() !is PermissionState.Granted) {
            Screen.Onboarding.route
        } else {
            Screen.Dashboard.route
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = remember { Screen.bottomNavItems }
    val bottomNavRoutes = remember(bottomNavItems) { bottomNavItems.mapNotNull { it.route }.toSet() }
    val showBottomBar = currentRoute != null && currentRoute in bottomNavRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = {
                                screen.icon?.let {
                                    Icon(imageVector = it, contentDescription = screen.title)
                                }
                            },
                            label = { Text(screen.title, fontSize = 10.sp, maxLines = 1) },
                            selected = selected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    permissionState = permissionState,
                    onPermissionGranted = {
                        permissionState = usageRepo.checkPermissionState()
                        if (permissionState is PermissionState.Granted) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        }
                    },
                    onContinue = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Auth.route) {
                AuthScreen(
                    onAuthSuccess = {
                        currentSession = supabaseRepo.getCurrentSession()
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToApps = {
                        navController.navigate(Screen.AppUsage.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToAuth = {
                        navController.navigate(Screen.Auth.route)
                    }
                )
            }

            composable(Screen.RemoteMonitor.route) {
                RemoteMonitorScreen(
                    onNavigateToAuth = {
                        navController.navigate(Screen.Auth.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            composable(Screen.AppUsage.route) {
                AppUsageScreen()
            }

            composable(Screen.Analytics.route) {
                AnalyticsScreen()
            }

            composable(Screen.RemoteSharing.route) {
                RemoteSharingScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToAuth = {
                        navController.navigate(Screen.Auth.route)
                    },
                    onLogout = {
                        currentSession = null
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
