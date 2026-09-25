package com.snapcore.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.UserFacingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditableField { USERNAME, PHONE }

data class ProfileState(
    val account: Account? = null,
    val emailVerified: Boolean? = null,
    val loading: Boolean = true,
    val editing: EditableField? = null,
    val draft: String = "",
    val draftError: String? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val confirmDeletion: Boolean = false,
    /** L'utilisateur a quitté son compte (déconnexion ou suppression) : retour à l'accueil. */
    val signedOut: Boolean = false,
)

class ProfileViewModel(
    private val auth: AuthService,
    private val accounts: AccountService,
    private val pending: PendingRegistrationStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { accounts.me() to auth.isEmailVerified() }
                .onSuccess { (account, verified) ->
                    _state.update { it.copy(account = account, emailVerified = verified, loading = false) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, message = e.message) } }
        }
    }

    fun startEditing(field: EditableField) {
        val account = _state.value.account ?: return
        val current = if (field == EditableField.USERNAME) account.username else account.phoneNumber.orEmpty()
        _state.update { it.copy(editing = field, draft = current, draftError = null) }
    }

    fun onDraft(value: String) = _state.update { it.copy(draft = value, draftError = null) }
    fun cancelEditing() = _state.update { it.copy(editing = null) }

    // Tout le profil est modifiable, pseudo compris ; un pseudo déjà pris renvoie 409 (ADR-014).
    fun saveEditing() {
        val s = _state.value
        val field = s.editing ?: return
        val error = if (field == EditableField.USERNAME) AccountRules.usernameError(s.draft) else AccountRules.phoneError(s.draft)
        if (error != null || (field == EditableField.PHONE && s.draft.isBlank())) {
            _state.update { it.copy(draftError = error ?: "Numéro de téléphone requis") }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val updated = if (field == EditableField.USERNAME) {
                    accounts.updateProfile(username = AccountRules.normalizeUsername(s.draft), phoneNumber = null)
                } else {
                    accounts.updateProfile(username = null, phoneNumber = AccountRules.normalizePhone(s.draft))
                }
                _state.update { it.copy(account = updated, editing = null, saving = false, message = "Profil mis à jour") }
            } catch (e: ApiException) {
                _state.update { it.copy(saving = false, draftError = e.message) }
            }
        }
    }

    fun resendVerification() {
        viewModelScope.launch {
            val message = runCatching { auth.resendVerificationEmail() }
                .fold({ "Email de confirmation renvoyé" }, { (it as? UserFacingException)?.message ?: "Envoi impossible" })
            _state.update { it.copy(message = message) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun signOut() {
        auth.signOut()
        _state.update { it.copy(signedOut = true) }
    }

    fun askDeletion() = _state.update { it.copy(confirmDeletion = true) }
    fun cancelDeletion() = _state.update { it.copy(confirmDeletion = false) }

    // Le backend bloque le compte immédiatement ; l'app se déconnecte de Firebase dès la
    // confirmation, pour ne jamais rejouer l'ancien token (ADR-015, ADR-019).
    fun deleteAccount() {
        _state.update { it.copy(saving = true, confirmDeletion = false) }
        viewModelScope.launch {
            try {
                accounts.deleteAccount()
                pending.clear()
                auth.signOut()
                _state.update { it.copy(saving = false, signedOut = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(saving = false, message = e.message) }
            }
        }
    }
}
