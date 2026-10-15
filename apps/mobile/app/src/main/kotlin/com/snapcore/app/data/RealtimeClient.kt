package com.snapcore.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Événement reçu de la passerelle (voir apps/backend/README.md, section Temps réel). */
sealed interface RealtimeEvent {
    /** Connexion (ré)établie : le client doit resynchroniser son état via l'API REST (ADR-006). */
    data object Connected : RealtimeEvent
    data class Message(val type: String, val data: JsonObject) : RealtimeEvent
}

/**
 * Client Socket.io v4 minimal, en WebSocket uniquement, sur OkHttp. Il couvre ce dont l'app a
 * besoin : authentification par ID token Firebase, pings, réception d'événements, reconnexion.
 * Évite la bibliothèque Java officielle, liée à OkHttp 3 et incompatible avec OkHttp 5.
 *
 * Trames Engine.IO v4 : "0" ouverture, "2"/"3" ping/pong, "4" message ; à l'intérieur, trames
 * Socket.IO : "40" connexion au namespace, "41" déconnexion, "42" événement ["nom", données].
 */
class RealtimeClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val idToken: suspend () -> String?,
) {
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var socket: WebSocket? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                val token = idToken()
                if (token == null) {
                    delay(5_000)
                    continue
                }
                val connected = runSession(token)
                // Connexion réussie puis perdue : on retente vite ; échec d'emblée : délai croissant.
                backoffMs = if (connected) 1_000L else (backoffMs * 2).coerceAtMost(30_000L)
                delay(backoffMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
        socket = null
    }

    /** Ouvre une connexion et attend sa fermeture. @return true si l'authentification a réussi. */
    private suspend fun runSession(token: String): Boolean {
        val closed = Channel<Unit>(Channel.CONFLATED)
        var authenticated = false
        val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/socket.io/?EIO=4&transport=websocket"

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.startsWith("0") -> webSocket.send("40" + buildJsonObject { put("token", token) })
                    text == "2" -> webSocket.send("3")
                    text.startsWith("42") -> handleEvent(text.substring(2))?.let { event ->
                        if (event.type == "ready") {
                            authenticated = true
                            _events.tryEmit(RealtimeEvent.Connected)
                        } else if (event.type == "auth_error") {
                            Log.w(TAG, "Authentification temps réel refusée : ${event.data}")
                        } else {
                            _events.tryEmit(event)
                        }
                    }
                    text.startsWith("41") -> webSocket.close(1000, null)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.trySend(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "Connexion temps réel perdue : ${t.message}")
                closed.trySend(Unit)
            }
        }
        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
        try {
            closed.receive()
        } finally {
            socket?.cancel()
        }
        return authenticated
    }

    private fun handleEvent(payload: String): RealtimeEvent.Message? = runCatching {
        val array = Json.parseToJsonElement(payload) as JsonArray
        val name = array[0].jsonPrimitive.contentOrNull ?: return null
        val data = array.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        RealtimeEvent.Message(name, data)
    }.getOrNull()

    companion object {
        private const val TAG = "RealtimeClient"
    }
}

/** Champ texte d'un événement (ex. "messageId"). */
fun RealtimeEvent.Message.field(name: String): String? = (data[name] as? JsonPrimitive)?.contentOrNull
