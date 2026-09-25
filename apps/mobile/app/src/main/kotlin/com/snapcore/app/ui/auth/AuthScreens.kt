package com.snapcore.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Session
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.SnapTextField
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@Composable
fun StartScreen(onSession: (Session) -> Unit) {
    val vm = containerViewModel { StartViewModel(it.sessionResolver) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { (state as? StartState.Resolved)?.let { onSession(it.session) } }

    Box(Modifier.fillMaxSize().background(SnapColors.Yellow), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is StartState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(s.message, color = SnapColors.Black, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Réessayer", vm::resolve, container = SnapColors.Black)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Logo()
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(color = SnapColors.Black)
            }
        }
    }
}

@Composable
private fun Logo() {
    Icon(Icons.Filled.ChatBubble, contentDescription = null, tint = SnapColors.Black, modifier = Modifier.size(96.dp))
}

@Composable
fun WelcomeScreen(onLogin: () -> Unit, onSignUp: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(SnapColors.Yellow).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Logo()
        Text("SnapCore", style = MaterialTheme.typography.headlineMedium, color = SnapColors.Black)
        Spacer(Modifier.weight(1f))
        // Disposition de l'écran d'accueil Snapchat : deux grands boutons pleine largeur en bas.
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Connexion", onLogin, container = SnapColors.SnapRed)
            PrimaryButton("Inscription", onSignUp, container = SnapColors.ChatBlue)
        }
    }
}

@Composable
private fun FormScaffold(
    title: String,
    onBack: () -> Unit,
    progress: Float? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
        progress?.let { LinearProgressIndicator(progress = { it }, color = SnapColors.ChatBlue, modifier = Modifier.fillMaxWidth()) }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            content()
        }
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, onSession: (Session) -> Unit) {
    val vm = containerViewModel { LoginViewModel(it.auth, it.sessionResolver) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }

    FormScaffold("Connexion à SnapCore", onBack) {
        SnapTextField(state.email, vm::onEmail, "Email", keyboardType = KeyboardType.Email)
        SnapTextField(state.password, vm::onPassword, "Mot de passe", password = true)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Connexion", vm::submit, loading = state.loading)
    }
}

@Composable
fun SignUpScreen(onBack: () -> Unit, onSession: (Session) -> Unit) {
    val vm = containerViewModel { SignUpViewModel(it.auth, it.accounts, it.pendingRegistration) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }

    val title = when (state.step) {
        1 -> "Choisis ton pseudo"
        2 -> "Tes coordonnées"
        else -> if (state.firebaseAccountCreated) "Finalise ton inscription" else "Choisis un mot de passe"
    }
    FormScaffold(title, onBack = { if (state.step == 1) onBack() else vm.back() }, progress = state.step / SignUpState.STEPS.toFloat()) {
        Text("Étape ${state.step} sur ${SignUpState.STEPS}", color = SnapColors.Grey)
        when (state.step) {
            1 -> SnapTextField(
                state.username,
                vm::onUsername,
                "Pseudo",
                error = state.usernameError,
                supporting = "Tu pourras le changer plus tard",
            )
            2 -> {
                SnapTextField(
                    state.email,
                    vm::onEmail,
                    "Email",
                    error = state.emailError,
                    keyboardType = KeyboardType.Email,
                    enabled = !state.firebaseAccountCreated,
                    supporting = "Un email de confirmation te sera envoyé",
                )
                SnapTextField(
                    state.phone,
                    vm::onPhone,
                    "Téléphone (facultatif)",
                    error = state.phoneError,
                    keyboardType = KeyboardType.Phone,
                )
            }
            else -> if (!state.firebaseAccountCreated) {
                SnapTextField(
                    state.password,
                    vm::onPassword,
                    "Mot de passe",
                    error = state.passwordError,
                    password = true,
                    supporting = "Au moins 6 caractères",
                )
            } else {
                Text("Ton compte est créé, il ne reste plus qu'à l'enregistrer.", textAlign = TextAlign.Center)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(if (state.step < SignUpState.STEPS) "Continuer" else "S'inscrire", vm::next, loading = state.loading)
    }
}

@Composable
fun CompleteRegistrationScreen(onSession: (Session) -> Unit) {
    val vm = containerViewModel { CompleteRegistrationViewModel(it.auth, it.accounts, it.pendingRegistration) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }

    FormScaffold("Termine ton inscription", onBack = vm::signOut) {
        Text("Connecté avec ${vm.email ?: "ton compte"}", color = SnapColors.Grey, textAlign = TextAlign.Center)
        SnapTextField(state.username, vm::onUsername, "Pseudo", error = state.usernameError)
        SnapTextField(state.phone, vm::onPhone, "Téléphone (facultatif)", error = state.phoneError, keyboardType = KeyboardType.Phone)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PrimaryButton("Terminer", vm::submit, loading = state.loading)
        TextButton(onClick = vm::signOut) { Text("Utiliser un autre compte") }
    }
}
