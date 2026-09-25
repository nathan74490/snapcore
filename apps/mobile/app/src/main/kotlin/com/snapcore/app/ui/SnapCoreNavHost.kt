package com.snapcore.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.Session
import com.snapcore.app.ui.auth.CompleteRegistrationScreen
import com.snapcore.app.ui.auth.LoginScreen
import com.snapcore.app.ui.auth.SignUpScreen
import com.snapcore.app.ui.auth.StartScreen
import com.snapcore.app.ui.auth.WelcomeScreen
import com.snapcore.app.ui.home.HomeScreen
import com.snapcore.app.ui.profile.ProfileScreen
import kotlinx.serialization.Serializable

@Serializable private object StartRoute
@Serializable private object WelcomeRoute
@Serializable private object LoginRoute
@Serializable private object SignUpRoute
@Serializable private object CompleteRegistrationRoute
@Serializable private object HomeRoute
@Serializable private object ProfileRoute

@Composable
fun SnapCoreNavHost(navController: NavHostController = rememberNavController()) {
    // Compte courant partagé entre l'accueil et le profil (mis à jour après une modification).
    var account by remember { mutableStateOf<Account?>(null) }

    // Après résolution de la session, on remplace toute la pile : pas de retour vers l'onboarding.
    fun go(session: Session) {
        val target: Any = when (session) {
            Session.SignedOut -> WelcomeRoute
            Session.NeedsRegistration -> CompleteRegistrationRoute
            is Session.Ready -> {
                account = session.account
                HomeRoute
            }
        }
        navController.navigate(target) { popUpTo(0) { inclusive = true } }
    }

    NavHost(navController, startDestination = StartRoute) {
        composable<StartRoute> { StartScreen(onSession = ::go) }
        composable<WelcomeRoute> {
            WelcomeScreen(
                onLogin = { navController.navigate(LoginRoute) },
                onSignUp = { navController.navigate(SignUpRoute) },
            )
        }
        composable<LoginRoute> { LoginScreen(onBack = navController::popBackStack, onSession = ::go) }
        composable<SignUpRoute> { SignUpScreen(onBack = navController::popBackStack, onSession = ::go) }
        composable<CompleteRegistrationRoute> { CompleteRegistrationScreen(onSession = ::go) }
        composable<HomeRoute> {
            // Après une mort du processus, le compte en mémoire est perdu : on le résout à nouveau.
            val current = account
            if (current == null) StartScreen(onSession = ::go)
            else HomeScreen(current, onOpenProfile = { navController.navigate(ProfileRoute) })
        }
        composable<ProfileRoute> {
            ProfileScreen(
                onBack = navController::popBackStack,
                onSignedOut = { go(Session.SignedOut) },
                onAccountChanged = { account = it },
            )
        }
    }
}
