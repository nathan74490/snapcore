package com.snapcore.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.PendingRegistrationStore
import kotlinx.coroutines.flow.first

private val Context.registrationStore by preferencesDataStore(name = "pending_registration")

class DataStorePendingRegistrationStore(private val context: Context) : PendingRegistrationStore {
    private val usernameKey = stringPreferencesKey("username")
    private val phoneKey = stringPreferencesKey("phone")
    private val firstNameKey = stringPreferencesKey("firstName")

    override suspend fun load(): PendingRegistration? {
        val prefs = context.registrationStore.data.first()
        val username = prefs[usernameKey] ?: return null
        return PendingRegistration(username, prefs[phoneKey], prefs[firstNameKey].orEmpty())
    }

    override suspend fun save(registration: PendingRegistration) {
        context.registrationStore.edit { prefs ->
            prefs[usernameKey] = registration.username
            prefs[firstNameKey] = registration.firstName
            registration.phoneNumber?.let { prefs[phoneKey] = it } ?: prefs.remove(phoneKey)
        }
    }

    override suspend fun clear() {
        context.registrationStore.edit { it.clear() }
    }
}
