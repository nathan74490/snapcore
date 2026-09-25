package com.snapcore.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.Session
import com.snapcore.app.ui.auth.CompleteRegistrationScreen
import com.snapcore.app.ui.auth.LoginScreen
import com.snapcore.app.ui.auth.SignUpScreen
import com.snapcore.app.ui.auth.StartScreen
import com.snapcore.app.ui.auth.WelcomeScreen
import com.snapcore.app.ui.camera.PreviewScreen
import com.snapcore.app.ui.camera.SendToScreen
import com.snapcore.app.ui.friends.FriendsScreen
import com.snapcore.app.ui.home.HomeScreen
import com.snapcore.app.ui.profile.ProfileScreen
import kotlinx.serialization.Serializable
import java.io.File

@Serializable private object StartRoute
@Serializable private object WelcomeRoute
@Serializable private object LoginRoute
@Serializable private object SignUpRoute
@Serializable private object CompleteRegistrationRoute
@Serializable private object HomeRoute
@Serializable private object ProfileRoute
@Serializable private object FriendsRoute

// Le média capturé transite par son chemin dans le cache privé de l'app (ADR-011).
@Serializable private data class PreviewRoute(val path: String, val kind: MediaKind)
@Serializable private data class SendToRoute(val path: String, val kind: MediaKind)

private fun PreviewRoute.media() = CapturedMedia(File(path), kind)
private fun SendToRoute.media() = CapturedMedia(File(path), kind)

@Composable
fun SnapCoreNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
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

    fun backToHome() {
        navController.popBackStack(HomeRoute, inclusive = false)
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
            if (current == null) {
                StartScreen(onSession = ::go)
            } else {
                HomeScreen(
                    account = current,
                    onOpenProfile = { navController.navigate(ProfileRoute) },
                    onOpenFriends = { navController.navigate(FriendsRoute) },
                    onCaptured = { media -> navController.navigate(PreviewRoute(media.file.absolutePath, media.kind)) },
                )
            }
        }
        composable<PreviewRoute> { entry ->
            val route = entry.toRoute<PreviewRoute>()
            PreviewScreen(
                media = route.media(),
                onDiscard = ::backToHome,
                onSendTo = { navController.navigate(SendToRoute(route.path, route.kind)) },
            )
        }
        composable<SendToRoute> { entry ->
            SendToScreen(
                media = entry.toRoute<SendToRoute>().media(),
                onBack = navController::popBackStack,
                onSent = {
                    Toast.makeText(context, "Snap envoyé !", Toast.LENGTH_SHORT).show()
                    backToHome()
                },
                onAddFriends = { navController.navigate(FriendsRoute) },
            )
        }
        composable<FriendsRoute> { FriendsScreen(onBack = navController::popBackStack) }
        composable<ProfileRoute> {
            ProfileScreen(
                onBack = navController::popBackStack,
                onSignedOut = { go(Session.SignedOut) },
                onAccountChanged = { account = it },
            )
        }
    }
}
