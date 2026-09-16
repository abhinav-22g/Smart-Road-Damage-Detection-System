package com.example.smartroaddamage

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.smartroaddamage.data.PreferencesManager
import com.example.smartroaddamage.ui.screens.CockpitScreen
import com.example.smartroaddamage.permissions.PermissionManager
import com.example.smartroaddamage.ui.onboarding.OnboardingScreen
import com.example.smartroaddamage.ui.screens.HardwareBridgeScreen
import com.example.smartroaddamage.ui.screens.SignInScreen
import com.example.smartroaddamage.ui.screens.OpeningScreen
import com.example.smartroaddamage.ui.theme.MyApplicationTheme
import com.google.firebase.auth.FirebaseAuth

private const val PERMISSION_REQUEST_CODE = 1

class MainActivity : ComponentActivity() {

    private val hasPermissions = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasPermissions.value = PermissionManager.hasRequiredPermissions(this)
        if (!hasPermissions.value) {
            PermissionManager.requestRequiredPermissions(this, PERMISSION_REQUEST_CODE)
        }

        // ── Determine the correct start screen ──────────────────────────────
        // Read both signals synchronously before first composition:
        //   1. Firebase session  → is the user already signed in?
        //   2. SharedPreferences → has this device seen onboarding before?
        //
        // Decision table:
        //   Firebase user  | Onboarding done | Start screen
        //   ───────────────┼─────────────────┼──────────────
        //   ✅ logged in   | any             | HardwareBridge  (skip both)
        //   ❌ not logged  | ✅ done          | SignIn          (skip onboarding)
        //   ❌ not logged  | ❌ first time    | Onboarding
        val prefs = PreferencesManager(this)
        val isAlreadySignedIn = FirebaseAuth.getInstance().currentUser != null
        val hasSeenOnboarding = prefs.isOnboardingCompletedSync()

        val startScreen: AppScreen = when {
            isAlreadySignedIn  -> AppScreen.HardwareBridge
            hasSeenOnboarding  -> AppScreen.SignIn
            else               -> AppScreen.Onboarding
        }

        setContent {
            MyApplicationTheme {
                var currentScreen by rememberSaveable { mutableStateOf(startScreen) }

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        when (targetState) {
                            AppScreen.Cockpit -> {
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness    = Spring.StiffnessLow
                                    )
                                ) + fadeIn() togetherWith
                                slideOutVertically(
                                    targetOffsetY = { -it },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness    = Spring.StiffnessLow
                                    )
                                ) + fadeOut()
                            }
                            else -> fadeIn() togetherWith fadeOut()
                        }
                    },
                    label = "screen_transition"
                ) { screen ->
                    when (screen) {
                        AppScreen.Onboarding -> {
                            OnboardingScreen(
                                onComplete = {
                                    // Permanently mark onboarding as done so we never
                                    // show it again — even after the user signs out.
                                    prefs.setOnboardingCompletedSync()
                                    currentScreen = AppScreen.SignIn
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        AppScreen.SignIn -> {
                            SignInScreen(
                                onSignInSuccess = {
                                    currentScreen = AppScreen.HardwareBridge
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        AppScreen.HardwareBridge -> {
                            HardwareBridgeScreen(
                                onNavigateToDashboard = {
                                    currentScreen = AppScreen.Opening
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        AppScreen.Opening -> {
                            OpeningScreen(
                                onAnimationFinished = {
                                    currentScreen = AppScreen.Cockpit
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        AppScreen.Cockpit -> {
                            CockpitScreen(
                                onDisconnect = {
                                    currentScreen = AppScreen.HardwareBridge
                                },
                                isDarkMode = true
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermissions.value = PermissionManager.hasRequiredPermissions(this)
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                hasPermissions.value = true
            } else if (!PermissionManager.shouldShowRequestPermissionRationale(this)) {
                hasPermissions.value = false
            }
        }
    }
}

private enum class AppScreen {
    Onboarding,
    SignIn,
    HardwareBridge,
    Opening,
    Cockpit
}
