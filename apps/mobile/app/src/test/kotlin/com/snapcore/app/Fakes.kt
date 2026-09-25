package com.snapcore.app

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.DeviceRegistry
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.ProfilePhotoPreparer
import com.snapcore.app.domain.UserFacingException
import java.io.File

class FakeAuth : AuthService {
    var signedIn = false
    var failSignUpWith: String? = null
    val signUps = mutableListOf<String>()
    var signOuts = 0

    override val isSignedIn get() = signedIn
    override val currentEmail get() = if (signedIn) "moi@snapcore.test" else null

    override suspend fun signUp(email: String, password: String) {
        failSignUpWith?.let { throw UserFacingException(it) }
        signUps += email
        signedIn = true
    }

    override suspend fun signIn(email: String, password: String) {
        signedIn = true
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

    override suspend fun updateProfile(username: String?, phoneNumber: String?): Account {
        if (username != null && username in takenUsernames) throw ApiException(409, "Ce pseudo est déjà pris")
        val current = account!!
        return current.copy(username = username ?: current.username, phoneNumber = phoneNumber ?: current.phoneNumber)
            .also { account = it }
    }

    override suspend fun deleteAccount() {
        deleted = true
    }

    val confirmedUploads = mutableListOf<String>()
    override suspend fun setProfilePhoto(uploadId: String): Account {
        confirmedUploads += uploadId
        return account!!.copy(profilePhotoUrl = "https://cdn.test/profiles/$uploadId").also { account = it }
    }

    override suspend fun removeProfilePhoto(): Account = account!!.copy(profilePhotoUrl = null).also { account = it }
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
