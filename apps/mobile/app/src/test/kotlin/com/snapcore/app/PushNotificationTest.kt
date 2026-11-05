package com.snapcore.app

import com.snapcore.app.domain.LaunchTarget
import com.snapcore.app.domain.PushDetails
import com.snapcore.app.domain.PushKind
import com.snapcore.app.domain.notificationFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushNotificationTest {
    @Test
    fun `snap recu, nomme l'expediteur et ouvre le Chat`() {
        val n = notificationFor(mapOf("type" to "message.received", "messageId" to "m1"), PushDetails("testeur", isSnap = true))!!
        assertEquals("Nouveau snap de testeur", n.text)
        assertEquals("testeur", n.title)
        assertEquals(PushKind.PHOTO, n.kind)
        assertEquals("Nouveau snap photo", n.previewText)
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
    fun `video de groupe, avatar et identification conserves meme sans resolution reseau`() {
        val data = mapOf("type" to "group.message.received", "groupId" to "g1", "senderName" to "Léa",
            "groupName" to "Soirée samedi", "contentType" to "SNAP_PHOTO_VIDEO")
        val n = notificationFor(data, PushDetails())!!
        assertEquals("Soirée samedi", n.title)
        assertEquals("Léa", n.senderName)
        assertEquals(PushKind.VIDEO, n.kind)
        assertEquals("Léa a envoyé un snap", n.text)
        assertEquals("Léa · Nouveau snap vidéo", n.previewText)
        assertEquals(true, n.isGroup)
    }

    @Test
    fun `le chat utilise le nom et l'avatar sans exposer le contenu du message`() {
        val n = notificationFor(mapOf("type" to "message.received", "content" to "texte privé"),
            PushDetails("Hugo", false, avatarUrl = "https://cdn/avatar.png"))!!
        assertEquals("Hugo", n.title)
        assertEquals("https://cdn/avatar.png", n.avatarUrl)
        assertEquals(PushKind.CHAT, n.kind)
        assertEquals("Nouveau chat de Hugo", n.text)
        assertEquals(false, n.isGroup)
    }

    @Test
    fun `evenement d'affichage seul, aucune notification`() {
        assertNull(notificationFor(mapOf("type" to "snap.opened", "messageId" to "m1"), PushDetails()))
        assertNull(notificationFor(emptyMap(), PushDetails()))
    }
}
