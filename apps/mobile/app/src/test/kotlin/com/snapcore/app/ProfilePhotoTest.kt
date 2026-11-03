package com.snapcore.app

import com.snapcore.app.domain.Account
import com.snapcore.app.domain.SquareCrop
import com.snapcore.app.domain.centeredSquare
import com.snapcore.app.ui.profile.ProfileViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CenteredSquareTest {
    @Test
    fun `photo portrait, carre centre verticalement`() {
        assertEquals(SquareCrop(left = 0, top = 420, size = 1080), centeredSquare(1080, 1920))
    }

    @Test
    fun `photo paysage, carre centre horizontalement`() {
        assertEquals(SquareCrop(left = 1000, top = 0, size = 3000), centeredSquare(5000, 3000))
    }

    @Test
    fun `photo deja carree, rien a couper`() {
        assertEquals(SquareCrop(0, 0, 512), centeredSquare(512, 512))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhotoViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var accounts: FakeAccounts
    private lateinit var uploader: RecordingUploader
    private lateinit var preparer: FakePhotoPreparer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccounts().apply { account = Account("id", "nathan", null, null, null, false) }
        uploader = RecordingUploader()
        preparer = FakePhotoPreparer()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ProfileViewModel(FakeAuth().apply { signedIn = true }, accounts, FakePending(), FakeDevices(), uploader, preparer)

    @Test
    fun `photo choisie, preparee, envoyee en PROFILE_PHOTO, confirmee, puis fichier local supprime`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.changePhoto("content://media/picker/42")
        advanceUntilIdle()

        assertEquals(listOf("PROFILE_PHOTO"), uploader.uploads)
        assertEquals(listOf("upload-1"), accounts.confirmedUploads)
        assertEquals("https://cdn.test/profiles/upload-1", vm.state.value.account?.profilePhotoUrl)
        assertFalse(preparer.prepared.single().exists())
    }

    @Test
    fun `image illisible, message clair et aucun envoi`() = runTest(dispatcher) {
        preparer.fail = true
        val vm = vm()
        advanceUntilIdle()

        vm.changePhoto("content://media/picker/42")
        advanceUntilIdle()

        assertTrue(uploader.uploads.isEmpty())
        assertEquals("Photo illisible", vm.state.value.message)
    }

    @Test
    fun `retirer la photo revient a l'avatar par defaut`() = runTest(dispatcher) {
        accounts.account = accounts.account!!.copy(profilePhotoUrl = "https://cdn.test/profiles/old")
        val vm = vm()
        advanceUntilIdle()

        vm.removePhoto()
        advanceUntilIdle()

        assertNull(vm.state.value.account?.profilePhotoUrl)
    }
}
