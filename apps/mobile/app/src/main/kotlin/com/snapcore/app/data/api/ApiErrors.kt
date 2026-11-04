package com.snapcore.app.data.api

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

// Erreur métier lisible, construite à partir de la réponse du backend.
class ApiException(val status: Int, override val message: String) : Exception(message) {
    val isNotFound get() = status == 404
    val isConflict get() = status == 409
    val isUnauthorized get() = status == 401
}

private val errorJson = Json { ignoreUnknownKeys = true }

/** Exécute un appel d'API en traduisant les erreurs HTTP et réseau en [ApiException]. */
suspend fun <T> apiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        val body = e.response()?.errorBody()?.string()
        val parsed = body?.let { runCatching { errorJson.decodeFromString<ApiErrorBody>(it) }.getOrNull() }
        throw ApiException(e.code(), parsed?.message ?: "Erreur du serveur (${e.code()})")
    } catch (e: IOException) {
        throw ApiException(0, "Impossible de joindre le serveur. Vérifie ta connexion.")
    }

/**
 * Cause d'un échec pour l'analytics (ADR-008) : le code HTTP d'une erreur du backend, sinon le type de
 * l'exception (ex. « FirebaseAuthInvalidCredentialsException »). Jamais le message, qui peut contenir
 * un email ou un pseudo.
 */
fun Throwable.analyticsReason(): String = if (this is ApiException) "http_$status" else this::class.simpleName ?: "unknown"
