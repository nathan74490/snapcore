package com.snapcore.app

import com.snapcore.app.domain.LaunchTarget
import com.snapcore.app.domain.PushDetails
import com.snapcore.app.domain.notificationFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushNotificationTest {
    @Test
    fun `snap recu, nomme l'expediteur et ouvre le Chat`() {
        val n = notificationFor(mapOf("type" to "message.received", "messageId" to "m1"), PushDetails("testeur", isSnap = true))!!
        assertEquals("Nouveau snap de testeur", n.text)
        assertEquals(LaunchTarget.CHAT, n.target)
    }

    @Test
    fun `chat recu sans reseau, texte generique`() {
        val n = notificationFor(mapOf("type" to "message.received", "messageId" to "m1"), PushDetails())!!
        assertEquals("Tu as reçu un message", n.text)
    }

    @Test
    fun `demande d'ami, ouvre l'ecran Amis`() {
        val n = notificationFor(mapOf("type" to "friend_request.received", "friendshipId" to "f1"), PushDetails("alice"))!!
        assertEquals("alice veut t'ajouter en ami", n.text)
        assertEquals(LaunchTarget.FRIENDS, n.target)
    }

    @Test
    fun `evenement d'affichage seul, aucune notification`() {
        assertNull(notificationFor(mapOf("type" to "snap.opened", "messageId" to "m1"), PushDetails()))
        assertNull(notificationFor(emptyMap(), PushDetails()))
    }
}
