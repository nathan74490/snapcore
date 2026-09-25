package com.snapcore.app.data.api

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import okhttp3.Interceptor
import okhttp3.Response

// Ajoute `Authorization: Bearer <ID token Firebase>` à chaque requête (ADR-015). Le SDK renvoie
// le token en cache et ne le rafraîchit que s'il expire bientôt. Les intercepteurs OkHttp
// s'exécutent hors du thread principal : l'attente bloquante est donc sans risque ici.
class FirebaseTokenInterceptor(private val auth: FirebaseAuth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val user = auth.currentUser ?: return chain.proceed(chain.request())
        val token = runCatching { Tasks.await(user.getIdToken(false)).token }.getOrNull()
            ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
    }
}
