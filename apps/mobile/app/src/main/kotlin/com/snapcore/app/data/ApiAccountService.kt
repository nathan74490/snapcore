package com.snapcore.app.data

import com.snapcore.app.data.api.ConfirmUploadRequest
import com.snapcore.app.data.api.RegisterRequest
import com.snapcore.app.data.api.SetAvatarRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.UpdateProfileRequest
import com.snapcore.app.data.api.UserDto
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AvatarCatalog
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.SubscriptionService
import com.snapcore.app.domain.UsernameAvailability
import com.snapcore.app.domain.isPlusActive
import java.time.Instant
import java.time.LocalDate

class ApiAccountService(private val api: SnapCoreApi) : AccountService {
    override suspend fun me() = apiCall { api.me() }.toAccount()

    override suspend fun register(registration: PendingRegistration) =
        apiCall {
            api.register(RegisterRequest(registration.username, registration.firstName, registration.phoneNumber,
                registration.lastName, registration.birthday?.toString()))
        }.toAccount()

    override suspend fun updateProfile(username: String?, phoneNumber: String?, firstName: String?, lastName: String?) =
        apiCall { api.updateProfile(UpdateProfileRequest(username, phoneNumber, firstName, lastName)) }.toAccount()

    override suspend fun usernameAvailability(username: String) =
        apiCall { api.usernameAvailability(username) }.let { UsernameAvailability(it.available, it.reason) }

    override suspend fun deleteAccount() = apiCall { api.deleteAccount() }

    override suspend fun setProfilePhoto(uploadId: String) = apiCall { api.setProfilePhoto(ConfirmUploadRequest(uploadId)) }.toAccount()

    override suspend fun removeProfilePhoto() = apiCall { api.removeProfilePhoto() }.toAccount()

    override suspend fun avatarCatalog() = apiCall { api.avatarCatalog() }.let { AvatarCatalog(it.defaults, it.options) }

    override suspend fun setAvatar(options: Map<String, String>) = apiCall { api.setAvatar(SetAvatarRequest(options)) }.toAccount()

    // DELETE ne renvoie rien : le compte à jour est relu.
    override suspend fun removeAvatar(): Account {
        apiCall { api.removeAvatar() }
        return me()
    }
}

private fun UserDto.toAccount(): Account {
    val until = premiumUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return Account(
        id = id,
        username = username,
        email = email,
        phoneNumber = phoneNumber,
        profilePhotoUrl = profilePhotoUrl,
        isPremium = isPlusActive(subscriptionTier, until, Instant.now()),
        premiumUntil = until,
        firstName = firstName,
        avatar = avatar,
        avatarUrl = avatar?.let(::avatarImageUrl),
        lastName = lastName,
        birthday = birthday?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )
}

class ApiSubscriptionService(private val api: SnapCoreApi) : SubscriptionService {
    override suspend fun checkoutUrl() = apiCall { api.checkout() }.url
}
