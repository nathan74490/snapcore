package com.snapcore.app.ui.home

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Un pager conserve ses voisins ; la caméra ne doit fonctionner que lorsqu'elle est visible. */
@Composable
internal fun CameraPageLifecycle(visible: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) {
        object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
    }
    val isVisible by rememberUpdatedState(visible)
    fun update() {
        val limit = if (isVisible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        owner.registry.currentState = minOf(parent.lifecycle.currentState, limit)
    }
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        update()
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
    LaunchedEffect(visible) { update() }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}
