package com.snapcore.app.ui

import android.Manifest
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.LaunchTarget
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.Session
import com.snapcore.app.ui.auth.CompleteRegistrationScreen
import com.snapcore.app.ui.auth.LoginScreen
import com.snapcore.app.ui.auth.SignUpScreen
import com.snapcore.app.ui.auth.StartScreen
import com.snapcore.app.ui.auth.WelcomeScreen
import com.snapcore.app.ui.camera.PreviewScreen
import com.snapcore.app.ui.camera.SendToScreen
import com.snapcore.app.ui.chat.ConversationScreen
import com.snapcore.app.ui.friends.FriendsScreen
import com.snapcore.app.push.Notifications
import com.snapcore.app.ui.home.CAMERA_TAB
import com.snapcore.app.ui.home.CHAT_TAB
import com.snapcore.app.ui.home.HomeScreen
import com.snapcore.app.ui.profile.ProfileScreen
import com.snapcore.app.ui.stories.MyStoryScreen
import com.snapcore.app.ui.stories.StoryPlayerScreen
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
@Serializable private object MyStoryRoute
// authorId = null : sa propre story.
@Serializable private data class StoryPlayerRoute(val title: String, val authorId: String?, val startItemId: String? = null)
@Serializable private data class ConversationRoute(val friendId: String, val username: String, val photoUrl: String?)

// Le média capturé transite par son chemin dans le cache privé de l'app (ADR-011).
@Serializable private data class PreviewRoute(val path: String, val kind: MediaKind)
@Serializable private data class SendToRoute(val path: String, val kind: MediaKind)

private fun PreviewRoute.media() = CapturedMedia(File(path), kind)
private fun SendToRoute.media() = CapturedMedia(File(path), kind)

@Composable
fun SnapCoreNavHost(
    launchTarget: LaunchTarget?,
    onLaunchTargetHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    // Compte courant partagé entre l'accueil et le profil (mis à jour après une modification).
    var account by remember { mutableStateOf<Account?>(null) }
    var homeTab by rememberSaveable { mutableIntStateOf(CAMERA_TAB) }

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

    // Temps réel actif tant qu'un compte est connecté et que l'app est au premier plan (ADR-006) ;
    // en arrière-plan, ce sont les notifications push qui prennent le relais (ADR-018).
    if (account != null) {
        RealtimeWhileForeground()
        PushRegistration(account!!.id)
    }

    // Notification touchée : ouvre l'écran concerné une fois la session prête.
    LaunchedEffect(launchTarget, account) {
        if (launchTarget == null || account == null) return@LaunchedEffect
        when (launchTarget) {
            LaunchTarget.CHAT -> {
                homeTab = CHAT_TAB
                navController.popBackStack(HomeRoute, inclusive = false)
            }
            LaunchTarget.FRIENDS -> navController.navigate(FriendsRoute)
        }
        onLaunchTargetHandled()
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
                    selected = homeTab,
                    onSelect = { homeTab = it },
                    onOpenProfile = { navController.navigate(ProfileRoute) },
                    onOpenFriends = { navController.navigate(FriendsRoute) },
                    onCaptured = { media -> navController.navigate(PreviewRoute(media.file.absolutePath, media.kind)) },
                    onOpenConversation = { f -> navController.navigate(ConversationRoute(f.id, f.username, f.photoUrl)) },
                    onOpenMyStory = { navController.navigate(MyStoryRoute) },
                    onPlayStory = { story -> navController.navigate(StoryPlayerRoute(story.author.username, story.author.id)) },
                )
            }
        }
        composable<PreviewRoute> { entry ->
            val route = entry.toRoute<PreviewRoute>()
            PreviewScreen(
                media = route.media(),
                onDiscard = ::backToHome,
                // Média déjà filtré (ADR-022) : un nouveau fichier, l'original restant à l'aperçu.
                onSendTo = { filtered -> navController.navigate(SendToRoute(filtered.file.absolutePath, filtered.kind)) },
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
        composable<ConversationRoute> { entry ->
            val route = entry.toRoute<ConversationRoute>()
            val me = account
            if (me == null) StartScreen(onSession = ::go)
            else ConversationScreen(Friend(route.friendId, route.username, route.photoUrl), me.id, onBack = navController::popBackStack)
        }
        composable<MyStoryRoute> {
            MyStoryScreen(
                onBack = navController::popBackStack,
                onPlay = { item -> navController.navigate(StoryPlayerRoute("Ma story", authorId = null, startItemId = item.id)) },
            )
        }
        composable<StoryPlayerRoute> { entry ->
            val route = entry.toRoute<StoryPlayerRoute>()
            StoryPlayerScreen(route.title, route.authorId, route.startItemId, onClose = navController::popBackStack)
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

@Composable
private fun RealtimeWhileForeground() {
    val realtime = appContainer().realtime
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val scope = lifecycleOwner.lifecycle.coroutineScope
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> realtime.start(scope)
                Lifecycle.Event.ON_STOP -> realtime.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            realtime.stop()
        }
    }
}

// Enregistre le token FCM de l'appareil pour ce compte (ADR-018) et demande la permission
// d'afficher des notifications, obligatoire depuis Android 13.
@Composable
private fun PushRegistration(accountId: String) {
    val container = appContainer()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(accountId) {
        runCatching { container.devices.registerCurrentDevice() }
            .onFailure { Log.w("SnapCorePush", "Enregistrement de l'appareil impossible", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(context)) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
