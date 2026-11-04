package com.snapcore.app.ui.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.ReportReason
import com.snapcore.app.domain.ReportTarget
import com.snapcore.app.domain.SafetyService
import com.snapcore.app.ui.completeEvenIfClosed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SafetyOutcome { REMOVED, BLOCKED, REPORTED }

data class SafetyState(val busy: Boolean = false, val outcome: SafetyOutcome? = null, val error: String? = null)

/**
 * Retirer un ami, le bloquer (ADR-012) ou signaler un contenu (ADR-021), depuis une conversation ou une
 * story. L'action va à son terme même si l'écran se ferme entre-temps (BUG-019 d'ADR-023).
 */
class SafetyViewModel(private val safety: SafetyService) : ViewModel() {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    fun removeFriend(userId: String) = run(SafetyOutcome.REMOVED) { safety.removeFriend(userId) }

    fun block(userId: String) = run(SafetyOutcome.BLOCKED) { safety.block(userId) }

    fun report(target: ReportTarget, reason: ReportReason) = run(SafetyOutcome.REPORTED) { safety.report(target, reason) }

    fun consume() = _state.update { SafetyState() }

    private fun run(outcome: SafetyOutcome, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { SafetyState(busy = true) }
        viewModelScope.launch {
            completeEvenIfClosed { action() }
                .onSuccess { _state.update { SafetyState(outcome = outcome) } }
                .onFailure { e -> _state.update { SafetyState(error = e.message ?: "Action impossible. Réessaie.") } }
        }
    }
}

data class BlockedUsersState(val users: List<Friend> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** Paramètres > Utilisateurs bloqués : seul moyen de retrouver quelqu'un qu'on a bloqué pour le débloquer (ADR-012). */
class BlockedUsersViewModel(private val safety: SafetyService) : ViewModel() {
    private val _state = MutableStateFlow(BlockedUsersState())
    val state: StateFlow<BlockedUsersState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { safety.blocked() }
                .onSuccess { users -> _state.update { it.copy(users = users.sortedBy { u -> u.displayName.lowercase() }, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Liste indisponible") } }
        }
    }

    /** Optimiste : la personne quitte la liste tout de suite, et y revient si le déblocage échoue. */
    fun unblock(user: Friend) {
        val before = _state.value.users
        _state.update { it.copy(users = it.users - user, error = null) }
        viewModelScope.launch {
            completeEvenIfClosed { safety.unblock(user.id) }
                .onFailure { e -> _state.update { it.copy(users = before, error = e.message ?: "Déblocage impossible. Réessaie.") } }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}
