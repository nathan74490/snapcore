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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snapcore.app.ui.components.GhostMood
import com.snapcore.app.ui.onboarding.GhostSays

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
                        // Toutes les humeurs du fantôme de l'onboarding, animées côte à côte.
                        "ghosts" -> GhostGallery()
                        // Paywall proposé en fin d'onboarding (ne pas toucher le bouton d'abonnement).
                        "paywall-onboarding" -> com.snapcore.app.ui.plus.PaywallScreen(returnedFromCheckout = false, onBack = { finish() },
                            onAccountChanged = {}, fromOnboarding = true)
                        else -> WelcomeScreen(onLogin = { screen = "login" }, onSignUp = { screen = "signup" })
                    }
                }
            }
        }
    }
}

@Composable
private fun GhostGallery() {
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GhostMood.entries.forEach { mood -> GhostSays(mood.name, mood, ghostSize = 88.dp) }
    }
}
