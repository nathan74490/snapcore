package com.snapcore.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.snapcore.app.data.ApiAccountService
import com.snapcore.app.data.ApiChatService
import com.snapcore.app.data.ApiFriendsService
import com.snapcore.app.data.ApiSnapService
import com.snapcore.app.data.ApiStoryService
import com.snapcore.app.data.ApiSubscriptionService
import com.snapcore.app.data.BitmapProfilePhotoPreparer
import com.snapcore.app.data.BitmapStoryThumbnailMaker
import com.snapcore.app.data.DataStorePendingRegistrationStore
import com.snapcore.app.data.FirebaseAuthService
import com.snapcore.app.data.MediaFilterRenderer
import com.snapcore.app.data.PostHogAnalytics
import com.snapcore.app.data.R2MediaUploader
import com.snapcore.app.data.RealtimeClient
import com.snapcore.app.data.api.FirebaseTokenInterceptor
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.ChatService
import com.snapcore.app.domain.FilterRenderer
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaPublisher
import com.snapcore.app.domain.MediaUploader
import com.snapcore.app.domain.NoAnalytics
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.ProfilePhotoPreparer
import com.snapcore.app.domain.SessionResolver
import com.snapcore.app.domain.SnapOutbox
import com.snapcore.app.domain.StoryService
import com.snapcore.app.domain.SubscriptionService
import com.snapcore.app.push.FcmDeviceRegistry
import com.snapcore.app.push.PushDetailsResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

// Injection de dépendances manuelle : un seul graphe d'objets, créé au démarrage de l'app.
class AppContainer(context: Context) {
    private val firebaseAuth = FirebaseAuth.getInstance()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(FirebaseTokenInterceptor(firebaseAuth))
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val api: SnapCoreApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SnapCoreApi::class.java)

    val auth: AuthService = FirebaseAuthService(firebaseAuth)
    val accounts: AccountService = ApiAccountService(api)
    val subscriptions: SubscriptionService = ApiSubscriptionService(api)
    val pendingRegistration: PendingRegistrationStore = DataStorePendingRegistrationStore(context.applicationContext)
    val sessionResolver = SessionResolver(auth, accounts, pendingRegistration)

    // Client dédié aux PUT vers R2 : sans jeton Firebase (l'URL est déjà signée), délais adaptés
    // à l'envoi d'une vidéo de 60 s (jusqu'à ~60 Mo, ADR-011).
    private val storageClient = OkHttpClient.Builder()
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .build()

    val friends: FriendsService = ApiFriendsService(api)
    val mediaUploader: MediaUploader = R2MediaUploader(api, storageClient)

    val chat: ChatService = ApiChatService(api)
    val stories: StoryService = ApiStoryService(api)

    // Notifications push (ADR-018).
    val devices = FcmDeviceRegistry(api, firebaseAuth)
    val pushDetails = PushDetailsResolver(api)

    // Passerelle temps réel (ADR-006), authentifiée avec l'ID token Firebase courant.
    val realtime = RealtimeClient(httpClient, BuildConfig.API_BASE_URL) {
        firebaseAuth.currentUser?.getIdToken(false)?.await()?.token
    }

    /** Médias capturés : cache privé de l'app, jamais la galerie (ADR-011). */
    val capturesDir: File = File(context.cacheDir, "captures").apply { mkdirs() }

    val profilePhotoPreparer: ProfilePhotoPreparer = BitmapProfilePhotoPreparer(context.applicationContext, capturesDir)

    // Après capturesDir : la vignette d'une story y est écrite le temps de l'envoi (ADR-026).
    val mediaPublisher = MediaPublisher(mediaUploader, ApiSnapService(api), BitmapStoryThumbnailMaker(capturesDir))

    /** Durée de vie de l'app : ce qui doit survivre à la fermeture d'un écran (envois en arrière-plan). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Snaps et stories en cours d'envoi : l'écran d'envoi se ferme tout de suite. */
    val outbox = SnapOutbox(mediaPublisher, appScope, File(capturesDir, "outbox")).apply { clearLeftovers() }

    /** Analytics PostHog (ADR-008) ; sans clé dans local.properties, rien n'est envoyé. */
    val analytics: Analytics =
        if (BuildConfig.POSTHOG_API_KEY.isBlank()) NoAnalytics
        else PostHogAnalytics(context.applicationContext, BuildConfig.POSTHOG_API_KEY, BuildConfig.POSTHOG_HOST)

    /** Incrustation du filtre choisi sur l'aperçu, avant l'envoi (ADR-022). */
    val filterRenderer: FilterRenderer = MediaFilterRenderer(context.applicationContext, capturesDir)
}
