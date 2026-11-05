package com.snapcore.app

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AvatarCatalog
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.DeviceRegistry
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.PhoneCodeSent
import com.snapcore.app.domain.ProfilePhotoPreparer
import com.snapcore.app.domain.UserFacingException
import com.snapcore.app.domain.UsernameAvailability
import java.io.File

class FakeAuth : AuthService {
    var signedIn = false
    var signOuts = 0
    var signedInWithEmail = false

    override val isSignedIn get() = signedIn
    override val currentEmail get() = if (signedIn && signedInWithEmail) "moi@snapcore.test" else null

    // Opérateur SMS fictif (ADR-034) : le code envoyé est toujours renvoyé.
    val codesSentTo = mutableListOf<String>()
    var code = "482913"
    var failCodeRequestWith: ApiException? = null
    val phoneSignIns = mutableListOf<String>()

    override suspend fun requestPhoneCode(phoneNumber: String): PhoneCodeSent {
        failCodeRequestWith?.let { throw it }
        codesSentTo += phoneNumber
        return PhoneCodeSent(phoneNumber, resendInSeconds = 30, simulatedCode = code)
    }

    override suspend fun signInWithPhoneCode(phoneNumber: String, code: String) {
        if (code != this.code) throw ApiException(400, "Code incorrect. Vérifie le SMS et réessaie.")
        phoneSignIns += phoneNumber
        signedIn = true
    }

    var failSignInWith: Exception? = null

    override suspend fun signIn(email: String, password: String) {
        failSignInWith?.let { throw it }
        signedIn = true
        signedInWithEmail = true
    }

    override fun signOut() {
        signedIn = false
        signOuts++
    }

    override suspend fun isEmailVerified() = false
    override suspend fun resendVerificationEmail() = Unit
}

// Simule le backend : un compte par session Firebase, pseudos uniques.
class FakeAccounts : AccountService {
    var account: Account? = null
    val takenUsernames = mutableSetOf<String>()
    var meError: ApiException? = null
    var registerError: ApiException? = null
    val registrations = mutableListOf<PendingRegistration>()
    var deleted = false

    override suspend fun me(): Account {
        meError?.let { throw it }
        return account ?: throw ApiException(404, "Compte introuvable")
    }

    override suspend fun register(registration: PendingRegistration): Account {
        registrations += registration
        registerError?.let { throw it }
        if (registration.username in takenUsernames) throw ApiException(409, "Ce pseudo est déjà pris")
        return Account("id-1", registration.username, "moi@snapcore.test", registration.phoneNumber, null, false)
            .also { account = it }
    }

    override suspend fun updateProfile(username: String?, phoneNumber: String?, firstName: String?, lastName: String?): Account {
        if (username != null && username in takenUsernames) throw ApiException(409, "Ce pseudo est déjà pris")
        val current = account!!
        return current.copy(username = username ?: current.username, phoneNumber = phoneNumber ?: current.phoneNumber,
            firstName = firstName ?: current.firstName, lastName = lastName?.ifEmpty { null } ?: current.lastName)
            .also { account = it }
    }

    /** Tous les pseudos sont pris : la proposition automatique doit abandonner. */
    var allUsernamesTaken = false
    override suspend fun usernameAvailability(username: String) =
        if (allUsernamesTaken || username in takenUsernames) UsernameAvailability(false, "Ce pseudo est déjà pris") else UsernameAvailability(true)

    override suspend fun deleteAccount() {
        deleted = true
    }

    val confirmedUploads = mutableListOf<String>()
    override suspend fun setProfilePhoto(uploadId: String): Account {
        confirmedUploads += uploadId
        return account!!.copy(profilePhotoUrl = "https://cdn.test/profiles/$uploadId").also { account = it }
    }

    override suspend fun removeProfilePhoto(): Account = account!!.copy(profilePhotoUrl = null).also { account = it }
    var catalog = AvatarCatalog(
        defaults = mapOf("top" to "shortFlat", "eyes" to "default", "mouth" to "smile", "facialHair" to "none"),
        options = mapOf("top" to listOf("none", "shortFlat", "bob"), "eyes" to listOf("default", "cry", "happy"), "mouth" to listOf("smile", "sad"), "facialHair" to listOf("none", "beardLight")),
    )
    var savedAvatar: Map<String, String>? = null
    override suspend fun avatarCatalog() = catalog
    override suspend fun setAvatar(options: Map<String, String>): Account =
        account!!.copy(avatar = options, avatarUrl = "avatar.png").also { account = it; savedAvatar = options }
    override suspend fun removeAvatar(): Account = account!!.copy(avatar = null, avatarUrl = null).also { account = it; savedAvatar = null }
}

class FakePending : PendingRegistrationStore {
    var value: PendingRegistration? = null
    override suspend fun load() = value
    override suspend fun save(registration: PendingRegistration) {
        value = registration
    }
    override suspend fun clear() {
        value = null
    }
}

class FakeDevices : DeviceRegistry {
    var registered = 0
    var unregistered = 0
    override suspend fun registerCurrentDevice() {
        registered++
    }
    override suspend fun unregisterCurrentDevice() {
        unregistered++
    }
}

class FakePhotoPreparer : ProfilePhotoPreparer {
    val prepared = mutableListOf<File>()
    var fail = false
    override suspend fun prepare(source: String): File {
        if (fail) error("image illisible")
        return File.createTempFile("profile", ".jpg").apply { writeText("jpeg") }.also { prepared += it }
    }
}

/** Analytics qui garde les événements capturés (ADR-008), dans l'ordre. */
class RecordingAnalytics : com.snapcore.app.domain.Analytics {
    val events = mutableListOf<Pair<String, Map<String, Any>>>()
    val names get() = events.map { it.first }
    fun propertiesOf(event: String) = events.last { it.first == event }.second
    override fun identify(userId: String) = Unit
    override fun reset() = Unit
    override fun screen(name: String) = Unit
    override fun capture(event: String, properties: Map<String, Any>) {
        events += event to properties
    }
}
