package com.snapcore.app

import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendStory
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.StoryItem
import com.snapcore.app.domain.StoryService
import com.snapcore.app.ui.stories.PlayerState
import com.snapcore.app.ui.stories.StoryPlayerViewModel
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

class FakeStories(private val friendStory: FriendStory, private val own: List<StoryItem> = emptyList()) : StoryService {
    val viewed = mutableListOf<String>()
    val expired = mutableSetOf<String>()

    override suspend fun feed() = listOf(friendStory)
    override suspend fun mine() = own
    override suspend fun viewUrl(item: StoryItem): String {
        if (item.id in expired) throw IllegalStateException("Story introuvable")
        viewed += item.id
        return "https://r2.test/stories/${item.id}"
    }
    override suspend fun viewers(item: StoryItem) = emptyList<Friend>()
    override suspend fun delete(item: StoryItem) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class StoryPlayerTest {
    private val dispatcher = StandardTestDispatcher()
    private val t = Instant.parse("2026-09-25T10:00:00Z")
    private fun item(id: String, viewed: Boolean) = StoryItem(id, MediaKind.PHOTO, t, t.plusSeconds(86_400), viewed)
    private val story = FriendStory(
        Friend("a", "alice", null),
        listOf(item("s1", viewed = true), item("s2", viewed = false), item("s3", viewed = false)),
        hasUnviewed = true,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val StoryPlayerViewModel.playing get() = state.value as PlayerState.Playing

    @Test
    fun `reprend au premier element non vu et enregistre la vue de chaque element affiche`() = runTest(dispatcher) {
        val service = FakeStories(story)
        val vm = StoryPlayerViewModel(service, "a", null)
        advanceUntilIdle()

        assertEquals("s2", vm.playing.current.id)
        vm.next()
        advanceUntilIdle()
        assertEquals("s3", vm.playing.current.id)
        assertEquals(listOf("s2", "s3"), service.viewed)

        vm.next()
        advanceUntilIdle()
        assertTrue(vm.state.value is PlayerState.Finished)
    }

    @Test
    fun `precedent revient en arriere, sans sortir avant le premier element`() = runTest(dispatcher) {
        val vm = StoryPlayerViewModel(FakeStories(story), "a", "s1")
        advanceUntilIdle()
        vm.previous()
        advanceUntilIdle()
        assertEquals("s1", vm.playing.current.id)
    }

    @Test
    fun `un element expire entre-temps est saute`() = runTest(dispatcher) {
        val service = FakeStories(story).apply { expired += "s2" }
        val vm = StoryPlayerViewModel(service, "a", null)
        advanceUntilIdle()
        assertEquals("s3", vm.playing.current.id)
    }
}
