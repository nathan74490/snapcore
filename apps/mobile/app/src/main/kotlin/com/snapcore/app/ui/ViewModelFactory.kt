package com.snapcore.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.snapcore.app.AppContainer
import com.snapcore.app.SnapCoreApplication

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as SnapCoreApplication).container

/** Crée un ViewModel à partir du conteneur de dépendances de l'application. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
