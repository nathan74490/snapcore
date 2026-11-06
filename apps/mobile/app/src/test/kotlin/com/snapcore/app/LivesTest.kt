package com.snapcore.app

import com.snapcore.app.ui.stories.LivesViewModel
import com.snapcore.app.ui.stories.audienceLabel
import fr.echimine.live.core.LiveError
import fr.echimine.live.core.LiveErrorCode
import fr.echimine.live.core.LiveResult
import fr.echimine.live.core.LiveSummary
import fr.echimine.live.core.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Section « Lives » de la page Stories (ADR-035) : les Lives en cours de toutes les App membres. */
@OptIn(ExperimentalCoroutinesApi::class)
class LivesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val live = LiveSummary(
        id = "lv_1",
        title = "Unboxing",
        host = Participant("twitch:u_88", "twitch", "Noa"),
        audience = 12,
        startedAt = Instant.parse("2026-11-06T10:00:00Z"),
    )

    @Test
    fun `les Lives en cours s'affichent apres le chargement`() = runTest(dispatcher) {
        val vm = LivesViewModel { LiveResult.Success(listOf(live)) }
        advanceUntilIdle()

        assertEquals(listOf(live), vm.state.value.lives)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `un echec garde les Lives deja affiches et l'annonce, le rechargement suivant l'efface`() = runTest(dispatcher) {
        var result: LiveResult<List<LiveSummary>> = LiveResult.Success(listOf(live))
        val vm = LivesViewModel { result }
        advanceUntilIdle()

        result = LiveResult.Failure(LiveError(LiveErrorCode.NotConnected, "Serveur injoignable."))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(live), vm.state.value.lives)
        assertEquals("Les Lives n'ont pas pu être chargés.", vm.state.value.error)

        result = LiveResult.Success(emptyList())
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptyList<LiveSummary>(), vm.state.value.lives)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `l'Audience s'accorde en francais`() {
        assertEquals("0 spectateur", audienceLabel(0))
        assertEquals("1 spectateur", audienceLabel(1))
        assertEquals("12 spectateurs", audienceLabel(12))
    }
}
