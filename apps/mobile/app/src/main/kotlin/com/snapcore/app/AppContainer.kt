package com.snapcore.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.snapcore.app.data.ApiAccountService
import com.snapcore.app.data.DataStorePendingRegistrationStore
import com.snapcore.app.data.FirebaseAuthService
import com.snapcore.app.data.api.FirebaseTokenInterceptor
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.SessionResolver
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

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
    val pendingRegistration: PendingRegistrationStore = DataStorePendingRegistrationStore(context.applicationContext)
    val sessionResolver = SessionResolver(auth, accounts, pendingRegistration)
}
