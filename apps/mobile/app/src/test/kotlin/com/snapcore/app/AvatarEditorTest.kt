package com.snapcore.app

import com.snapcore.app.domain.Account
import com.snapcore.app.ui.profile.AvatarEditorViewModel
import com.snapcore.app.ui.profile.AvatarPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

// Éditeur d'avatar maison (ADR-028).
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarEditorTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var accounts: FakeAccounts

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccounts().apply { account = Account("u1", "nathan", null, null, null, false) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `sans avatar l'editeur part des valeurs par defaut, un avatar existant les complete`() = runTest(dispatcher) {
        val fresh = AvatarEditorViewModel(accounts, initial = null)
        advanceUntilIdle()
        assertEquals(accounts.catalog.defaults, fresh.state.value.options)

        val existing = AvatarEditorViewModel(accounts, initial = mapOf("top" to "bob"))
        advanceUntilIdle()
        assertEquals("bob", existing.state.value.options["top"])
        assertEquals("smile", existing.state.value.options["mouth"])
    }

    @Test
    fun `un choix change une seule partie, puis l'enregistrement renvoie le compte a jour`() = runTest(dispatcher) {
        val vm = AvatarEditorViewModel(accounts, initial = null)
        advanceUntilIdle()

        vm.choose(AvatarPart.FACIAL_HAIR, "beardLight")
        vm.save()
        advanceUntilIdle()

        assertEquals("beardLight", accounts.savedAvatar?.get("facialHair"))
        assertEquals("shortFlat", accounts.savedAvatar?.get("top"))
        assertEquals("avatar.png", vm.state.value.saved?.avatarUrl)
    }

    @Test
    fun `le tirage au sort reste sur des expressions avenantes`() = runTest(dispatcher) {
        val vm = AvatarEditorViewModel(accounts, initial = null, random = Random(42))
        advanceUntilIdle()
        repeat(50) {
            vm.randomize()
            assertNotEquals("cry", vm.state.value.options["eyes"])
            assertEquals(accounts.catalog.options.keys, vm.state.value.options.keys)
        }
    }

    @Test
    fun `retirer l'avatar revient aux initiales ou a la photo`() = runTest(dispatcher) {
        accounts.account = accounts.account!!.copy(avatar = mapOf("top" to "bob"), avatarUrl = "avatar.png")
        val vm = AvatarEditorViewModel(accounts, initial = mapOf("top" to "bob"))
        advanceUntilIdle()

        vm.remove()
        advanceUntilIdle()

        assertNull(vm.state.value.saved?.avatarUrl)
        assertNull(vm.state.value.saved?.pictureUrl)
    }
}
