package com.snapcore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.snapcore.app.ui.auth.LoginScreen
import com.snapcore.app.ui.auth.SignUpScreen
import com.snapcore.app.ui.onboarding.OnboardingScreen
import com.snapcore.app.ui.auth.WelcomeScreen
import com.snapcore.app.ui.theme.SnapCoreTheme

/** Debug-only entry point for visual checks of the public auth flow. Never included in release. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var screen by remember { mutableStateOf(intent.getStringExtra("screen") ?: "welcome") }
            SnapCoreTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (screen) {
                        "login" -> LoginScreen(onBack = { screen = "welcome" }, onSession = { finish() })
                        "signup" -> SignUpScreen(onBack = { screen = "welcome" }, onSession = { finish() })
                        // Premiers pas : à parcourir avec « Passer » seulement (les autres actions modifient le compte connecté).
                        "onboarding" -> OnboardingScreen("nathan", onOpenAvatarEditor = {}, avatarChange = null, onAvatarChangeApplied = {}, onDone = { finish() })
                        else -> WelcomeScreen(onLogin = { screen = "login" }, onSignUp = { screen = "signup" })
                    }
                }
            }
        }
    }
}
