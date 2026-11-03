package com.snapcore.app.data

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.snapcore.app.domain.ContactsReader
import com.snapcore.app.domain.PhoneContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lit les contacts qui ont un numéro de téléphone (permission READ_CONTACTS accordée au préalable).
 * Rien n'est conservé : la liste sert le temps de l'écran « Ajouter des amis », et seuls les numéros
 * partent au backend pour la correspondance (ADR-029).
 */
class AndroidContactsReader(private val context: Context) : ContactsReader {
    override suspend fun read(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val byContact = linkedMapOf<Long, Pair<String, MutableSet<String>>>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null,
            "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: number
                byContact.getOrPut(cursor.getLong(0)) { name to linkedSetOf() }.second += number.trim()
            }
        }
        byContact.values.map { (name, numbers) -> PhoneContact(name, numbers.toList()) }
    }
}
