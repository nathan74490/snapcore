package com.snapcore.app.ui.components

import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * Vrai quand un lecteur d'écran qui explore au toucher (TalkBack) est actif ; suit son activation en direct.
 * Sert à adapter l'onboarding (ADR-033) : pas de clavier ouvert d'office, pas d'animation de transition,
 * et le focus posé sur la question de chaque écran.
 */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * Place le focus de TalkBack sur cet élément dès qu'il apparaît (ou quand [key] change), et le fait lire.
 * Le focus clavier ne suffit pas : TalkBack garde son propre focus, par exemple sur le bouton Continuer qui
 * existe encore à l'écran suivant. On passe donc par l'action ACTION_ACCESSIBILITY_FOCUS, celle que TalkBack
 * envoie lui-même quand on touche un élément. Sans lecteur d'écran, ne fait rien.
 */
@Composable
fun Modifier.screenReaderFocusOnAppear(key: Any? = Unit): Modifier {
    val screenReader = rememberScreenReaderOn()
    val view = LocalView.current
    val tag = remember { "a11y-focus-" + java.util.UUID.randomUUID() }
    LaunchedEffect(key, screenReader) {
        if (!screenReader) return@LaunchedEffect
        // Quelques essais : l'arbre d'accessibilité n'est à jour qu'après la mise en page de l'écran.
        repeat(6) {
            delay(250)
            if (requestAccessibilityFocus(view, tag)) return@LaunchedEffect
        }
    }
    return testTag(tag)
}

private fun requestAccessibilityFocus(view: View, tag: String): Boolean {
    @Suppress("VisibleForTests")
    val owner = (view as? ViewRootForTest)?.semanticsOwner ?: return false
    val node = findTagged(owner.unmergedRootSemanticsNode, tag) ?: return false
    return view.accessibilityNodeProvider?.performAction(node.id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) == true
}

private fun findTagged(node: SemanticsNode, tag: String): SemanticsNode? {
    if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return node
    return node.children.firstNotNullOfOrNull { findTagged(it, tag) }
}

/**
 * Comme [screenReaderFocusOnAppear], mais seulement quand [active] devient vrai : pour un élément toujours
 * composé mais pas toujours visible (page de l'accueil à onglets qui devient la page courante, etc.).
 */
@Composable
fun Modifier.screenReaderFocusWhen(active: Boolean): Modifier {
    val screenReader = rememberScreenReaderOn()
    val view = LocalView.current
    val tag = remember { "a11y-focus-" + java.util.UUID.randomUUID() }
    LaunchedEffect(active, screenReader) {
        if (!active || !screenReader) return@LaunchedEffect
        repeat(6) {
            delay(250)
            if (requestAccessibilityFocus(view, tag)) return@LaunchedEffect
        }
    }
    return testTag(tag)
}

/**
 * Écran de l'app pour TalkBack (ADR-033) : son nom est annoncé quand il s'ouvre (« Conversation avec Léa »,
 * « Mon profil »), comme le titre d'une nouvelle fenêtre.
 */
@Composable
fun AccessibleScreen(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().semantics { paneTitle = title }) { content() }
}
