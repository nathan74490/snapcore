package com.snapcore.app

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.Session
import com.snapcore.app.domain.SessionResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionResolverTest {
    private lateinit var auth: FakeAuth
    private lateinit var accounts: FakeAccounts
    private lateinit var pending: FakePending
    private lateinit var resolver: SessionResolver

    @Before
    fun setUp() {
        auth = FakeAuth()
        accounts = FakeAccounts()
        pending = FakePending()
        resolver = SessionResolver(auth, accounts, pending)
    }

    @Test
    fun `sans session Firebase, affiche l'accueil`() = runTest {
        assertEquals(Session.SignedOut, resolver.resolve())
    }

    @Test
    fun `compte existant, ouvre l'app`() = runTest {
        auth.signedIn = true
        accounts.account = Account("id", "nathan", null, null, null, false)
        assertEquals("nathan", (resolver.resolve() as Session.Ready).account.username)
    }

    @Test
    fun `inscription interrompue, la reprend automatiquement avec les données locales`() = runTest {
        auth.signedIn = true
        pending.value = PendingRegistration("nathan", "+33600000000")

        val session = resolver.resolve()

        assertEquals("nathan", (session as Session.Ready).account.username)
        assertNull(pending.value)
    }

    @Test
    fun `compte Firebase sans compte SnapCore ni données locales, demande la fin d'inscription`() = runTest {
        auth.signedIn = true
        assertEquals(Session.NeedsRegistration, resolver.resolve())
    }

    @Test
    fun `token refusé (compte Firebase supprimé), déconnecte au lieu de boucler`() = runTest {
        auth.signedIn = true
        accounts.meError = ApiException(401, "Session invalide ou expirée")

        assertEquals(Session.SignedOut, resolver.resolve())
        assertFalse(auth.signedIn)
    }

    @Test
    fun `compte en cours de suppression, ne le recrée jamais`() = runTest {
        auth.signedIn = true
        pending.value = PendingRegistration("nathan", null)
        accounts.registerError = ApiException(404, "Compte introuvable")

        assertEquals(Session.SignedOut, resolver.resolve())
        assertTrue(auth.signOuts > 0)
    }
}
