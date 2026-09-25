package com.snapcore.app.data

import com.snapcore.app.data.api.RegisterRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.UpdateProfileRequest
import com.snapcore.app.data.api.UserDto
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.PendingRegistration

class ApiAccountService(private val api: SnapCoreApi) : AccountService {
    override suspend fun me() = apiCall { api.me() }.toAccount()

    override suspend fun register(registration: PendingRegistration) =
        apiCall { api.register(RegisterRequest(registration.username, registration.phoneNumber)) }.toAccount()

    override suspend fun updateProfile(username: String?, phoneNumber: String?) =
        apiCall { api.updateProfile(UpdateProfileRequest(username, phoneNumber)) }.toAccount()

    override suspend fun deleteAccount() = apiCall { api.deleteAccount() }
}

private fun UserDto.toAccount() = Account(
    id = id,
    username = username,
    email = email,
    phoneNumber = phoneNumber,
    profilePhotoUrl = profilePhotoUrl,
    isPremium = subscriptionTier == "premium",
)
