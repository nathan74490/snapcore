package com.snapcore.app

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.SubscriptionService
import com.snapcore.app.domain.isPlusActive
import com.snapcore.app.ui.plus.PaywallViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private class FakeSubscriptions : SubscriptionService {
    var error: ApiException? = null
    var calls = 0

    override suspend fun checkoutUrl(): String {
        calls++
        error?.let { throw it }
        return "https://pay.rev.cat/sandbox/id-1"
    }
}

class PlusRuleTest {
    private val now = Instant.parse("2026-10-15T10:00:00Z")

    @Test
    fun `abonne seulement tant que la periode payee court`() {
        assertTrue(isPlusActive("premium", now.plusSeconds(60), now))
        assertFalse(isPlusActive("premium", now.minusSeconds(60), now))
        assertFalse(isPlusActive("premium", null, now))
        assertFalse(isPlusActive("free", now.plusSeconds(60), now))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var accounts: FakeAccounts
    private lateinit var subscriptions: FakeSubscriptions
    private val free = Account("id-1", "nathan", "moi@snapcore.test", null, null, isPremium = false)
    private val premium = free.copy(isPremium = true, premiumUntil = Instant.parse("2026-11-15T10:00:00Z"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccounts().apply { account = free }
        subscriptions = FakeSubscriptions()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PaywallViewModel(accounts, subscriptions, pollDelayMs = 1_000, pollAttempts = 3)

    @Test
    fun `s'abonner ouvre le lien de paiement propre au compte, puis attend l'activation`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.subscribe()
        advanceUntilIdle()

        assertEquals("https://pay.rev.cat/sandbox/id-1", vm.state.value.checkoutUrl)
        assertTrue(vm.state.value.awaitingActivation)
        vm.consumeCheckoutUrl()
        assertNull(vm.state.value.checkoutUrl)
    }

    @Test
    fun `retour du paiement, l'abonnement active par le webhook est detecte`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.subscribe()
        advanceUntilIdle()

        vm.checkActivation()
        advanceTimeBy(500)
        accounts.account = premium // le webhook arrive pendant l'attente
        advanceUntilIdle()

        assertTrue(vm.state.value.account!!.isPremium)
        assertFalse(vm.state.value.awaitingActivation)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `paiement non confirme, l'attente s'arrete avec un message clair`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.returnedFromCheckout()
        advanceUntilIdle()

        assertFalse(vm.state.value.awaitingActivation)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.account!!.isPremium)
    }

    @Test
    fun `sans paiement lance, revenir sur l'ecran ne declenche aucune attente`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.checkActivation()
        advanceUntilIdle()

        assertFalse(vm.state.value.awaitingActivation)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `offre indisponible cote backend, message et pas de lien`() = runTest(dispatcher) {
        subscriptions.error = ApiException(409, "SnapCore+ n'est pas encore disponible")
        val vm = vm()
        advanceUntilIdle()

        vm.subscribe()
        advanceUntilIdle()

        assertNull(vm.state.value.checkoutUrl)
        assertFalse(vm.state.value.awaitingActivation)
        assertEquals("SnapCore+ n'est pas encore disponible", vm.state.value.error)
    }
}
