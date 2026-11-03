package com.snapcore.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleOut
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
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.firstUnviewedIndex
import com.snapcore.app.domain.Session
import com.snapcore.app.domain.screenNameOf
import com.snapcore.app.push.Notifications
import com.snapcore.app.ui.auth.LoginScreen
import com.snapcore.app.ui.auth.SignUpScreen
import com.snapcore.app.ui.auth.StartScreen
import com.snapcore.app.ui.auth.WelcomeScreen
import com.snapcore.app.ui.camera.PreviewScreen
import com.snapcore.app.ui.camera.SendToScreen
import com.snapcore.app.ui.chat.ConversationScreen
import com.snapcore.app.ui.friends.FriendsScreen
import com.snapcore.app.ui.home.CAMERA_TAB
import com.snapcore.app.ui.profile.AvatarChange
import com.snapcore.app.ui.profile.AvatarEditorScreen
import com.snapcore.app.ui.home.CHAT_TAB
import com.snapcore.app.ui.home.HomeScreen
import com.snapcore.app.ui.onboarding.OnboardingScreen
import com.snapcore.app.ui.home.STORIES_TAB
import com.snapcore.app.ui.plus.PaywallScreen
import com.snapcore.app.ui.profile.ProfileScreen
import com.snapcore.app.ui.stories.MyStoryScreen
import com.snapcore.app.ui.stories.StoryPlayerScreen
import kotlinx.serialization.Serializable
import java.io.File

// Écrans des onglets de l'accueil pour l'analytics (ADR-008), dans l'ordre MAP_TAB, CHAT_TAB, CAMERA_TAB, STORIES_TAB.
private val HOME_TAB_SCREENS = listOf("Map", "Chat", "Camera", "Stories")

@Serializable private object StartRoute
@Serializable private object WelcomeRoute
@Serializable private object LoginRoute
@Serializable private object SignUpRoute
@Serializable private object CompleteRegistrationRoute
// Premiers pas après une inscription (ADR-029).
@Serializable private object OnboardingRoute
@Serializable private object HomeRoute
@Serializable private object ProfileRoute
@Serializable private object FriendsRoute
@Serializable private object MyStoryRoute
@Serializable private object AvatarEditorRoute
// Paywall SnapCore+ ; returnedFromCheckout = ouvert par le lien de retour du paiement (ADR-025).
@Serializable private data class PaywallRoute(val returnedFromCheckout: Boolean = false)
// authorId = null : sa propre story.
@Serializable private data class StoryPlayerRoute(val title: String, val authorId: String?, val startItemId: String? = null)
// openSnapId : snap à lire dès l'ouverture, touché depuis la liste des chats.
@Serializable private data class ConversationRoute(val friendId: String, val username: String, val photoUrl: String?, val firstName: String? = null, val openSnapId: String? = null)

// Le média capturé transite par son chemin dans le cache privé de l'app (ADR-011).
@Serializable private data class PreviewRoute(val path: String, val kind: MediaKind)
@Serializable private data class SendToRoute(val path: String, val kind: MediaKind, val recipientId: String? = null)

private fun PreviewRoute.media() = CapturedMedia(File(path), kind)
private fun SendToRoute.media() = CapturedMedia(File(path), kind)

@Composable
fun SnapCoreNavHost(
    launchTarget: LaunchTarget?,
    onLaunchTargetHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Compte courant partagé entre l'accueil et le profil (mis à jour après une modification).
    var account by remember { mutableStateOf<Account?>(null) }
    var homeTab by rememberSaveable { mutableIntStateOf(CAMERA_TAB) }
    // Premiers pas en cours après une inscription : la demande de notifications attend leur dernier écran.
    var onboarding by rememberSaveable { mutableStateOf(false) }
    // Avatar validé dans l'éditeur, en route vers le profil qui l'affiche puis l'enregistre.
    var avatarChange by remember { mutableStateOf<AvatarChange?>(null) }
    val analytics = appContainer().analytics
    val homeLayer = rememberGraphicsLayer()
    val navigationScope = rememberCoroutineScope()
    var chatBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var swipeDismissed by remember { mutableStateOf(false) }
    var openingChat by remember { mutableStateOf(false) }
    // Ami à qui envoyer le prochain snap : posé par le bouton appareil photo d'une conversation ou de
    // la liste Chat, il est coché d'office à l'envoi. Quitter la caméra l'oublie.
    var snapRecipient by remember { mutableStateOf<Friend?>(null) }

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

    // Compte tout juste créé : les premiers pas avant l'accueil (ADR-029).
    fun afterSignUp(session: Session) {
        if (session !is Session.Ready) return go(session)
        account = session.account
        analytics.identify(session.account.id)
        onboarding = true
        navController.navigate(OnboardingRoute) { popUpTo(0) { inclusive = true } }
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
        PushRegistration(account!!.id, askPermission = !onboarding)
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

    NavHost(
        navController,
        startDestination = StartRoute,
        // Retour prédictif d'Android (touche ou geste retour du système) : par défaut, la page qui se ferme
        // rétrécit (scaleOut 0.7). Une conversation glisse plutôt vers la droite et révèle l'accueil, comme
        // avec la flèche de l'en-tête ou le glissement du doigt. L'animation suit la progression du retour.
        predictivePopEnterTransition = {
            if (initialState.destination.hasRoute<ConversationRoute>())
                slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 }
            else fadeIn(spring(dampingRatio = 1f, stiffness = 1600f))
        },
        predictivePopExitTransition = {
            if (initialState.destination.hasRoute<ConversationRoute>())
                slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it }
            else scaleOut(targetScale = .7f)
        },
    ) {
        composable<StartRoute>(exitTransition = { fadeOut(tween(180)) }) { StartScreen(onSession = ::go) }
        composable<WelcomeRoute> {
            WelcomeScreen(
                onLogin = { navController.navigate(LoginRoute) },
                onSignUp = { navController.navigate(SignUpRoute) },
            )
        }
        composable<LoginRoute> { LoginScreen(onBack = navController::popBackStack, onSession = ::go) }
        composable<SignUpRoute> { SignUpScreen(onBack = navController::popBackStack, onSession = ::afterSignUp) }
        // Compte Firebase sans compte SnapCore : on reprend l'inscription aux étapes nom, naissance, pseudo.
        composable<CompleteRegistrationRoute> { SignUpScreen(onBack = {}, onSession = ::afterSignUp, resuming = true) }
        composable<OnboardingRoute> {
            val me = account
            if (me == null) StartScreen(onSession = ::go)
            else OnboardingScreen(
                username = me.username,
                onOpenAvatarEditor = { navController.navigate(AvatarEditorRoute) },
                avatarChange = avatarChange,
                onAvatarChangeApplied = { avatarChange = null },
                onDone = {
                    onboarding = false
                    homeTab = CAMERA_TAB
                    navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
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
                    onSelect = {
                        homeTab = it
                        if (it != CAMERA_TAB) snapRecipient = null
                    },
                    snapRecipient = snapRecipient,
                    onSnapTo = { friend ->
                        snapRecipient = friend
                        homeTab = CAMERA_TAB
                    },
                    onClearSnapRecipient = { snapRecipient = null },
                    onOpenProfile = { navController.navigate(ProfileRoute) },
                    onOpenFriends = { navController.navigate(FriendsRoute) },
                    onCaptured = { media -> navController.navigate(PreviewRoute(media.file.absolutePath, media.kind)) },
                    onOpenConversation = { f, snapId ->
                        if (!openingChat) {
                            openingChat = true
                            navigationScope.launch {
                                chatBackdrop = runCatching { homeLayer.toImageBitmap() }.getOrNull()
                                swipeDismissed = false
                                navController.navigate(ConversationRoute(f.id, f.username, f.pictureUrl, f.firstName, snapId))
                                openingChat = false
                            }
                        }
                    },
                    onOpenMyStory = { navController.navigate(MyStoryRoute) },
                    // Depuis la carte, la story passée ne contient que les éléments d'un pin : la lecture part du premier non vu.
                    onPlayStory = { story ->
                        val start = story.items.getOrNull(firstUnviewedIndex(story.items))?.id
                        navController.navigate(StoryPlayerRoute(story.author.displayName, story.author.id, start))
                    },
                )
            }
        }
        composable<PreviewRoute> { entry ->
            val route = entry.toRoute<PreviewRoute>()
            PreviewScreen(
                media = route.media(),
                onDiscard = ::backToHome,
                // Média déjà filtré (ADR-022) : un nouveau fichier, l'original restant à l'aperçu.
                onSendTo = { filtered -> navController.navigate(SendToRoute(filtered.file.absolutePath, filtered.kind, snapRecipient?.id)) },
            )
        }
        composable<SendToRoute> { entry ->
            SendToScreen(
                media = entry.toRoute<SendToRoute>().media(),
                recipientId = entry.toRoute<SendToRoute>().recipientId,
                onBack = navController::popBackStack,
                // Comme dans Snapchat : on suit l'envoi là où il s'affiche (« Envoi… » dans le chat, ou sur Ma story).
                onSent = { targets ->
                    snapRecipient = null
                    homeTab = if (targets.any { it is SendTarget.ToFriend }) CHAT_TAB else STORIES_TAB
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
                Friend(route.friendId, route.username, route.photoUrl, route.firstName),
                me.id,
                onOpenCamera = {
                    snapRecipient = Friend(route.friendId, route.username, route.photoUrl, route.firstName)
                    homeTab = CAMERA_TAB
                    backToHome()
                },
                backdrop = chatBackdrop,
                onSwipeBack = { swipeDismissed = true; navController.popBackStack() },
                onBack = navController::popBackStack,
                onOpenPlus = { navController.navigate(PaywallRoute()) },
                openSnapId = route.openSnapId,
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
                onOpenAvatar = { navController.navigate(AvatarEditorRoute) },
                onNewStory = {
                    homeTab = CAMERA_TAB
                    backToHome()
                },
                onAccountChanged = { account = it },
                onOpenPlus = { navController.navigate(PaywallRoute()) },
                initialAccount = account,
                avatarChange = avatarChange,
                onAvatarChangeApplied = { avatarChange = null },
            )
        }
        composable<AvatarEditorRoute> {
            val me = account
            if (me == null) StartScreen(onSession = ::go)
            else AvatarEditorScreen(me, onBack = navController::popBackStack, onSaved = {
                avatarChange = it
                navController.popBackStack()
            })
        }
        composable<PaywallRoute> { entry ->
            PaywallScreen(
                returnedFromCheckout = entry.toRoute<PaywallRoute>().returnedFromCheckout,
                onBack = navController::popBackStack,
                onAccountChanged = { account = it },
                initialAccount = account,
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
private fun PushRegistration(accountId: String, askPermission: Boolean) {
    val container = appContainer()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(accountId, askPermission) {
        runCatching { container.devices.registerCurrentDevice() }
            .onFailure { Log.w("SnapCorePush", "Enregistrement de l'appareil impossible", it) }
        // Demandée une seule fois par installation (BUG-007) ; ensuite, depuis les paramètres du profil.
        if (askPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(context) &&
            Notifications.claimFirstPermissionRequest(context)
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
