package com.snapcore.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.launch
import android.Manifest
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavController
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
import com.snapcore.app.domain.screenNameOf
import com.snapcore.app.push.Notifications
import com.snapcore.app.ui.auth.CompleteRegistrationScreen
import com.snapcore.app.ui.auth.LoginScreen
import com.snapcore.app.ui.auth.SignUpScreen
import com.snapcore.app.ui.auth.StartScreen
import com.snapcore.app.ui.auth.WelcomeScreen
import com.snapcore.app.ui.camera.PreviewScreen
import com.snapcore.app.ui.camera.SendToScreen
import com.snapcore.app.ui.chat.ConversationScreen
import com.snapcore.app.ui.friends.FriendsScreen
import com.snapcore.app.ui.home.CAMERA_TAB
import com.snapcore.app.ui.home.CHAT_TAB
import com.snapcore.app.ui.home.HomeScreen
import com.snapcore.app.ui.plus.PaywallScreen
import com.snapcore.app.ui.profile.ProfileScreen
import com.snapcore.app.ui.stories.MyStoryScreen
import com.snapcore.app.ui.stories.StoryPlayerScreen
import kotlinx.serialization.Serializable
import java.io.File

// Écrans des onglets de l'accueil pour l'analytics (ADR-008), dans l'ordre CHAT_TAB, CAMERA_TAB, STORIES_TAB.
private val HOME_TAB_SCREENS = listOf("Chat", "Camera", "Stories")

@Serializable private object StartRoute
@Serializable private object WelcomeRoute
@Serializable private object LoginRoute
@Serializable private object SignUpRoute
@Serializable private object CompleteRegistrationRoute
@Serializable private object HomeRoute
@Serializable private object ProfileRoute
@Serializable private object FriendsRoute
@Serializable private object MyStoryRoute
// Paywall SnapCore+ ; returnedFromCheckout = ouvert par le lien de retour du paiement (ADR-025).
@Serializable private data class PaywallRoute(val returnedFromCheckout: Boolean = false)
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
    val analytics = appContainer().analytics
    val homeLayer = rememberGraphicsLayer()
    val navigationScope = rememberCoroutineScope()
    var chatBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var swipeDismissed by remember { mutableStateOf(false) }
    var openingChat by remember { mutableStateOf(false) }

    // Après résolution de la session, on remplace toute la pile : pas de retour vers l'onboarding.
    fun go(session: Session) {
        // Le compte courant suit toujours la session : null après une déconnexion, ce qui arrête le
        // temps réel et l'enregistrement de l'appareil (BUG-002 d'ADR-023).
        account = (session as? Session.Ready)?.account
        // Même identifiant que le backend (ADR-008) ; à la déconnexion, plus rien n'est rattaché au compte.
        when (session) {
            is Session.Ready -> analytics.identify(session.account.id)
            Session.SignedOut -> analytics.reset()
            Session.NeedsRegistration -> Unit
        }
        val target: Any = when (session) {
            Session.SignedOut -> WelcomeRoute
            Session.NeedsRegistration -> CompleteRegistrationRoute
            is Session.Ready -> HomeRoute
        }
        navController.navigate(target) { popUpTo(0) { inclusive = true } }
    }

    // Écrans visités (ADR-008) : la route courante, ou l'onglet de l'accueil.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            screenNameOf(destination.route)?.let(analytics::screen)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
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
            // Remplace le paywall déjà ouvert avant le paiement, plutôt que d'en empiler un second.
            LaunchTarget.SNAPCORE_PLUS -> navController.navigate(PaywallRoute(returnedFromCheckout = true)) {
                popUpTo<PaywallRoute> { inclusive = true }
            }
        }
        onLaunchTargetHandled()
    }

    NavHost(navController, startDestination = StartRoute) {
        composable<StartRoute>(exitTransition = { fadeOut(tween(180)) }) { StartScreen(onSession = ::go) }
        composable<WelcomeRoute> {
            WelcomeScreen(
                onLogin = { navController.navigate(LoginRoute) },
                onSignUp = { navController.navigate(SignUpRoute) },
            )
        }
        composable<LoginRoute> { LoginScreen(onBack = navController::popBackStack, onSession = ::go) }
        composable<SignUpRoute> { SignUpScreen(onBack = navController::popBackStack, onSession = ::go) }
        composable<CompleteRegistrationRoute> { CompleteRegistrationScreen(onSession = ::go) }
        composable<HomeRoute>(
            enterTransition = {
                if (initialState.destination.hasRoute<StartRoute>()) fadeIn(tween(180))
                else fadeIn(tween(220))
            },
            exitTransition = {
                if (targetState.destination.hasRoute<ConversationRoute>())
                    slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 }
                else fadeOut(tween(180))
            },
            popEnterTransition = {
                if (initialState.destination.hasRoute<ConversationRoute>()) {
                    if (swipeDismissed) EnterTransition.None
                    else slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 }
                } else fadeIn(tween(180))
            },
        ) {
            LifecycleResumeEffect(Unit) {
                chatBackdrop = null
                onPauseOrDispose { }
            }
            // Après une mort du processus, le compte en mémoire est perdu : on le résout à nouveau.
            val current = account
            if (current == null) {
                StartScreen(onSession = ::go)
            } else {
                LaunchedEffect(homeTab) { analytics.screen(HOME_TAB_SCREENS[homeTab]) }
                HomeScreen(
                    modifier = Modifier.drawWithContent {
                        homeLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(homeLayer)
                    },
                    account = current,
                    selected = homeTab,
                    onSelect = { homeTab = it },
                    onOpenProfile = { navController.navigate(ProfileRoute) },
                    onOpenFriends = { navController.navigate(FriendsRoute) },
                    onCaptured = { media -> navController.navigate(PreviewRoute(media.file.absolutePath, media.kind)) },
                    onOpenConversation = { f ->
                        if (!openingChat) {
                            openingChat = true
                            navigationScope.launch {
                                chatBackdrop = runCatching { homeLayer.toImageBitmap() }.getOrNull()
                                swipeDismissed = false
                                navController.navigate(ConversationRoute(f.id, f.username, f.photoUrl))
                                openingChat = false
                            }
                        }
                    },
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
        composable<ConversationRoute>(
            enterTransition = { slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } },
            exitTransition = { slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 } },
            popExitTransition = { if (swipeDismissed) ExitTransition.None else slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } },
        ) { entry ->
            val route = entry.toRoute<ConversationRoute>()
            val me = account
            if (me == null) StartScreen(onSession = ::go)
            else ConversationScreen(
                Friend(route.friendId, route.username, route.photoUrl),
                me.id,
                backdrop = chatBackdrop,
                onSwipeBack = { swipeDismissed = true; navController.popBackStack() },
                onBack = navController::popBackStack,
                onOpenPlus = { navController.navigate(PaywallRoute()) },
            )
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
                onOpenMyStory = { navController.navigate(MyStoryRoute) },
                onOpenFriends = { navController.navigate(FriendsRoute) },
                onAccountChanged = { account = it },
                onOpenPlus = { navController.navigate(PaywallRoute()) },
            )
        }
        composable<PaywallRoute> { entry ->
            PaywallScreen(
                returnedFromCheckout = entry.toRoute<PaywallRoute>().returnedFromCheckout,
                onBack = navController::popBackStack,
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
        // Demandée une seule fois par installation (BUG-007) ; ensuite, depuis les paramètres du profil.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(context) &&
            Notifications.claimFirstPermissionRequest(context)
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
