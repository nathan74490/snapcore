package com.snapcore.app

import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.ReportReason
import com.snapcore.app.domain.ReportTarget
import com.snapcore.app.domain.SafetyService
import com.snapcore.app.ui.safety.BlockedUsersViewModel
import com.snapcore.app.ui.safety.SafetyOutcome
import com.snapcore.app.ui.safety.SafetyViewModel
import com.snapcore.app.ui.safety.message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class RecordingSafety(var fail: Boolean = false, val blockedUsers: MutableList<Friend> = mutableListOf()) : SafetyService {
    val calls = mutableListOf<String>()
    private fun record(call: String) {
        calls += call
        if (fail) error("Serveur injoignable")
    }
    override suspend fun removeFriend(userId: String) = record("remove:$userId")
    override suspend fun block(userId: String) = record("block:$userId")
    override suspend fun unblock(userId: String) {
        record("unblock:$userId")
        blockedUsers.removeAll { it.id == userId }
    }
    override suspend fun blocked() = blockedUsers.toList()
    override suspend fun report(target: ReportTarget, reason: ReportReason) = record("report:${target::class.simpleName}:${target.id}:${reason.name}")
}

// Retirer un ami, bloquer, débloquer et signaler depuis l'app (ADR-012, ADR-021 ; BUG-019 d'ADR-023).
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyTest {
    private val dispatcher = StandardTestDispatcher()
    private val lea = Friend("lea", "lea.martin", null, "Léa")
    private val tom = Friend("tom", "tom_grd", null, "Tom")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `retirer, bloquer et signaler appellent le backend et annoncent le resultat`() = runTest(dispatcher) {
        val safety = RecordingSafety()
        val vm = SafetyViewModel(safety)

        vm.removeFriend("lea")
        advanceUntilIdle()
        assertEquals(SafetyOutcome.REMOVED, vm.state.value.outcome)
        vm.consume()
        vm.block("lea")
        advanceUntilIdle()
        assertEquals(SafetyOutcome.BLOCKED, vm.state.value.outcome)
        vm.consume()
        vm.report(ReportTarget.Message("m1"), ReportReason.SPAM)
        vm.report(ReportTarget.StoryItem("s1"), ReportReason.SPAM) // ignoré : une action est déjà en cours
        advanceUntilIdle()

        assertEquals(listOf("remove:lea", "block:lea", "report:Message:m1:SPAM"), safety.calls)
        assertEquals("Merci, ton signalement a été envoyé à la modération", vm.state.value.outcome!!.message("Léa"))
        assertEquals("Tu as bloqué Léa", SafetyOutcome.BLOCKED.message("Léa"))
    }

    @Test
    fun `un echec affiche une erreur, sans resultat`() = runTest(dispatcher) {
        val vm = SafetyViewModel(RecordingSafety(fail = true))
        vm.block("lea")
        advanceUntilIdle()
        assertNull(vm.state.value.outcome)
        assertEquals("Serveur injoignable", vm.state.value.error)
    }

    @Test
    fun `utilisateurs bloques tries par nom, deblocage optimiste annule en cas d'echec`() = runTest(dispatcher) {
        val safety = RecordingSafety(blockedUsers = mutableListOf(tom, lea))
        val vm = BlockedUsersViewModel(safety)
        advanceUntilIdle()
        assertEquals(listOf("Léa", "Tom"), vm.state.value.users.map { it.displayName })

        vm.unblock(lea)
        assertEquals(listOf(tom), vm.state.value.users) // tout de suite
        advanceUntilIdle()
        assertEquals(listOf("unblock:lea"), safety.calls)

        safety.fail = true
        vm.unblock(tom)
        advanceUntilIdle()
        assertEquals(listOf(tom), vm.state.value.users) // revenu
        assertTrue(vm.state.value.error != null)
    }
}
