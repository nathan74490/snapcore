package com.snapcore.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.data.avatarImageUrl
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.DeviceRegistry
import com.snapcore.app.domain.MediaUploader
import com.snapcore.app.domain.ProfilePhotoPreparer
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.UserFacingException
import com.snapcore.app.ui.OptimisticGuard
import com.snapcore.app.ui.completeEvenIfClosed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditableField(val title: String) {
    FIRST_NAME("Modifier le prénom"), LAST_NAME("Modifier le nom"), USERNAME("Modifier le pseudo"), PHONE("Modifier le téléphone")
}

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
    private val devices: DeviceRegistry,
    private val uploader: MediaUploader,
    private val photoPreparer: ProfilePhotoPreparer,
    /** Compte déjà connu de l'app : le profil s'affiche tout de suite, le rechargement le met à jour. */
    initial: Account? = null,
    private val avatarUrl: (Map<String, String>) -> String = ::avatarImageUrl,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState(account = initial, loading = initial == null))
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val guard = OptimisticGuard()

    init {
        refresh()
    }

    fun refresh() {
        val mark = guard.mark()
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { accounts.me() to auth.isEmailVerified() }
                .onSuccess { (account, verified) ->
                    // Une modification en cours (ou finie entre-temps) affiche déjà un compte plus récent.
                    _state.update { it.copy(account = if (guard.canApply(mark)) account else it.account, emailVerified = verified, loading = false) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, message = e.message) } }
        }
    }

    /**
     * Interface optimiste : la modification s'affiche tout de suite, puis part au backend. Le compte renvoyé
     * remplace l'affichage une fois toutes les modifications terminées ; en cas d'échec, seule celle-ci est
     * défaite ([revert] reçoit le compte affiché et celui d'avant) et [onFailure] dit pourquoi.
     */
    private fun optimistic(
        change: (Account) -> Account,
        revert: (shown: Account, before: Account) -> Account,
        onFailure: (ProfileState, Throwable) -> ProfileState = { s, e -> s.copy(message = e.message ?: "Modification impossible") },
        call: suspend () -> Account,
    ) {
        val before = _state.value.account ?: return
        guard.begin()
        _state.update { it.copy(account = it.account?.let(change)) }
        viewModelScope.launch {
            val result = completeEvenIfClosed { call() }
            guard.end()
            result
                .onSuccess { server -> if (guard.idle) _state.update { it.copy(account = server) } }
                .onFailure { e -> _state.update { s -> onFailure(s.copy(account = s.account?.let { revert(it, before) }), e) } }
        }
    }

    fun startEditing(field: EditableField) {
        val account = _state.value.account ?: return
        val current = when (field) {
            EditableField.FIRST_NAME -> account.firstName.orEmpty()
            EditableField.LAST_NAME -> account.lastName.orEmpty()
            EditableField.USERNAME -> account.username
            EditableField.PHONE -> account.phoneNumber.orEmpty()
        }
        _state.update { it.copy(editing = field, draft = current, draftError = null) }
    }

    fun onDraft(value: String) = _state.update { it.copy(draft = value, draftError = null) }
    fun cancelEditing() = _state.update { it.copy(editing = null) }

    // Tout le profil est modifiable, pseudo compris ; un pseudo déjà pris renvoie 409 (ADR-014).
    fun saveEditing() {
        val s = _state.value
        val field = s.editing ?: return
        val error = when (field) {
            EditableField.FIRST_NAME -> AccountRules.firstNameError(s.draft)
            EditableField.LAST_NAME -> AccountRules.lastNameError(s.draft)
            EditableField.USERNAME -> AccountRules.usernameError(s.draft)
            EditableField.PHONE -> AccountRules.phoneError(s.draft)
        }
        if (error != null || (field == EditableField.PHONE && s.draft.isBlank())) {
            _state.update { it.copy(draftError = error ?: "Numéro de téléphone requis") }
            return
        }
        val draft = s.draft
        val firstName = AccountRules.normalizeFirstName(draft)
        val lastName = AccountRules.normalizeLastName(draft)
        val username = AccountRules.normalizeUsername(draft)
        val phone = AccountRules.normalizePhone(draft)
        // Le champ se ferme tout de suite ; un refus du backend (pseudo pris : 409) le rouvre avec le motif.
        _state.update { it.copy(editing = null) }
        optimistic(
            change = { account ->
                when (field) {
                    EditableField.FIRST_NAME -> account.copy(firstName = firstName)
                    EditableField.LAST_NAME -> account.copy(lastName = lastName)
                    EditableField.USERNAME -> account.copy(username = username)
                    EditableField.PHONE -> account.copy(phoneNumber = phone)
                }
            },
            revert = { shown, before ->
                when (field) {
                    EditableField.FIRST_NAME -> shown.copy(firstName = before.firstName)
                    EditableField.LAST_NAME -> shown.copy(lastName = before.lastName)
                    EditableField.USERNAME -> shown.copy(username = before.username)
                    EditableField.PHONE -> shown.copy(phoneNumber = before.phoneNumber)
                }
            },
            onFailure = { state, e ->
                if (state.editing == null) state.copy(editing = field, draft = draft, draftError = e.message ?: "Modification impossible")
                else state.copy(message = e.message ?: "Modification impossible")
            },
        ) {
            when (field) {
                EditableField.FIRST_NAME -> accounts.updateProfile(username = null, phoneNumber = null, firstName = firstName)
                // Chaîne vide : le backend retire le nom de famille (ADR-029).
                EditableField.LAST_NAME -> accounts.updateProfile(username = null, phoneNumber = null, lastName = lastName.orEmpty())
                EditableField.USERNAME -> accounts.updateProfile(username = username, phoneNumber = null)
                EditableField.PHONE -> accounts.updateProfile(username = null, phoneNumber = phone)
            }
        }
    }

    // Photo de profil : 512×512 préparée sur le téléphone, upload R2 pré-signé puis confirmation
    // (ADR-014, ADR-016). Le backend supprime lui-même l'ancienne image. La photo choisie s'affiche
    // tout de suite, le temps de la préparer et de l'envoyer.
    fun changePhoto(source: String) = optimistic(
        change = { it.copy(profilePhotoUrl = source) },
        revert = { shown, before -> shown.copy(profilePhotoUrl = before.profilePhotoUrl) },
        onFailure = { state, e -> state.copy(message = if (e is PhotoUnreadable) "Photo illisible" else e.message ?: "Envoi impossible") },
    ) {
        val prepared = runCatching { photoPreparer.prepare(source) }.getOrElse { throw PhotoUnreadable() }
        try {
            accounts.setProfilePhoto(uploader.upload(prepared, "image/jpeg", "PROFILE_PHOTO"))
        } finally {
            prepared.delete()
        }
    }

    fun removePhoto() = optimistic(
        change = { it.copy(profilePhotoUrl = null) },
        revert = { shown, before -> shown.copy(profilePhotoUrl = before.profilePhotoUrl) },
    ) { accounts.removeProfilePhoto() }

    // Avatar choisi dans l'éditeur (ADR-028) : affiché dès le retour sur le profil, enregistré en arrière-plan.
    fun applyAvatar(change: AvatarChange) = optimistic(
        change = {
            when (change) {
                is AvatarChange.Set -> it.copy(avatar = change.options, avatarUrl = avatarUrl(change.options))
                AvatarChange.Remove -> it.copy(avatar = null, avatarUrl = null)
            }
        },
        revert = { shown, before -> shown.copy(avatar = before.avatar, avatarUrl = before.avatarUrl) },
        onFailure = { state, e -> state.copy(message = e.message ?: "Avatar non enregistré") },
    ) {
        when (change) {
            is AvatarChange.Set -> accounts.setAvatar(change.options)
            AvatarChange.Remove -> accounts.removeAvatar()
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

    // Retire d'abord le token FCM de l'appareil (tant que la session permet encore d'appeler l'API) :
    // un appareil déconnecté ne doit plus recevoir de push pour ce compte (ADR-018).
    fun signOut() {
        viewModelScope.launch {
            runCatching { devices.unregisterCurrentDevice() }
            auth.signOut()
            _state.update { it.copy(signedOut = true) }
        }
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

private class PhotoUnreadable : Exception("Photo illisible")
