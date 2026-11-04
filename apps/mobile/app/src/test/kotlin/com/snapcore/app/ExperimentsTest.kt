package com.snapcore.app

import com.snapcore.app.domain.Account
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.Experiments
import com.snapcore.app.domain.PaywallCta
import com.snapcore.app.domain.SubscriptionService
import com.snapcore.app.ui.plus.PaywallViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Expériences tirées d'avance ; garde la trace des expositions. */
private class FixedExperiments(private val variants: Map<String, String>) : Experiments {
    val exposures = mutableListOf<String>()
    override fun variant(flag: String): String? {
        exposures += flag
        return variants[flag]
    }
}

// Expérience A/B n° 1 : libellé du bouton du paywall SnapCore+ (ADR-030).
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallExperimentTest {
    private val dispatcher = StandardTestDispatcher()
    private val free = Account("id-1", "nathan", null, null, null, isPremium = false)
    private val premium = free.copy(isPremium = true, premiumUntil = Instant.parse("2027-01-01T00:00:00Z"))
    private lateinit var accounts: FakeAccounts
    private val subscriptions = object : SubscriptionService {
        override suspend fun checkoutUrl() = "https://pay.rev.cat/sandbox/id-1"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccounts().apply { account = free }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `variante inconnue ou absente, le bouton témoin`() {
        assertEquals(PaywallCta.CONTROL, PaywallCta.of(null))
        assertEquals(PaywallCta.CONTROL, PaywallCta.of("autre"))
        assertEquals(PaywallCta.BENEFIT, PaywallCta.of("benefit"))
    }

    @Test
    fun `compte gratuit exposé une seule fois, et la conversion porte la variante vue`() = runTest(dispatcher) {
        val experiments = FixedExperiments(mapOf(PaywallCta.FLAG to "benefit"))
        val analytics = RecordingAnalytics()
        val vm = PaywallViewModel(accounts, subscriptions, analytics = analytics, initial = free, experiments = experiments)
        advanceUntilIdle()

        assertEquals(PaywallCta.BENEFIT, vm.state.value.cta)
        assertEquals(listOf(PaywallCta.FLAG), experiments.exposures) // pas de seconde exposition au rechargement du compte

        vm.subscribe()
        advanceUntilIdle()
        assertEquals("checkout_started" to mapOf("\$feature/paywall-cta" to "benefit"), analytics.events.single())
    }

    @Test
    fun `un abonné ne voit pas le bouton, il n'entre pas dans l'expérience`() = runTest(dispatcher) {
        accounts.account = premium
        val experiments = FixedExperiments(mapOf(PaywallCta.FLAG to "benefit"))
        val vm = PaywallViewModel(accounts, subscriptions, initial = premium, experiments = experiments)
        advanceUntilIdle()

        assertTrue(experiments.exposures.isEmpty())
        assertEquals(PaywallCta.CONTROL, vm.state.value.cta)
    }

    @Test
    fun `sans PostHog, le témoin et une conversion sans variante`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        val vm = PaywallViewModel(accounts, subscriptions, analytics = analytics)
        advanceUntilIdle()
        vm.subscribe()
        advanceUntilIdle()

        assertEquals(PaywallCta.CONTROL, vm.state.value.cta)
        assertEquals("checkout_started" to emptyMap<String, Any>(), analytics.events.single())
    }
}
