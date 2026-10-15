package com.snapcore.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.snapcore.app.domain.CameraFilter
import com.snapcore.app.domain.CameraFilters
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.FilterRenderer
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.ui.camera.PreviewViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Écrit un nouveau fichier par rendu, comme l'adaptateur réel ; [failing] simule un échec. */
private class FakeFilterRenderer(private val dir: File) : FilterRenderer {
    val rendered = mutableListOf<Pair<File, String>>()
    var failing = false

    override suspend fun render(media: CapturedMedia, filter: CameraFilter): CapturedMedia {
        if (failing) error("encodeur indisponible")
        if (filter.matrix == null) return media
        val file = File(dir, "filtered-${rendered.size}.jpg").apply { writeText(filter.id) }
        rendered += file to filter.id
        return CapturedMedia(file, media.kind)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var dir: File
    private lateinit var original: CapturedMedia
    private lateinit var renderer: FakeFilterRenderer
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dir = Files.createTempDirectory("captures").toFile()
        original = CapturedMedia(File(dir, "capture.jpg").apply { writeText("brut") }, MediaKind.PHOTO)
        renderer = FakeFilterRenderer(dir)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    // Passe par un ViewModelStore pour pouvoir déclencher onCleared, comme la navigation.
    private fun vm(): PreviewViewModel {
        val factory = viewModelFactory { initializer { PreviewViewModel(original, renderer) } }
        return ViewModelProvider.create(store, factory)[PreviewViewModel::class]
    }

    @Test
    fun `swipe a gauche = filtre suivant, a droite = precedent, en boucle`() {
        val vm = vm()
        vm.swipe(1)
        assertEquals(CameraFilters.ALL[1], vm.state.value.filter)
        vm.swipe(-1)
        vm.swipe(-1)
        assertEquals(CameraFilters.ALL.last(), vm.state.value.filter)
    }

    @Test
    fun `envoyer incruste le filtre choisi dans un nouveau fichier, l'original reste intact`() = runTest(dispatcher) {
        val vm = vm()
        vm.swipe(1)
        vm.confirm()
        advanceUntilIdle()

        val ready = vm.state.value.ready
        assertNotNull(ready)
        assertEquals(CameraFilters.ALL[1].id, ready!!.file.readText())
        assertEquals("brut", original.file.readText())
        assertFalse(vm.state.value.rendering)
    }

    @Test
    fun `filtre Normal, aucun rendu, l'original est envoye tel quel`() = runTest(dispatcher) {
        val vm = vm()
        vm.confirm()
        advanceUntilIdle()

        assertEquals(original, vm.state.value.ready)
        assertTrue(renderer.rendered.isEmpty())
    }

    @Test
    fun `retour a l'apercu puis autre filtre, l'ancien rendu est supprime`() = runTest(dispatcher) {
        val vm = vm()
        vm.swipe(1)
        vm.confirm()
        advanceUntilIdle()
        vm.consumeReady()
        val first = renderer.rendered.single().first

        vm.swipe(1)
        vm.confirm()
        advanceUntilIdle()

        assertFalse(first.exists())
        assertEquals(CameraFilters.ALL[2].id, vm.state.value.ready!!.file.readText())
    }

    @Test
    fun `echec du rendu, message clair et l'apercu reste utilisable`() = runTest(dispatcher) {
        renderer.failing = true
        val vm = vm()
        vm.swipe(1)
        vm.confirm()
        advanceUntilIdle()

        assertNull(vm.state.value.ready)
        assertEquals("Impossible d'appliquer le filtre", vm.state.value.error)
        assertFalse(vm.state.value.rendering)
    }

    @Test
    fun `apercu quitte pour de bon, original et rendu supprimes du cache`() = runTest(dispatcher) {
        val vm = vm()
        vm.swipe(1)
        vm.confirm()
        advanceUntilIdle()

        store.clear()

        assertFalse(original.file.exists())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
