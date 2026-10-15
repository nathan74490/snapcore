package com.snapcore.app.data

import com.snapcore.app.data.api.ConfirmUploadRequest
import com.snapcore.app.data.api.RegisterRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.UpdateProfileRequest
import com.snapcore.app.data.api.UserDto
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.SubscriptionService
import com.snapcore.app.domain.isPlusActive
import java.time.Instant

class ApiAccountService(private val api: SnapCoreApi) : AccountService {
    override suspend fun me() = apiCall { api.me() }.toAccount()

    override suspend fun register(registration: PendingRegistration) =
        apiCall { api.register(RegisterRequest(registration.username, registration.phoneNumber)) }.toAccount()

    override suspend fun updateProfile(username: String?, phoneNumber: String?) =
        apiCall { api.updateProfile(UpdateProfileRequest(username, phoneNumber)) }.toAccount()

    override suspend fun deleteAccount() = apiCall { api.deleteAccount() }

    override suspend fun setProfilePhoto(uploadId: String) = apiCall { api.setProfilePhoto(ConfirmUploadRequest(uploadId)) }.toAccount()

    override suspend fun removeProfilePhoto() = apiCall { api.removeProfilePhoto() }.toAccount()
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
    )
}

class ApiSubscriptionService(private val api: SnapCoreApi) : SubscriptionService {
    override suspend fun checkoutUrl() = apiCall { api.checkout() }.url
}
