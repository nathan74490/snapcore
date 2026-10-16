package com.snapcore.app.domain

import java.time.Instant

// Un élément de story, avec sa propre expiration 24 h après SA publication (ADR-013).
data class StoryItem(
    val id: String,
    val kind: MediaKind,
    val createdAt: Instant,
    val expiresAt: Instant,
    val viewed: Boolean = false,
    val viewCount: Int = 0,
    /** URL courte de la vignette, qui ne compte pas de vue (ADR-026) ; null sans vignette. */
    val thumbnailUrl: String? = null,
)

data class FriendStory(val author: Friend, val items: List<StoryItem>, val hasUnviewed: Boolean, val thumbnailUrl: String? = null) {
    val lastPostedAt get() = items.maxOf { it.createdAt }
}

interface StoryService {
    /** Stories des amis ACCEPTED, non vues d'abord ; éléments du plus ancien au plus récent. */
    suspend fun feed(): List<FriendStory>
    suspend fun mine(): List<StoryItem>
    /** URL de lecture courte ; enregistre la vue côté backend (sauf pour sa propre story). */
    suspend fun viewUrl(item: StoryItem): String
    suspend fun viewers(item: StoryItem): List<Friend>
    suspend fun delete(item: StoryItem)
}

/** Position de lecture dans une story : l'index de départ est le premier élément non vu. */
fun firstUnviewedIndex(items: List<StoryItem>): Int = items.indexOfFirst { !it.viewed }.takeIf { it >= 0 } ?: 0
